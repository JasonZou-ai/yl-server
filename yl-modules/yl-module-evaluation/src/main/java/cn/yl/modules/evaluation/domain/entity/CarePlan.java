package cn.yl.modules.evaluation.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 照护方案（care_plan，一个报告一份）。
 *
 * <p>PRD P02 口径：评估员可建议、管理员确认——故 {@code suggestBy} 与 {@code confirmedBy} <b>必须不同人</b>， {@code
 * status} 0-待确认 / 1-已确认。
 *
 * <p>{@code itemsJson} 为方案条目 JSON（模板条目 + 个性化条目），便于审计复盘「当时给了什么建议」。
 *
 * <p>{@code active_uk} 为 {@code GENERATED ALWAYS AS ... STORED} 生成列，由数据库维护，实体侧<b>不映射</b>。
 */
@Data
@TableName("care_plan")
public class CarePlan {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long reportId;
    private Long elderId;

    /** 方案生成时所依据的能力等级码。 */
    private String gradeCode;

    /** 照护条目 JSON（CarePlanItem 列表）。 */
    private String itemsJson;

    /** 建议人（评估员）。 */
    private Long suggestBy;

    /** 确认人（机构管理员，必须 ≠ 建议人）。 */
    private Long confirmedBy;

    private LocalDateTime confirmedAt;

    /** 0-待确认 1-已确认。 */
    private Integer status;

    private Integer deleted;
    private LocalDateTime createdAt;
}
