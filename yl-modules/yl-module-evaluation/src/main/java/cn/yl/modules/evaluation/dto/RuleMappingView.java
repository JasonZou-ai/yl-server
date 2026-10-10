package cn.yl.modules.evaluation.dto;

import lombok.Data;

/** B7 国标条款→规则条目映射行。 */
@Data
public class RuleMappingView {

    private String clauseNo;
    private String clauseTitle;
    private String itemCode;
    private String ruleCode;
    private String remark;
}
