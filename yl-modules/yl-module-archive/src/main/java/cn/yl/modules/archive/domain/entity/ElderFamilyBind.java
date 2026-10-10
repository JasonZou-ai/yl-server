package cn.yl.modules.archive.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 亲情绑定（elder_family_bind，D-4）。
 *
 * <p><b>主键</b>：{@link IdType#ASSIGN_ID}（雪花，ADR-0003：业务表禁用 AUTO_INCREMENT）。
 *
 * <p><b>★ 生成列 {@code active_uk} 不得映射</b>：该列为 {@code GENERATED ALWAYS AS (IF(deleted = 0, 1, NULL))
 * STORED}，由数据库自动维护；若实体映射它，insert 会因"向生成列写值"报错（同 {@code care_plan}/{@code export_task} 的
 * 同类坑）。故本实体<b>只映射 {@code deleted}</b>（普通列），且查询须<b>显式</b> {@code deleted = 0}。
 *
 * <p><b>★ 无 {@code org_id} 列</b>：机构归属是 {@code elder.org_id} 的函数依赖，由第三闸经 {@code elder_family_bind →
 * elder} 反查锚收口（{@code DataScopePermissionHandler.ORG_VIA_PARENT}），本实体不持有机构列。
 *
 * <p><b>状态五态</b>（{@code status}）：0-待老人确认 / 1-待机构审核 / 2-生效 / 3-已拒绝 / 4-已解绑或过期。
 *
 * <p>{@code created_at / updated_at} 依赖数据库默认值，实体不填充。
 */
@Data
@TableName("elder_family_bind")
public class ElderFamilyBind {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 被绑定老人（直锚列，第三闸经它反查 elder 收口）。 */
    private Long elderId;

    /** 家属账号（scope=5 本表锚列；本表自身按此列过滤，不可改用老人锚列）。 */
    private Long familyUserId;

    /** 关系：CHILD/SPOUSE/KIN/GUARDIAN。 */
    private String relation;

    /** 核验方式：SCAN/PHONE/FACE（NOT NULL，无默认 → 创建必填）。 */
    private String verifyMethod;

    /** 监护关系证明对象键（只存键，不落盘）。 */
    private String proofCosKey;

    /** 授权起始（机构审核通过时置为 now）。 */
    private LocalDateTime authStartAt;

    /** 授权截止（到期自动解绑，PRD P04；第三闸子查询判据）。 */
    private LocalDateTime authEndAt;

    /** 0-待确认 1-待机构审核 2-生效 3-已拒绝 4-已解绑/过期。 */
    private Integer status;

    /** 拒绝原因。 */
    private String rejectReason;

    /** 软删除标记（0-未删除 1-已删除）；查询须显式过滤。 */
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
