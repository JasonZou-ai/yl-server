package cn.yl.modules.account.service;

import cn.yl.common.api.PageResult;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.account.domain.entity.Org;
import cn.yl.modules.account.dto.OrgQuery;
import cn.yl.modules.account.dto.OrgView;
import cn.yl.modules.account.mapper.OrgMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * R6 机构查询服务。
 *
 * <p><b>数据域口径</b>：{@code org} 已登记 {@code ORG_COLUMN(org → id)} → scope=2 时第三闸自动追加 {@code org.id IN
 * (orgIds)}；scope=4（监管）不限。故本服务<b>不手写</b>机构过滤。
 */
@Service
@RequiredArgsConstructor
public class OrgQueryService {

    private static final int DEFAULT_PAGE_NUM = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int DELETED_NO = 0;

    private final OrgMapper orgMapper;

    /**
     * 分页查询机构。
     *
     * @param query 查询条件（可空）
     * @return 分页结果
     */
    public PageResult<OrgView> list(OrgQuery query) {
        OrgQuery q = query == null ? new OrgQuery() : query;
        int pageNum =
                q.getPageNum() == null || q.getPageNum() < 1 ? DEFAULT_PAGE_NUM : q.getPageNum();
        int pageSize =
                q.getPageSize() == null || q.getPageSize() < 1
                        ? DEFAULT_PAGE_SIZE
                        : q.getPageSize();

        QueryWrapper<Org> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted", DELETED_NO);
        if (q.getKeyword() != null && !q.getKeyword().isBlank()) {
            String keyword = q.getKeyword().trim();
            wrapper.and(w -> w.likeRight("org_name", keyword).or().likeRight("org_code", keyword));
        }
        wrapper.orderByAsc("id");

        Page<Org> page = orgMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        List<OrgView> list = page.getRecords().stream().map(this::toView).toList();
        return PageResult.of(list, page.getTotal(), pageNum, pageSize);
    }

    /**
     * 机构详情。
     *
     * <p>越界与不存在<b>同码</b>（{@link ErrorCode#NOT_FOUND} = 10004，不泄露存在性）：跨机构行被第三闸过滤后查不到， 与真正不存在的行返回一致。
     *
     * @param orgId 机构 id
     * @return 机构视图
     */
    public OrgView detail(Long orgId) {
        if (orgId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "orgId 不能为空");
        }
        Org org = orgMapper.selectById(orgId);
        if (org == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "机构不存在或无权访问: orgId=" + orgId);
        }
        return toView(org);
    }

    private OrgView toView(Org org) {
        OrgView view = new OrgView();
        view.setId(org.getId());
        view.setOrgCode(org.getOrgCode());
        view.setOrgName(org.getOrgName());
        view.setOrgType(org.getOrgType());
        view.setRegionCode(org.getRegionCode());
        view.setAddress(org.getAddress());
        view.setContactName(org.getContactName());
        view.setStatus(org.getStatus());
        return view;
    }
}
