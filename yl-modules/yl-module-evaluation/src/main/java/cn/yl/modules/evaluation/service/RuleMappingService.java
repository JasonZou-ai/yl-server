package cn.yl.modules.evaluation.service;

import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.evaluation.domain.entity.GbRuleMapping;
import cn.yl.modules.evaluation.dto.RuleMappingView;
import cn.yl.modules.evaluation.mapper.GbRuleMappingMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * B7 国标条款→规则条目映射查询服务（{@code GET /api/v1/rule/mapping}）。
 *
 * <p>{@code gb_rule_mapping} 是<b>字典/规则类表</b>，无归属列 → <b>不参与</b>第三闸数据域过滤（有意对所有持 {@code rule:view}
 * 的账号可读，用于条款可追溯性核查）。
 */
@Service
@RequiredArgsConstructor
public class RuleMappingService {

    private final GbRuleMappingMapper gbRuleMappingMapper;

    /**
     * 按规则版本查映射矩阵。
     *
     * @param ruleVersionId 国标规则版本 id（{@code gb_rule_version.id}）
     * @return 映射行集合（无数据时返回空列表）
     */
    public List<RuleMappingView> mapping(Long ruleVersionId) {
        if (ruleVersionId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "ruleVersionId 不能为空");
        }
        List<GbRuleMapping> rows =
                gbRuleMappingMapper.selectList(
                        new QueryWrapper<GbRuleMapping>()
                                .eq("rule_version_id", ruleVersionId)
                                .orderByAsc("clause_no"));
        return rows.stream().map(this::toView).toList();
    }

    private RuleMappingView toView(GbRuleMapping row) {
        RuleMappingView view = new RuleMappingView();
        view.setClauseNo(row.getClauseNo());
        view.setClauseTitle(row.getClauseTitle());
        view.setItemCode(row.getItemCode());
        view.setRuleCode(row.getRuleCode());
        view.setRemark(row.getRemark());
        return view;
    }
}
