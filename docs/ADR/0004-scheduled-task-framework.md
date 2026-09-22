# ADR-0004：定时任务框架选型（留存到期清理）

- 状态：**已接受**（2026-09-22）
- 决策者：研发侧（产品/CCB 知会）
- 相关：ER-14（`sys_user_third_party.retain_until`）、PM 裁决 2026-09-21（注销后保留 30 天）

## 背景

ER-14 落地后，三方绑定行需要在 `retain_until` 到期后**物理删除**，否则构成超期留存的合规缺口。全仓此前**零定时任务**，需要为第一个任务选定调度方式。

## 决策

**采用 Spring `@Scheduled`**，不使用 Quartz，暂不使用 XXL-Job。

## 备选与淘汰理由

| 方案 | 评估 |
|---|---|
| **Spring `@Scheduled`**（选） | 零新依赖、本迭代即可闭合缺口；cron 可由配置注入；缺点是缺可视化运维与失败重试编排 |
| Quartz | 需新增 11 张调度表 + 持久化配置。本任务「一天一次、幂等、无编排、无依赖链」，引入该重量级设施收益为负 |
| XXL-Job | `xxl-job-core` 已在依赖中，但需独立部署 admin 服务端、注册执行器、建调度任务；当前 `xxl.job.enabled=false`。M3 前引入会把「合规缺口」和「运维建设」绑到同一条关键路径上 |

## 由选型派生的三条硬约束

1. **不加分布式锁**。清理动作按 `retain_until < now` 删除，**幂等**：多实例并发最坏是重复空跑，不会误删或数据错乱。加锁会引入锁失效/死锁等新风险，收益为负。留痕以「实际删除行数 > 0」为门槛，因此只有真正删到数据的实例写审计，不会每实例一条。
2. **限批执行**。单批 `batch-size`（默认 500）+ 最大轮次 `max-rounds`（默认 20），避免大表长事务与主从延迟。
3. **失败可重试**。异常只记 error 日志、不向调度器抛出；因幂等，下周期自然重试安全。

## 迁移边界（避免将来返工）

业务动作全部放在 `ThirdPartyRetentionCleanupTask` 的普通方法中，`@Scheduled` 方法只是**触发壳**。迁到 XXL-Job 时只需把注解替换为 `@XxlJob("...")`，方法体不动。

## 何时重新评估

出现下列任一情况即启动向 XXL-Job 的迁移评估：

- 定时任务数量 ≥ 3，或出现**任务编排/分片**需求；
- 需要在不停机前提下**动态调整 cron** 或**手动补跑**成为常态；
- 需要跨实例**失败重试与告警编排**，或运维要求统一调度视图。

## 落地位置

- 任务：`yl-modules/yl-module-account/.../service/retention/ThirdPartyRetentionCleanupTask.java`
- 端口：`domain/thirdparty/ExpiredBindingPurger.java`（任务类不感知存储实现）
- 实现：`mapper/SysUserThirdPartyMapper.java`（手写 SQL 物理删除，绕开全局逻辑删除改写）
- 开关：`config/SchedulingConfig.java`（`@EnableScheduling`）+ `yl.retention.third-party.*`
