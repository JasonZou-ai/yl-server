package cn.yl.modules.archive.service;

import cn.yl.api.security.LoginUser;
import cn.yl.common.api.PageResult;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.common.ratelimit.RateLimiter;
import cn.yl.modules.archive.domain.entity.Elder;
import cn.yl.modules.archive.domain.entity.ElderFamilyBind;
import cn.yl.modules.archive.dto.FamilyBindingCreateRequest;
import cn.yl.modules.archive.dto.FamilyBindingQuery;
import cn.yl.modules.archive.dto.FamilyBindingView;
import cn.yl.modules.archive.mapper.ElderFamilyBindMapper;
import cn.yl.modules.archive.mapper.ElderMapper;
import cn.yl.modules.archive.mapper.FamilyBindingVerifyMapper;
import cn.yl.modules.archive.support.SensitiveFieldSupport;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 亲情绑定状态机服务（D-4）：申请 / 机构审核 / 老人拒绝 / 解绑 + 列表。
 *
 * <p><b>五态转移</b>（{@code elder_family_bind.status}，唯一口径见设计 §3.3）：0-待老人确认 / 1-待机构审核 / 2-生效 / 3-已拒绝 /
 * 4-已解绑或过期。
 *
 * <ul>
 *   <li>{@link #apply}：无活性行 → <b>0 或 1</b>（按 {@code hasLoginAccount(elderId)} 分派）；已存在 <b>{@code
 *       status ∈ {0,1,2}}</b> → {@code 10005}（重复申请 / 已有生效绑定）；已存在 <b>{@code status ∈ {3,4}}</b> →
 *       <b>复用同一活性行复位</b>（{@code status}=0/1、{@code reject_reason}=NULL、{@code verify_method}/{@code
 *       proof_cos_key} 覆盖为新值；{@code auth_start_at}/{@code auth_end_at} <b>不动</b>）—— 见 §R-2 裁决；
 *   <li>{@link #approve}：<b>{0,1} → 2</b>（★C2 宽容化：老人不注册/未表态时机构<b>直接</b>放行）；
 *   <li>{@link #reject}：{0,1} → 3；
 *   <li>{@link #unbind}：2 → 4（{@code deleted} 保持 0，不物理删）。
 * </ul>
 *
 * <p><b>★ R-2 裁决（重新申请复用活性行）</b>：{@code active_uk = IF(deleted=0, 1, NULL)} ⇒ 只有 {@code deleted=1}
 * 才让位；同一老人多条历史解绑行靠 {@code deleted=1} 保留，<b>不得</b>用 {@code deleted} 表达业务状态（否则该行对所有 {@code deleted=0}
 * 读路径消失）。故「重新申请」<b>复用</b>既有的 {@code deleted=0} 行（{@code status ∈ {3,4}} → 复位）， <b>不新增行</b>。
 *
 * <p><b>★F2 核验</b>：{@link #apply} 经 {@link FamilyBindingVerifyMapper}（本卡唯一绕过第三闸的读）以 {@code
 * name_hash} + {@code id_card_hash} 精确核验；编解码器未装配 → 直拒（{@code 40001}）且<b>零 insert</b>。
 *
 * <p><b>数据域（第三闸）</b>：读/写均经 Mapper 发起，行级收口自动生效（scope=5 本表按 {@code family_user_id}；scope=2 经 {@code
 * elder.org_id} 反查；scope=1 经 {@code elder.user_id} 反查）。服务层<b>不再手写</b>机构/关系过滤。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FamilyBindingService {

    /** 0-待老人确认。 */
    private static final int STATUS_PENDING_ELDER = 0;

    /** 1-待机构审核。 */
    private static final int STATUS_PENDING_ORG = 1;

    /** 2-生效。 */
    private static final int STATUS_ACTIVE = 2;

    /** 3-已拒绝。 */
    private static final int STATUS_REJECTED = 3;

    /** 4-已解绑或过期。 */
    private static final int STATUS_UNBOUND = 4;

    private static final int DELETED_NO = 0;

    /** 核验方式白名单（本期仅 SCAN 有真实链路；PHONE/FACE 属 P2-8 核身通道）。 */
    private static final Set<String> VERIFY_METHODS = Set.of("SCAN", "PHONE", "FACE");

    /** 核验频控：窗口 60s，上限 10 次（按 userId）。 */
    private static final int VERIFY_RATE_LIMIT = 10;

    private static final int VERIFY_RATE_WINDOW_SECONDS = 60;

    private static final String RATE_DIMENSION_PREFIX = "fam:verify:";

    private static final int DEFAULT_PAGE_NUM = 1;

    private static final int DEFAULT_PAGE_SIZE = 10;

    private final ElderFamilyBindMapper bindMapper;

    private final FamilyBindingVerifyMapper verifyMapper;

    private final ElderMapper elderMapper;

    private final SensitiveFieldSupport support;

    private final RateLimiter rateLimiter;

    /**
     * 发起绑定申请（家属/机构）。
     *
     * <p>顺序（设计 §2.2 / §5.1，R-2 裁决后）：① 频控（{@code 10429}）→ ② 核验方式白名单（{@code 10001}）→ ③ 编解码器未装配（{@code
     * 40001}，零 insert）→ ④ 算 {@code name_hash}/{@code id_card_hash} → ⑤ 精确计数（{@code ==0} → {@code
     * 10004}， 与"老人不存在"同码防枚举）→ ⑥ 据 {@code hasLoginAccount} 定落态（有账号→0，无账号→1）→ ⑦ 查同对 {@code deleted=0}
     * 活性行：<b>无</b> → ⑧ insert；<b>{@code status ∈ {0,1,2}}</b> → {@code 10005}；<b>{@code status ∈
     * {3,4}}</b> → 复用该活性行<b>复位</b>（不新增行）。
     *
     * <p><b>★ 核验 + 频控照旧先跑</b>：复用分支<b>不跳过</b>核验 —— 申请人可能已换人（原申请人/新申请人），故每一次 {@code apply}
     * 都必须重新核验老人身份并受频控约束。
     *
     * @param req 申请入参（明文核验字段不落库）
     * @param operator 登录态操作者（{@code familyUserId} 取登录态，请求体不承载身份）
     * @return 绑定视图（申请阶段 {@code elderName} 为 {@code null}）
     */
    @Transactional(rollbackFor = Exception.class)
    public FamilyBindingView apply(FamilyBindingCreateRequest req, LoginUser operator) {
        long userId = operator.userId();
        if (!rateLimiter.tryAcquire(
                RATE_DIMENSION_PREFIX + userId, VERIFY_RATE_LIMIT, VERIFY_RATE_WINDOW_SECONDS)) {
            throw new BizException(ErrorCode.RATE_LIMITED);
        }
        String verifyMethod =
                req.getVerifyMethod() == null
                        ? ""
                        : req.getVerifyMethod().trim().toUpperCase(Locale.ROOT);
        if (!VERIFY_METHODS.contains(verifyMethod)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "不支持的核验方式：" + req.getVerifyMethod());
        }
        if (!support.isAvailable()) {
            // 宁可拒绝，不可假核验：假核验会绑定到非目标老人，比拒绝更危险（设计 §1.1c）。零 insert。
            throw new BizException(
                    ErrorCode.ARCHIVE_SENSITIVE_ENCRYPT_FAILED, "敏感字段编解码器未装配，无法核验老人身份");
        }
        String nameHash = support.hashForSearch(req.getElderName());
        String idCardHash = support.hashForSearch(req.getIdCardNo());
        int matched = verifyMapper.countExactMatch(req.getElderId(), nameHash, idCardHash);
        if (matched == 0) {
            // 与"老人不存在"同码（10004），不泄露存在性（设计 §1.1e）
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        boolean elderHasAccount = verifyMapper.hasLoginAccount(req.getElderId());
        int status = elderHasAccount ? STATUS_PENDING_ELDER : STATUS_PENDING_ORG;
        // ⑦ 同对（elder_id + family_user_id）的 deleted=0 活性行（第三闸内：scope=5 经 family_user_id=me 收口）
        ElderFamilyBind existing = findActiveBind(req.getElderId(), userId);
        if (existing == null) {
            // ⑧ 无活性行 → INSERT，落态按 hasLoginAccount（不变）
            return insertNewBind(req, userId, verifyMethod, status);
        }
        Integer existingStatus = existing.getStatus();
        if (existingStatus != null
                && (existingStatus == STATUS_PENDING_ELDER
                        || existingStatus == STATUS_PENDING_ORG
                        || existingStatus == STATUS_ACTIVE)) {
            // status ∈ {0,1,2}：重复申请 / 已有生效绑定（不新增行、不覆盖）
            throw new BizException(ErrorCode.CONFLICT, "该老人已存在绑定关系（含申请中/已生效），请勿重复申请");
        }
        if (existingStatus == null
                || (existingStatus != STATUS_REJECTED && existingStatus != STATUS_UNBOUND)) {
            // 防御：未知状态一律同码 10005（不静默放行）
            throw new BizException(ErrorCode.CONFLICT, "该老人已存在绑定关系，请勿重复申请");
        }
        // status ∈ {3,4}：复用同一活性行复位（deleted=1 只表软删，不得表达业务状态 ⇒ 不新增行）
        return resetReusableBind(existing, req, verifyMethod, status);
    }

    /** 无活性行：新增绑定行（{@code deleted=0}，落态按 {@code hasLoginAccount}）。 */
    private FamilyBindingView insertNewBind(
            FamilyBindingCreateRequest req, long userId, String verifyMethod, int status) {
        ElderFamilyBind bind = new ElderFamilyBind();
        bind.setElderId(req.getElderId());
        bind.setFamilyUserId(userId);
        bind.setRelation(req.getRelation());
        bind.setVerifyMethod(verifyMethod);
        bind.setProofCosKey(req.getProofCosKey());
        bind.setStatus(status);
        bind.setDeleted(DELETED_NO);
        try {
            bindMapper.insert(bind);
        } catch (DuplicateKeyException e) {
            // uk_elder_family(elder_id, family_user_id, active_uk) 冲突兜底：绝不 500（设计 §4）
            throw new BizException(ErrorCode.CONFLICT, "该老人已存在绑定关系（含申请中/已生效）");
        }
        return toView(bind, null);
    }

    /**
     * 复用活性行复位（{@code status ∈ {3,4}} → 0/1）：{@code status}=0/1、{@code reject_reason}=NULL、 {@code
     * verify_method}/{@code proof_cos_key} 覆盖为新值；{@code auth_start_at}/{@code auth_end_at}
     * <b>不动</b>（不重签有效期）。
     *
     * <p>用 {@link UpdateWrapper} 显式 {@code set(..., null)}：MyBatis-Plus {@code updateById} 默认忽略
     * null 字段，无法把 {@code reject_reason} 复位为 NULL。
     */
    private FamilyBindingView resetReusableBind(
            ElderFamilyBind existing,
            FamilyBindingCreateRequest req,
            String verifyMethod,
            int status) {
        UpdateWrapper<ElderFamilyBind> reset = new UpdateWrapper<>();
        reset.eq("id", existing.getId())
                .set("status", status)
                .set("reject_reason", null)
                .set("verify_method", verifyMethod)
                .set("proof_cos_key", req.getProofCosKey());
        try {
            bindMapper.update(null, reset);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.CONFLICT, "该老人已存在绑定关系（含申请中/已生效）");
        }
        existing.setStatus(status);
        existing.setVerifyMethod(verifyMethod);
        existing.setProofCosKey(req.getProofCosKey());
        existing.setRejectReason(null);
        return toView(existing, null);
    }

    /**
     * 机构审核放行：<b>{0,1} → 2</b>，{@code auth_start_at = now}（★C2 宽容化）。
     *
     * <p>越界/不存在/状态非法<b>同码</b> {@code 10004}（不泄露存在性）。
     *
     * @param bindingId 绑定关系 id
     * @param operator 登录态操作者（机构管理员）
     * @return 绑定视图
     */
    @Transactional(rollbackFor = Exception.class)
    public FamilyBindingView approve(Long bindingId, LoginUser operator) {
        ElderFamilyBind bind = requireBinding(bindingId);
        requirePending(bind);
        ElderFamilyBind update = new ElderFamilyBind();
        update.setId(bind.getId());
        update.setStatus(STATUS_ACTIVE);
        update.setAuthStartAt(LocalDateTime.now());
        bindMapper.updateById(update);
        bind.setStatus(STATUS_ACTIVE);
        bind.setAuthStartAt(update.getAuthStartAt());
        return toView(bind, null);
    }

    /**
     * 老人侧拒绝：<b>{0,1} → 3</b>，写 {@code reject_reason}。
     *
     * @param bindingId 绑定关系 id
     * @param reason 拒绝原因（可选）
     * @param operator 登录态操作者（老人本人）
     * @return 绑定视图
     */
    @Transactional(rollbackFor = Exception.class)
    public FamilyBindingView reject(Long bindingId, String reason, LoginUser operator) {
        ElderFamilyBind bind = requireBinding(bindingId);
        requirePending(bind);
        ElderFamilyBind update = new ElderFamilyBind();
        update.setId(bind.getId());
        update.setStatus(STATUS_REJECTED);
        update.setRejectReason(reason);
        bindMapper.updateById(update);
        bind.setStatus(STATUS_REJECTED);
        bind.setRejectReason(reason);
        return toView(bind, null);
    }

    /**
     * 解绑：<b>2 → 4</b>；{@code deleted} 保持 0（保留历史，不物理删）。
     *
     * <p><b>服务层双保险</b>：先 {@code selectById}（第三闸 → 越权/越界查不到 → 10004），再置 {@code status=4}。
     *
     * @param bindingId 绑定关系 id
     * @param operator 登录态操作者
     */
    @Transactional(rollbackFor = Exception.class)
    public void unbind(Long bindingId, LoginUser operator) {
        ElderFamilyBind bind = requireBinding(bindingId);
        if (!Integer.valueOf(STATUS_ACTIVE).equals(bind.getStatus())) {
            throw new BizException(ErrorCode.NOT_FOUND, "仅生效中的绑定可解绑");
        }
        ElderFamilyBind update = new ElderFamilyBind();
        update.setId(bind.getId());
        update.setStatus(STATUS_UNBOUND);
        bindMapper.updateById(update);
    }

    /**
     * 我的绑定列表（分页；可见范围由第三闸按登录态 dataScope 收口）。
     *
     * <p>读路径：仅对 <b>status=2（生效）</b> 的绑定填充脱敏 {@code elderName}（未生效阶段不下发，防枚举）；经 {@code elder}
     * 读取（第三闸：家属仅能读"已生效绑定"的老人，故未生效行自然取不到名）。
     *
     * @param query 分页入参
     * @return 分页视图
     */
    public PageResult<FamilyBindingView> list(FamilyBindingQuery query) {
        FamilyBindingQuery q = query == null ? new FamilyBindingQuery() : query;
        int pageNum =
                q.getPageNum() == null || q.getPageNum() < 1 ? DEFAULT_PAGE_NUM : q.getPageNum();
        int pageSize =
                q.getPageSize() == null || q.getPageSize() < 1
                        ? DEFAULT_PAGE_SIZE
                        : q.getPageSize();
        QueryWrapper<ElderFamilyBind> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted", DELETED_NO).orderByDesc("created_at");
        Page<ElderFamilyBind> page = bindMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        Map<Long, String> names = loadMaskedElderNames(page.getRecords());
        List<FamilyBindingView> list =
                page.getRecords().stream()
                        .map(bind -> toView(bind, names.get(bind.getElderId())))
                        .toList();
        return PageResult.of(list, page.getTotal(), pageNum, pageSize);
    }

    /** 取绑定行（第三闸收口：越权/越界 → 查不到 → 10004；同码不泄露存在性）。 */
    private ElderFamilyBind requireBinding(Long bindingId) {
        if (bindingId == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        QueryWrapper<ElderFamilyBind> wrapper = new QueryWrapper<>();
        wrapper.eq("id", bindingId).eq("deleted", DELETED_NO);
        ElderFamilyBind bind = bindMapper.selectOne(wrapper);
        if (bind == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return bind;
    }

    /** 仅 {0,1}（待老人确认 / 待机构审核）可被审核/拒绝，否则同码 10004。 */
    private void requirePending(ElderFamilyBind bind) {
        Integer status = bind.getStatus();
        if (status == null || (status != STATUS_PENDING_ELDER && status != STATUS_PENDING_ORG)) {
            throw new BizException(ErrorCode.NOT_FOUND, "绑定状态不允许该操作");
        }
    }

    /**
     * 取同对（{@code elderId} + {@code familyUserId}）的 {@code deleted=0} <b>活性行</b>（无则 {@code null}）。
     *
     * <p>唯一键 {@code uk_elder_family(elder_id, family_user_id, active_uk)} 在 {@code deleted=0} 时
     * {@code active_uk=1} ⇒ 同一对至多一条活性行，故 {@code selectOne} 安全。查询经 Mapper 发起，落在第三闸内（scope=5 经 {@code
     * family_user_id=me} 收口；scope=2/1 经 {@code elder} 反查锚）。
     */
    private ElderFamilyBind findActiveBind(Long elderId, long familyUserId) {
        QueryWrapper<ElderFamilyBind> wrapper = new QueryWrapper<>();
        wrapper.eq("elder_id", elderId)
                .eq("family_user_id", familyUserId)
                .eq("deleted", DELETED_NO);
        return bindMapper.selectOne(wrapper);
    }

    /** 批量取"生效中绑定"对应的老人脱敏名（空集 → 空 Map；不可读者自然缺席）。 */
    private Map<Long, String> loadMaskedElderNames(List<ElderFamilyBind> binds) {
        List<Long> activeElderIds =
                binds.stream()
                        .filter(bind -> Integer.valueOf(STATUS_ACTIVE).equals(bind.getStatus()))
                        .map(ElderFamilyBind::getElderId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        if (activeElderIds.isEmpty()) {
            return Map.of();
        }
        QueryWrapper<Elder> wrapper = new QueryWrapper<>();
        wrapper.in("id", activeElderIds).eq("deleted", DELETED_NO);
        List<Elder> elders = elderMapper.selectList(wrapper);
        Map<Long, String> names = new HashMap<>();
        for (Elder elder : elders) {
            names.put(elder.getId(), support.maskName(support.decrypt(elder.getNameEnc())));
        }
        return names;
    }

    private FamilyBindingView toView(ElderFamilyBind bind, String elderName) {
        FamilyBindingView view = new FamilyBindingView();
        view.setId(bind.getId());
        view.setElderId(bind.getElderId());
        view.setElderName(elderName);
        view.setRelation(bind.getRelation());
        view.setStatus(bind.getStatus());
        view.setAuthStartAt(bind.getAuthStartAt());
        view.setAuthEndAt(bind.getAuthEndAt());
        view.setRejectReason(bind.getRejectReason());
        return view;
    }
}
