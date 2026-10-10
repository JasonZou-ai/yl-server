# D-5 地基核查报告 · 保留期链路收官（ER-14 适配器形态归一 + D3-R4 `supervise_report` 接线）

> 角色：架构师（**地基核查阶段**，本文件不含业务代码）
> 真源：**实读** `RetentionChainScheduler` / 两个端口与任务 / `02_schema.sql` / `application.yml` / `RetentionChainSchedulerTest` / `verify-schema.sh`（全部标注 `文件:行`，未采信二手记忆）
> 日期：2026-10-09 ｜ 基线：D-4 已收口（单测 255 / schema 83 / 契约 8-8 / 冒烟 116）

---

## 0. 结论先行（TL;DR）

| # | 结论 | 依据 |
|---|---|---|
| 1 | **D-5 的「ER-14 适配器」部分已实际完成形态归一，零残留** —— 端口 / 任务 / 触发壳三者与 ER-03 归档动作**完全同形** | §1 |
| 2 | **D-5 的真实功能缺口只有 1 个**：`supervise_report` 有 `retain_until` 写入口，但**无任何归档/清理动作**（D3-R4） | §2 |
| 3 | 该缺口带出 **2 个必须先裁决的问题**：①「归档」到底是**物理删除**还是**加列封存**（表当前无归档态承载列）；② ADR-0004 的「任务数 ≥ 3」按**动作数**数时，**D-5 自己就会撞线**，与 P2-7 卡面「本卡落地后 = 3」冲突 | §3 |
| 4 | `supervise_report` 当前**无任何对外读端点** ⇒ 归档动作落地后**在 API 层不可观测**，只有 DB 状态差异 | §2.3 |
| 5 | **核查未改变落点**（与 D-2/D-3/D-4 不同），但**必须裁决后才可开工**（裁决清单见 §4） | §4 |

---

## 1. 「形态归一」实读结论 —— ER-14 部分**已完成**

三处并列比对（端口接口 / 任务类 / 触发壳），**ER-14 清理与 ER-03 归档完全同形**：

| 维度 | ER-14（三方绑定物理删除） | ER-03（评估单归档） | 同形？ |
|---|---|---|---|
| **端口接口** | `ExpiredBindingPurger`（`domain/thirdparty/`，2 方法 `purgeExpired` + `countExpired`） | `ExpiredOrderArchiver`（`domain/archive/`，2 方法 `archiveExpired` + `countExpired`） | ✅ |
| **端口实现** | `SysUserThirdPartyMapper` **手写 SQL**（`@Delete` 物理删除，绕开逻辑删除改写） | `EvalOrderMapper` **手写 SQL**（`@Update … SET status='ARCHIVED'`） | ✅ |
| **任务类** | `ThirdPartyRetentionCleanupTask#purgeExpiredBindings()`（`service/retention/`，**无 `@Scheduled`**） | `EvaluationArchiveTask#archiveExpiredOrders()`（`service/retention/`，**无 `@Scheduled`**） | ✅ |
| **限批/轮次/干跑** | `batch-size` / `max-rounds` / `dry-run` 三件套 + 异常吞掉返回 0 | 同 | ✅ |
| **配置键族** | `yl.retention.third-party.*` | `yl.retention.eval-archive.*` | ✅ |
| **触发壳** | 由 `RetentionChainScheduler` 统一调度 | 同 | ✅ |
| **审计留痕** | `AuditService.record("THIRD_PARTY_PURGE", …)`，门槛「实际删除 > 0」 | 并入链路级 `RETENTION_CHAIN` 一条 | ⚠ 粒度不同（有意：见 ADR-0004 补充 §5） |

**证据**
- 壳：`yl-bootstrap/…/schedule/RetentionChainScheduler.java:73`（`@Scheduled(cron = "${yl.retention.chain-cron:0 15 3 * * *}")`）、`:76-77`（串行调两个动作）
- 端口：`domain/thirdparty/ExpiredBindingPurger.java`、`domain/archive/ExpiredOrderArchiver.java`
- 实现：`mapper/SysUserThirdPartyMapper.java:20-41`、`mapper/EvalOrderMapper.java:23-30`
- 任务：`service/retention/ThirdPartyRetentionCleanupTask.java:87`、`service/retention/EvaluationArchiveTask.java:79`

**⇒ 判定**：`D1-openapi-contract-delta.md:273` 所述「本卡实为**适配器形态归一**而非新功能」的**归一动作已随 P1-4b 一并完成**（ADR-0004 补充节 `:60-71` 记录了触发壳上移）。**唯一残余差异是端口包路径**（ER-14 在 `domain/thirdparty/` 与登录适配器混放；ER-03 独占 `domain/archive/`）——**纯风格，零行为影响**。

---

## 2. 真实缺口：D3-R4 `supervise_report` 未接入保留期链路

### 2.1 缺口事实

| 项 | 事实 | 位置 |
|---|---|---|
| 写入口 | **有** —— B10 `POST /supervise/report` 写 `retain_until = now + 5 年` | `SuperviseReportService.java:78 / :92` |
| 归档/清理动作 | **无** —— 全仓 `RetentionChainScheduler` 只调 2 个动作，**不含 `supervise_report`** | `RetentionChainScheduler.java:76-77` |
| 服务自行声明 | javadoc 直书：「**归档/清理归 P2-7 / D-5**」 | `SuperviseReportService.java:32-33` |
| 索引 | `idx_retain (retain_until)` **已建**（扫描支撑就绪，零 DDL 即可查询） | `02_schema.sql:642` |
| 表约束 | `uk_month_org` + `uk_idem` 双唯一键；**无 `deleted`、无归档列** | `02_schema.sql:625-643` |

⇒ 若不接线，`retain_until` 是**只写不读的字段**：任务「上线了但一行都不会归档」（与 ADR-0004 `:80-86` 记录的「归档的写入口」问题是同一类静默失效，只是这次反过来了——**有写入口、无消费方**）。

### 2.2 ★ 语义冲突：`supervise_report` **无法直接复刻** `eval_order` 的归档

| 表 | 状态列 | 类型 | 归档态承载 |
|---|---|---|---|
| `eval_order` | `status` | **`VARCHAR(16)`**，值域 `DRAFT/SUBMITTED/PUBLISHED/REJECTED/VOID/**ARCHIVED**` | ✅ **已有** `ARCHIVED` |
| `supervise_report` | `status` | **`TINYINT`**，值域 `0-待上报 / 1-成功 / 2-失败 / 3-重试中` | ❌ **不承载归档**（值域被"上报状态"占满） |

**证据**：`02_schema.sql:625-643`（supervise_report DDL）、`SuperviseAggregateMapper.java:14-15`（eval_order 值域说明）、`EvalOrderMapper.java:30`（`SET status='ARCHIVED'`）。

⇒ **结论**：要让 `supervise_report` 走「只读封存」的 ARCHIVED 语义，**必须新增列**（`archived_at DATETIME NULL` 或等价），即**需要 DDL 变更** → 走 `*-Patch-Proposal.sql` → 门禁断言同步（schema `83 → 84`）。**这与 P1-4b「归档不需要任何 DDL 变更」的先例不同。**

### 2.3 可观测性缺口

`supervise_report` **当前无任何对外读端点**：`SuperviseReportMapper` 是**空 `BaseMapper`**（`:9`），唯一读取是 `SuperviseReportService#findExisting`（同月同机构**幂等查重**，非业务读）。

⇒ 归档动作落地后，**API 层不可观测**（唯一可验面 = DB 行差异 + 审计日志）。验收断言只能落在「DB `status`/`archived_at` 变化」+「动作返回值」+「日志」。

### 2.4 数据域侧已就绪（零改动）

- `supervise_report → org_id` **已登记** `ORG_COLUMN`（`DataScopePermissionHandler.java:122`）
- `scope=1/5` 已补入 `SELF_DENY_TABLES`（`:186-188`，D-3 修复的「暗区」）
- ⇒ 归档动作的 Mapper 手写 SQL **仍需注意**：`@InterceptorIgnore` **不适用**（归档是系统动作、非用户请求，不经拦截器）；无需额外数据域处理。

---

## 3. ★ 两个必须先裁决的问题

### 3.1 裁决点 D5-D1：「归档」的语义与承载（**决定要不要 DDL**）

| 选项 | 做法 | 代价 | 合规/语义 |
|---|---|---|---|
| **A**（默认建议） | 加 `archived_at DATETIME NULL`（可空，非保留字），到期 `SET archived_at = NOW()`，**只读封存、不销毁** | DDL 补丁 + schema 断言 `83→84` + 实体补列 | 与 ER-03「5 年留存对象只脱业务视图、不销毁」**同构**；监管上报记录**可追溯**（审计友好） |
| **B** | **物理删除**（复刻 ER-14 风格） | 零 DDL | 与 `supervise_report.retain_until` 的 DDL 注释「保留至」字面可通；但**销毁监管上报凭证**，若监管事后追溯将无法举证 —— **与 ER-03 的"不销毁"口径冲突** |
| **C** | 本卡**不做**，只登记残留转 P2-7 / 另立卡 | 零 | 缺口继续挂着；但 `retain_until` 只写不读的静默失效会**延续到 D-6** |

> **默认建议 = A**。理由：① 监管上报是**对外凭证**，销毁的可逆性为零（P2-7 已确立"删除走回收站/隔离目录、保留可还原窗口"的红线，本项目对"删除"一贯谨慎）；② 与既有 `ARCHIVED` 语义同构，避免同一系统两套"到期处置"哲学；③ DDL 成本可控（**+1 可空列，非破坏、可回退**，沿用 D-2/D-3 已跑通的补丁流程）。

### 3.2 裁决点 D5-D2：ADR-0004「任务数 ≥ 3」的计数口径（**与 P2-7 卡面冲突**）

| 出处 | 表述 |
|---|---|
| `ADR-0004:38` | 出现「**定时任务数量 ≥ 3**」即启动迁 XXL-Job 评估 |
| `ADR-0004:94-96` | 当前 =「**1 个触发壳 / 2 个动作**」，未达阈值 |
| `BACKLOG:173`（P2-7 卡面） | 「现 **2** 个动作，**本卡落地后 = 3** → 触发复核点」 |
| `BACKLOG:211`（D3-R4） | `supervise_report` 归档 → **归 D-5** |

⇒ 若 D-5 落地 supervise_report 归档，**动作数 = 3 在 D-5 就达成**，P2-7 落地后将是 **4**。**P2-7 卡面的"3"已过期，必须同步改写。**

| 选项 | 做法 | 影响 |
|---|---|---|
| **A**（默认建议） | **按动作数诚实计数**：D-5 = 第 3 个动作 → **本卡内显式做一次 XXL-Job 迁移复评并回写 ADR-0004**（预期结论仍是"不迁"，但必须留痕），并**同步改写 P2-7 卡面为"本卡落地后 = 4"** | 多一次决策留痕；换来计数口径自洽 |
| **B** | 把 `supervise_report` 归档**并入现有 `EvaluationArchiveTask` 一个动作**内（一个动作覆盖多表） | 动作数仍 2、P2-7 才到 3（与卡面自洽）；**但** supervise 与 evaluation 是平级兄弟模块，归档逻辑落 evaluation = 重演 D-3 已否决的"路径语义 ≠ 模块归属"；落 supervise 则链路壳要注入第三个任务 → **动作数又变 3**，自相矛盾 |

> **默认建议 = A**。B 会造成跨模块分层污染且**掩盖真实任务数增长**（ADR-0004 的复核点本就是为了在复杂度上升时被触发，绕过它等于废掉这条风控）。

### 3.3 裁决点 D5-D3：`D4-R1`（`elder_family_bind.auth_end_at` 到期流转 `status=4`）是否纳入 D-5

两处记录互相矛盾：`BACKLOG:215` 记「**保持残留**…若需展示一致，**另立卡**」；`D4-family-domain-design.md:968` 记「清理/流转**排 P2-7/D-5**」。

> **默认建议 = 不纳入**。理由：① 安全性**不依赖**该流转（第三闸 `MY_ELDER_SUBQUERY` 含 `auth_end_at IS NULL OR > NOW()` → **fail-closed**，到期即数据不可达）；② 它是**展示态陈旧**问题，不是合规缺口；③ 纳入会把 D-5 从"保留期链路收官"扩成"所有到期语义大杂烩"，违反单卡聚焦。**建议在 D-5 报告中显式"再登记一次残留"并注明"若要展示一致另立卡"**。

### 3.4 裁决点 D5-D4：「形态归一」的具体动作是否需要一次轻量重构

| 选项 | 做法 |
|---|---|
| **A**（默认建议） | **做**：把 ER-14 的保留期端口从 `domain/thirdparty/`（与登录适配器混放）迁至与归档对称的位置（如 `domain/retention/`），使三处"保留期端口"同址；**纯移动 + 改 import，零行为变更**，由既有单测/门禁作证 |
| **B** | **不做**：仅在本报告中记录"形态已一致，包路径差异属风格" |

> **默认建议 = A（轻量）**，因为卡名即「形态归一」，做一次"看得见"的归一比只在报告里声明更符合卡面预期；且纯移动风险极低（编译期即暴露遗漏）。

---

## 4. 裁决清单（待拍板）

| # | 裁决点 | 默认建议 | 影响面 |
|---|---|---|---|
| **D5-D1** | `supervise_report` 到期处置语义 | **A：加 `archived_at` 列，只读封存（不销毁）** | **需 DDL 补丁**；schema 断言 83→84 |
| **D5-D2** | ADR-0004 任务数计数口径 | **A：按动作数诚实计数**；D-5 内做 XXL-Job 复评 + 回写 ADR + 改写 P2-7 卡面为 4 | ADR + BACKLOG 文档 |
| **D5-D3** | `D4-R1` 是否纳入 D-5 | **不纳入**，再登记一次残留 | 仅文档 |
| **D5-D4** | 是否做端口包路径轻量归一 | **做**（纯移动，零行为变更） | 1 个文件移动 + import |

---

## 5. 若按默认建议落地的设计草案（供裁决后直接进实现）

| # | 落点 | 内容 |
|---|---|---|
| 1 | **DDL** | `docker/mysql/init/02_schema.sql` + 新 `docs/reviews/D5-supervise-report-DDL-Patch-Proposal.sql`：`ALTER TABLE supervise_report ADD COLUMN archived_at DATETIME NULL COMMENT '归档时间（ER-03 到期只读封存，非销毁）'`；`idx_retain` 已存在，**不新增索引** |
| 2 | **端口** | `yl-module-supervise/domain/retention/ExpiredSuperviseReportArchiver`（2 方法：`archiveExpired(cutoff, limit)` / `countExpired(cutoff)`）——与 `ExpiredOrderArchiver` **同形** |
| 3 | **实现** | `SuperviseReportMapper` 增手写 `@Update`：`UPDATE supervise_report SET archived_at = NOW() WHERE archived_at IS NULL AND retain_until IS NOT NULL AND retain_until < #{cutoff} LIMIT #{limit}`（**幂等**：`archived_at IS NULL` 是命中条件，归档后离开集合） |
| 4 | **任务** | `yl-module-supervise/service/retention/SuperviseReportArchiveTask#archiveExpiredReports()`（无 `@Scheduled`；`batch-size`/`max-rounds`/`dry-run` 三件套；异常吞掉返回 0） |
| 5 | **壳** | `RetentionChainScheduler` 注入第三个任务，**串行第三环**；链路日志/审计 detail 增 `archivedReports` 计数；门槛仍「三者不同时为 0 才写审计」 |
| 6 | **配置** | `application.yml` 增 `yl.retention.supervise-archive.{enabled,batch-size,max-rounds,dry-run}`（与既有两族同形） |
| 7 | **门禁** | ① `RetentionChainSchedulerTest` **扩反射断言**：第三个任务方法同样**不得带 `@Scheduled`**（否则双份调度风险）；② 新任务单测（含 dry-run / 幂等 / 异常吞掉 / 限批轮次）；③ `verify-schema.sh` 断言 `83 → 84` + 新增 `archived_at` 列存在断言；④ 全量四门禁复跑 |
| 8 | **形态归一（D5-D4）** | `domain/thirdparty/ExpiredBindingPurger` → `domain/retention/ExpiredBindingPurger`（或与三处统一的包名），改 import；零行为变更 |
| 9 | **文档** | 回写 `ADR-0004`（任务数 2→3 的显式复评 + 结论）、`BACKLOG` 的 D-5 行改 ✅ 与 P2-7 卡面计数、`MEMORY-ARCHITECTURE.md` |

**验收判据（拟）**：
1. 构造 `supervise_report` 超期行 → 跑一次链路 → `archived_at` 被写入、**行保留**（非删除）；
2. 未到期行 / `retain_until IS NULL` 行**未动**；
3. 三个动作在**同一次调度**内串行完成，日志含三者实际处理行数；
4. 重复跑**幂等**（第二次 0 行）；
5. 与 ER-14 物理删除、ER-03 归档**互不影响**（前一环异常不阻断后两环）。

---

## 6. 边界（本卡不做）

- ❌ **不碰任何文件删除/回收**（`export_task.expire_at`、`proof_cos_key` 一律归 **P2-7**）
- ❌ **不新增第二个 `@Scheduled`**（全仓唯一触发壳不变，ADR-0004）
- ❌ **不做 `D4-R1`**（见 §3.3）
- ❌ **不给 `supervise_report` 加对外读端点**（超范围；归档的可观测性落在 DB 与审计层）
