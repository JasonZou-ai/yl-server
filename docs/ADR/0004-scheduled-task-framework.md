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

业务动作全部放在任务类的普通方法中，`@Scheduled` 方法只是**触发壳**。迁到 XXL-Job 时只需把注解替换为 `@XxlJob("...")`，方法体不动。
（2026-09-23 补充：触发壳已由任务类上移至 `yl-bootstrap` 的链路编排，见下节；两条业务方法仍不带注解。）

## 何时重新评估

出现下列任一情况即启动向 XXL-Job 的迁移评估：

- 定时任务数量 ≥ 3，或出现**任务编排/分片**需求；
- 需要在不停机前提下**动态调整 cron** 或**手动补跑**成为常态；
- 需要跨实例**失败重试与告警编排**，或运维要求统一调度视图。

## 落地位置

- 任务：`yl-modules/yl-module-account/.../service/retention/ThirdPartyRetentionCleanupTask.java`
- 端口：`domain/retention/ExpiredBindingPurger.java`（任务类不感知存储实现；D-5 裁决 D5-D4 由 `domain/thirdparty/` 形态归一迁入）
- 实现：`mapper/SysUserThirdPartyMapper.java`（手写 SQL 物理删除，绕开全局逻辑删除改写）
- 开关：`config/SchedulingConfig.java`（`@EnableScheduling`）+ `yl.retention.third-party.*`

---

## 补充（2026-09-23）：`ARCHIVED` 超期归档并入本调度链路

### 变更

`ARCHIVED`（评估单超保留期归档）此前只是枚举注释里的状态、无任何实现。按 P1-4 裁决并入本链路，**一次调度做两件事**：

1. ER-14 三方绑定到期 **物理删除**（合规：超期不得留存）；
2. ER-03 评估单超保留期 **状态流转为 `ARCHIVED`**（5 年留存对象，只脱业务视图、**不销毁**）。

### 触发壳上移到 `yl-bootstrap`（对原决策的修正）

原设计把 `@Scheduled` 放在任务类上。新增归档任务后发现：两个动作分属 `yl-module-account` 与 `yl-module-evaluation`，二者是
**平级兄弟模块**（都只依赖 `yl-api`，互不依赖），**任一模块都无法引用对方的任务类**，无法在模块内编排。

故触发壳上移到唯一同时依赖两者的 `yl-bootstrap`：

- 壳：`yl-bootstrap/src/main/java/cn/yl/bootstrap/schedule/RetentionChainScheduler.java`（**全仓唯一 `@Scheduled`**）
- 业务方法保持原位且**不带注解**：`ThirdPartyRetentionCleanupTask.purgeExpiredBindings()`、`EvaluationArchiveTask.archiveExpiredOrders()`

「迁移边界」不变：迁 XXL-Job 时只替换链路壳上的注解为 `@XxlJob("retentionChain")`，两个方法体都不动。
为防止将来"顺手"给任务类加回注解导致双份调度，`RetentionChainSchedulerTest` 用反射断言「链路带 `@Scheduled`、两个任务方法都不带」。

### 由新增动作派生的额外约束

4. **链路顺序与隔离**：串行执行，两个任务各自吞掉自身异常并返回 0，故**前一环失败不影响后一环**；
5. **链路级留痕按「有事发生」为门槛**：只有两个计数不同时为 0 才写一条 `audit_log`，避免每天一条无意义噪声。

### 归档的写入口（否则功能静默失效）

归档条件含 `retain_until IS NOT NULL`，而此前**全仓没有任何代码写 `retain_until`**——不补写入口，归档任务会
"上线了但一单都不会归档"（无异常、无日志）。故在发布时点同时写两侧保留期（ER-03：5 年）：

- `AssessmentReviewService.publish` → `eval_order.retain_until = publishedAt + 5y`
- `AssessmentReportService.markPublished` → `eval_report.retain_until = publishedAt + 5y`

口径常量化于 `domain/RetentionPolicy.RECORD_RETENTION_YEARS`（写读两侧必须同源，否则出现"永不到期"或"过早归档"）。

### 不需要 DDL 变更

`eval_order` 与 `eval_report` **已有** `retain_until` 列与 `idx_retain` 索引（ER-03 落地时即建），归档动作不需要新增列或索引。
`eval_report.status`（tinyint，0/1/2）**不承载** `ARCHIVED`——报告随评估单封存；报告侧的独立归档态登记为后续项（若确需，走
`*-Patch-Proposal.sql` → 门禁断言同步流程）。

### 任务数复核

本链路为 **1 个触发壳 / 2 个动作**，未达「定时任务数量 ≥ 3」的迁移阈值，**仍不迁 XXL-Job**。

---

## 补充（2026-10-09）：D-5 保留期链路收官 —— 任务数 **2 → 4** 的显式复评

### 变更

D-5 把保留期链路从「一次调度做两件事」扩为「一次调度做四件事」，新增两个到期动作（D-5 裁决 D5-D1 / D5-D3）：

1. ER-14 三方绑定到期 **物理删除**（30 天，既有）；
2. ER-03 评估单超保留期 **转 `ARCHIVED`** 只读封存（既有）；
3. **ER-03 监管上报记录超保留期写 `archived_at`** 只读封存（**新**，`SuperviseReportArchiveTask`）；
4. **D4-R1 亲情绑定授权到期 `status 2 → 4`** 状态流转（**新**，`FamilyBindingExpireTask`）。

触发壳 `RetentionChainScheduler` 构造器注入四个任务、串行执行四环；链路日志与审计 detail 均含**四个计数**（`purged` / `archived` / `archivedReports` / `expiredBindings`）。

### 触发壳仍唯一（对原决策的延续）

四环依旧由**全仓唯一 `@Scheduled`**（`RetentionChainScheduler`，`yl-bootstrap`）统一调度；四个任务类（`ThirdPartyRetentionCleanupTask` / `EvaluationArchiveTask` / `SuperviseReportArchiveTask` / `FamilyBindingExpireTask`）的业务方法**均不带 `@Scheduled`**，由 `RetentionChainSchedulerTest` 反射断言守卫（防"顺手加回注解"导致双份调度）。

### 形态归一（D-5 裁决 D5-D4）

ER-14 保留期端口由 `domain/thirdparty/ExpiredBindingPurger` 迁至 `domain/retention/`，与评估单归档端口（`domain/archive/`）及新增两个端口（`domain/retention/`）同址；**纯移动 + 改 import，零行为变更**。

### ★ 任务数复核（D-5 裁决 D5-D2：按动作数诚实计数）

按「**动作数**」诚实计数：D-5 落地后动作数 = **4**，**已过** ADR-0004 迁移阈值「定时任务数量 ≥ 3」，故本卡内**显式做一次 XXL-Job 迁移复评**（不得绕过——该阈值本就是为了在复杂度上升时被触发）。

**复评结论：仍不迁 XXL-Job。** 理由：

- 四个动作仍满足「**一天一次、幂等、无编排依赖链、无分片**」的画像——它们是**同一保留期链路的四次串行动作**，由同一壳、同一 cron、同一次留痕驱动，不构成独立的调度编排需求；
- 迁移 XXL-Job 的收益（可视化运维 / 动态 cron / 失败重试编排 / 分片）在当前规模仍为**负**：需独立部署 admin 服务端、注册执行器、建调度任务，且当前 `xxl.job.enabled=false`（把「合规闭环」与「运维建设」解耦，与 P1-4 决策一致）；
- **迁移边界不变**：迁 XXL-Job 时只需把链路壳上的注解替换为 `@XxlJob("retentionChain")`，四个业务方法体一字不动。

**复核点保持有效**：后续若出现**跨实例失败重试编排 / 分片 / 动态 cron 常态 / 需要统一调度视图**，仍按本节触发迁移评估；`P2-7`（孤儿文件回收）落地后再增 1 个动作（→ 5），届时按同一「动作数」口径复核。

---

## 补充（2026-10-09）：P2-7 孤儿文件回收 —— 任务数 **4 → 5** 的第 2 次显式复评

### 变更

P2-7 把保留期链路从「一次调度做四件事」扩为「一次调度做五件事」，新增第 5 个动作：

5. **P2-7 孤儿文件回收**（**新**，`OrphanFileSweepTask`）：把存储根（`report/`、`export/`）内**已无 DB 引用**的普通文件移入隔离目录 `<baseDir>/.trash/<yyyyMMdd>/`（**不 `rm`、可还原**）。为让到期导出文件真正可回收，动作**先行**把 `export_task`（`expire_at < now AND status ≠ 4`）置 `status = 4`（用户裁决 **D1 = B**），随后其键退出在用集合。

触发壳 `RetentionChainScheduler` 构造器注入五个任务、串行执行五环；链路日志与审计 detail 均含**五个计数**（`purged` / `archived` / `archivedReports` / `expiredBindings` / `swept`），门槛改为「五者不同时为 0 才写审计」。

### 触发壳仍唯一（对原决策的延续）

五环依旧由**全仓唯一 `@Scheduled`**（`RetentionChainScheduler`，`yl-bootstrap`）统一调度；五个任务类（`ThirdPartyRetentionCleanupTask` / `EvaluationArchiveTask` / `SuperviseReportArchiveTask` / `FamilyBindingExpireTask` / `OrphanFileSweepTask`）的业务方法**均不带 `@Scheduled`**，由 `RetentionChainSchedulerTest` 反射断言守卫（防"顺手加回注解"导致双份调度）。

### ★ 任务数复核（沿用 D-5 裁决 D5-D2 的「按动作数诚实计数」）

按「**动作数**」诚实计数：P2-7 落地后动作数 = **5**，**再次越过** ADR-0004 迁移阈值「定时任务数量 ≥ 3」，故本卡内**显式做第 2 次 XXL-Job 迁移复评**（不得绕过——该阈值本就是为了在复杂度上升时被触发）。

**复评结论：仍不迁 XXL-Job。** 理由：

- ① 仍只有 **1 处** `@Scheduled`（壳）；5 个动作均为**幂等 + 限批 + 失败不抛**的朴素批处理，无依赖编排、无重试编排、无分片需求；
- ② 第 5 个动作**不引入分布式协调**：孤儿回收「命中即离开命中集合」（命中即移出根）⇒ 天然幂等，多实例并发最坏是重复空跑；到期标记 `export_task.status → 4` 同样「命中即退出集合」；
- ③ 迁移 XXL-Job 的收益（可视化运维 / 动态 cron / 失败重试编排 / 分片）在当前规模仍为**负**：需独立部署 admin 服务端、注册执行器、建调度任务，且当前 `xxl.job.enabled=false`（与 P1-4 / D-5 决策一致，把「合规闭环」与「运维建设」解耦）；
- ④ **迁移边界不变**：迁 XXL-Job 时只需把链路壳上的注解替换为 `@XxlJob("retentionChain")`，五个业务方法体一字不动。

**复核点保持有效**：后续若出现**跨实例失败重试编排 / 分片 / 动态 cron 常态 / 需要统一调度视图**，仍按本节触发迁移评估。

