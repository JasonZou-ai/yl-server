package cn.yl.modules.evaluation.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 照护建议模板（care_template，按能力等级匹配）。
 *
 * <p>PRD P02：报告页照护建议「按等级匹配的模板建议；无匹配模板显示通用建议」。模板库由 DDL 播种， {@code gradeCode}
 * 与评估等级码（GRADE_*）一致，{@code sortNo} 决定建议展示顺序。
 */
@Data
@TableName("care_template")
public class CareTemplate {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 匹配等级：GRADE_INTACT / GRADE_MILD / GRADE_MODERATE / GRADE_SEVERE / GRADE_TOTAL。 */
    private String gradeCode;

    /** 适用场景（可空，预留按场景细分）。 */
    private String scene;

    private String title;
    private String content;
    private Integer sortNo;

    /** 1-启用 0-停用。 */
    private Integer status;

    private Integer deleted;
}
