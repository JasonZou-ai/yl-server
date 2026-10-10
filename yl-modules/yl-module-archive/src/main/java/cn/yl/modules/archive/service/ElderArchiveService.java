package cn.yl.modules.archive.service;

import cn.yl.api.security.LoginUser;
import cn.yl.common.api.PageResult;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.archive.domain.entity.Elder;
import cn.yl.modules.archive.dto.ElderCreateRequest;
import cn.yl.modules.archive.dto.ElderQuery;
import cn.yl.modules.archive.dto.ElderView;
import cn.yl.modules.archive.mapper.ElderMapper;
import cn.yl.modules.archive.support.SensitiveFieldSupport;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 档案域服务（D-2）：老人档案<b>建档</b>（加密 + 摘要 + 脱敏）、<b>列表</b>、<b>详情</b>。
 *
 * <p><b>建档链路</b>：明文入参 → 经 {@link SensitiveFieldSupport} 加密（{@code *_enc}）、 计算等值检索摘要（{@code
 * *_hash}）、生成身份证脱敏串（{@code id_card_mask}）→ 落库 → 返回<b>脱敏</b>视图（{@link ElderView}）。<b>禁止明文落库</b>（PRD
 * §9）。
 *
 * <p><b>机构与数据域</b>：写操作（建档）的机构合法性由控制器/服务显式校验（归属机构须落在 {@code LoginUser.orgIds()}
 * 内）；读操作（列表/详情）的机构/关系收口由第三闸 {@code DataScopePermissionHandler} <b>自动</b>完成，本服务<b>不</b>再手写 {@code
 * org_id} 过滤， 以避免双写不一致。
 *
 * <p><b>查询口径</b>：软删除列 {@code deleted} 非生成列，且第三闸不改写该条件，故列表/详情 <b>必须显式</b> {@code deleted = 0}。
 */
@Service
@RequiredArgsConstructor
public class ElderArchiveService {

    /** 在册状态。 */
    private static final int STATUS_ACTIVE = 1;

    private static final int DELETED_NO = 0;

    /** 档案号唯一键冲突时的最大重试次数（含首次）。 */
    private static final int ELDER_NO_MAX_ATTEMPTS = 3;

    private static final int DEFAULT_PAGE_NUM = 1;

    private static final int DEFAULT_PAGE_SIZE = 10;

    private final ElderMapper mapper;

    private final SensitiveFieldSupport support;

    private final ElderNoGenerator noGenerator;

    /**
     * 建档：加密敏感字段 + 计算摘要 + 身份证脱敏 + 落库，返回脱敏视图。
     *
     * <p>身份证号在 {@code deleted = 0} 范围内<b>去重</b>：命中已有 {@code id_card_hash} 即抛 {@link
     * ErrorCode#CONFLICT}（10005），防止同一老人重复建档。
     *
     * <p>敏感字段编解码器未装配（dev 无密钥）时，{@link SensitiveFieldSupport#encrypt} 抛 {@link
     * ErrorCode#ARCHIVE_SENSITIVE_ENCRYPT_FAILED}（40001）——<b>拒绝明文落库</b>。
     *
     * @param req 建档请求（{@code orgId} 已由调用方解析为合法机构）
     * @param operator 登录态操作者（建档人一律取登录态，请求体不承载身份）
     * @return 脱敏后的档案视图
     */
    @Transactional(rollbackFor = Exception.class)
    public ElderView create(ElderCreateRequest req, LoginUser operator) {
        if (req.getOrgId() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "归属机构不能为空（应取登录态默认机构）");
        }
        String idCardHash = support.hashForSearch(req.getIdCardNo());
        if (idCardHash != null && existsByIdCardHash(idCardHash)) {
            throw new BizException(ErrorCode.CONFLICT, "该身份证号已存在档案（elder_no 冲突请改用详情接口核验）");
        }
        Elder elder = buildElder(req, operator);
        insertWithUniqueElderNo(elder);
        return toView(elder);
    }

    /**
     * 分页列表（第三闸自动按登录态 dataScope 行级收口）。
     *
     * <p>关键词 {@code keyword}：姓名走 {@code name_hash} <b>精确</b>匹配（GCM 密文每次 IV 随机、不可 模糊查），档案号 {@code
     * elder_no} 走<b>前缀</b>匹配。未装配编解码器（dev 无密钥）时无法计算 {@code name_hash}，此时关键词仅按档案号前缀匹配。
     *
     * @param query 筛选条件（分页/关键词/状态/机构）
     * @return 脱敏分页结果
     */
    public PageResult<ElderView> list(ElderQuery query) {
        ElderQuery q = query == null ? new ElderQuery() : query;
        int pageNum =
                q.getPageNum() == null || q.getPageNum() < 1 ? DEFAULT_PAGE_NUM : q.getPageNum();
        int pageSize =
                q.getPageSize() == null || q.getPageSize() < 1
                        ? DEFAULT_PAGE_SIZE
                        : q.getPageSize();

        QueryWrapper<Elder> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted", DELETED_NO);
        if (q.getStatus() != null) {
            wrapper.eq("status", q.getStatus());
        }
        if (q.getOrgId() != null) {
            wrapper.eq("org_id", q.getOrgId());
        }
        if (q.getKeyword() != null && !q.getKeyword().isBlank()) {
            String keyword = q.getKeyword().trim();
            wrapper.and(
                    w -> {
                        w.likeRight("elder_no", keyword);
                        if (support.isAvailable()) {
                            w.or().eq("name_hash", support.hashForSearch(keyword));
                        }
                    });
        }

        Page<Elder> page = mapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        List<ElderView> list = page.getRecords().stream().map(this::toView).toList();
        return PageResult.of(list, page.getTotal(), pageNum, pageSize);
    }

    /**
     * 档案详情（脱敏）。
     *
     * <p>越界与不存在<b>同码</b>（{@link ErrorCode#NOT_FOUND} = 10004，不泄露存在性）：跨机构/跨绑定
     * 的行被第三闸过滤后查不到，与真正不存在的行返回一致。
     *
     * @param elderId 档案 id
     * @return 脱敏后的档案视图
     */
    public ElderView detail(Long elderId) {
        if (elderId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "elderId 不能为空");
        }
        QueryWrapper<Elder> wrapper = new QueryWrapper<>();
        wrapper.eq("id", elderId).eq("deleted", DELETED_NO);
        Elder elder = mapper.selectOne(wrapper);
        if (elder == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return toView(elder);
    }

    /** 身份证摘要是否已存在（仅 {@code deleted = 0} 范围内去重）。 */
    private boolean existsByIdCardHash(String idCardHash) {
        QueryWrapper<Elder> wrapper = new QueryWrapper<>();
        wrapper.eq("id_card_hash", idCardHash).eq("deleted", DELETED_NO);
        return mapper.selectCount(wrapper) > 0;
    }

    /** 组装实体（敏感字段加密 + 摘要 + 脱敏；身份取登录态）。 */
    private Elder buildElder(ElderCreateRequest req, LoginUser operator) {
        Elder elder = new Elder();
        elder.setElderNo(noGenerator.next());
        elder.setNameEnc(support.encrypt(req.getName()));
        elder.setNameHash(support.hashForSearch(req.getName()));
        elder.setIdCardEnc(support.encrypt(req.getIdCardNo()));
        elder.setIdCardHash(support.hashForSearch(req.getIdCardNo()));
        elder.setIdCardMask(
                req.getIdCardNo() == null ? null : support.maskIdCard(req.getIdCardNo()));
        elder.setPhoneEnc(support.encrypt(req.getPhone()));
        elder.setPhoneHash(support.hashForSearch(req.getPhone()));
        elder.setAddressEnc(support.encrypt(req.getAddress()));
        elder.setGender(req.getGender());
        elder.setBirthDate(req.getBirthDate());
        elder.setAge(ageOf(req.getBirthDate()));
        elder.setOrgId(req.getOrgId());
        elder.setStatus(STATUS_ACTIVE);
        elder.setDeleted(DELETED_NO);
        elder.setCreatedBy(operator.userId());
        return elder;
    }

    /**
     * 插入并在档案号唯一键冲突时重试。
     *
     * <p>{@code uk_elder_no} 冲突由随机后缀重复引起；{@code uk_elder_user} 在 {@code user_id} 为 {@code NULL}
     * 时（D-2 不写绑定）不会冲突。重试至上限仍冲突则抛 {@link ErrorCode#CONFLICT}（10005），由上层提示重试。
     */
    private void insertWithUniqueElderNo(Elder elder) {
        for (int attempt = 1; attempt <= ELDER_NO_MAX_ATTEMPTS; attempt++) {
            try {
                mapper.insert(elder);
                return;
            } catch (DuplicateKeyException ex) {
                if (attempt == ELDER_NO_MAX_ATTEMPTS) {
                    throw new BizException(ErrorCode.CONFLICT, "档案号生成冲突，请重试");
                }
                elder.setElderNo(noGenerator.next());
            }
        }
    }

    /** 年龄（由出生日期推算；出生日期为空则返回 null）。 */
    private Integer ageOf(LocalDate birthDate) {
        if (birthDate == null) {
            return null;
        }
        return Period.between(birthDate, LocalDate.now()).getYears();
    }

    /** 实体 → 脱敏视图（姓名/手机解密后脱敏；住址不出现在默认视图）。 */
    private ElderView toView(Elder elder) {
        ElderView view = new ElderView();
        view.setId(elder.getId());
        view.setElderNo(elder.getElderNo());
        view.setName(support.maskName(support.decrypt(elder.getNameEnc())));
        view.setIdCardMask(elder.getIdCardMask());
        view.setPhone(support.maskPhone(support.decrypt(elder.getPhoneEnc())));
        view.setGender(elder.getGender());
        view.setAge(elder.getAge());
        view.setOrgId(elder.getOrgId());
        view.setStatus(elder.getStatus());
        return view;
    }
}
