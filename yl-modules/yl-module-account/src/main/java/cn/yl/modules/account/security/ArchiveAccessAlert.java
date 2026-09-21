package cn.yl.modules.account.security;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 档案接口异常访问告警载荷（CR-M2-001 §2.5）。
 *
 * <p>不含任何老人个人信息：仅承载「谁、以何种角色、在多久内访问了多少次」的<b>行为指标</b>， 供监管角色核实是否存在批量拉档。具体档案对象与 IP 已由 {@code
 * audit_log} 独立留痕，此处不重复携带。
 *
 * @param userId 触发账号
 * @param roles 触发账号角色码快照（ELDER/FAMILY/SUPERVISOR 之一）
 * @param count 滑动窗口内累计调用次数（含触发本次）
 * @param threshold 配置阈值 N
 * @param windowMinutes 滑动窗口长度（分钟）
 * @param occurredAt 触发时刻
 */
public record ArchiveAccessAlert(
        long userId,
        List<String> roles,
        long count,
        int threshold,
        long windowMinutes,
        LocalDateTime occurredAt) {

    /** 紧凑构造器：角色集合快照为不可变视图。 */
    public ArchiveAccessAlert {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
