package cn.yl.modules.evaluation.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 国标条款 → 规则条目映射矩阵（{@code gb_rule_mapping}）。
 *
 * <p><b>字典类表</b>：无 {@code org_id} / 无归属用户列 / 无 {@code deleted} 列（与 {@code eval_item} 同口径）—— 对所有账号
 * 可读，<b>不得</b>登记进第三闸数据域映射（否则 B7 规则映射对所有账号不可读）。
 */
@Data
@TableName("gb_rule_mapping")
public class GbRuleMapping {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long ruleVersionId;
    private String clauseNo;
    private String clauseTitle;
    private String itemCode;
    private String ruleCode;
    private String remark;
}
