package cn.yl.modules.evaluation.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 国标规则版本（gb_rule_version）。
 *
 * <p>01_init.sql 建表、02_schema.sql 播种单行（verify-schema 断言 COUNT=1）。{@code version} 采用权威规则集口径 {@code
 * 1.0.0}，与 {@code rules/gbt42195-ruleset.json} 的 ruleVersion 一致，评估单据此绑定判定所用版本以支持事后复算。
 *
 * <p>主键为数据库自增（ADR-0003 ER-09：全库仅本表允许自增列）。
 */
@Data
@TableName("gb_rule_version")
public class GbRuleVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String standardCode;
    private String version;

    /** 0-草稿 1-生效 2-已下线。 */
    private Integer status;

    private LocalDateTime effectiveAt;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
