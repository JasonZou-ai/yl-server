# D-5 保留期链路收官 · 设计 + 裁决 + 任务分解

> 角色：架构师 ｜ 产出类型：**设计 + 裁决 + 任务分解**（不含业务代码）
> 真源：`docs/reviews/D5-foundation-check.md`（**已实读**）｜`docs/ADR/0004-scheduled-task-framework.md`｜`docker/mysql/init/02_schema.sql`｜**既有 2 个 retention 动作作为范式**
> 节拍：**严格照既有 retention 动作（`ExpiredOrderArchiver` + `EvaluationArchiveTask`）的形态打，不重新发明范式**
> 边界：**本卡不碰文件删除/回收**（归 P2-7）；**不新增第二个 `@Scheduled`**（全仓唯一触发壳不变）

---

## 0. 裁决记录（2026-10-09 用户拍板，**不得推翻**）

| # | 裁决点 | 裁定 | 落地口径（机械可验收） |
|---|---|---|---|
| **D5-D1** | `supervise_report` 到期处置语义 | **A：加列只读封存，不销毁** | 新增 `archived_at DATETIME NULL`；到期 `SET archived_at = NOW()`，**行保留**。走 DDL 补丁流程，schema 断言 **83 → 84** |
| **D5-D2** | ADR-0004「任务数 ≥ 3」计数口径 | **A：按动作数诚实计数** | D-5 落地后动作数 = **4** → **本卡内显式做一次 XXL-Job 迁移复评并回写 ADR-0004**；**P2-7 卡面「落地后 = 3」须改写为「= 5」** |
| **D5-D3** | `D4-R1` 是否纳入 D-5 | **纳入** | 增加第 4 个动作：`elder_family_bind` 到期 `status 2 → 4` |
| **D5-D4** | 「形态归一」的具体动作 | **做（轻量）** | ER-14 保留期端口 `domain/thirdparty/ExpiredBindingPurger` → `domain/retention/`（**纯移动 + 改 import，零行为变更**） |
| **D5-D5** | `auth_end_at` 无写入口的处置 | **仍落地 + 登记残留** | 动作照落，验收用**种子过期行**证明；登记残留「`auth_end_at` 全仓无写入口 ⇒ **生产零命中**，待有效期来源卡（`elder_authorization`/admin）落地后自动生效」 |

---

## 1. 事实核对（承 `D5-foundation-check.md`，全部实读）

| 事实 | 位置 | 对本设计的影响 |
|---|---|---|
| ER-14 / ER-03 两个动作**形态已完全一致**（端口 2 方法 + 手写 SQL + 任务无注解 + 壳统一调度） | `ExpiredBindingPurger` / `ExpiredOrderArchiver` / `SysUserThirdPartyMapper:20-41` / `EvalOrderMapper:20-41` / `ThirdPartyRetentionCleanupTask:87` / `EvaluationArchiveTask:79` | 新 2 个动作**逐字复刻此形态**，零新增范式 |
| 壳 = 全仓唯一 `@Scheduled`，串行调 2 个动作 | `RetentionChainScheduler.java:73-89` | 改为串行调 **4** 个动作；日志/审计 detail 扩字段 |
| `supervise_report.status` = **TINYINT 0-3（上报状态）**，**不承载归档**；表无 `deleted`、无 `archived_at` | `02_schema.sql:625-643` | **必须加列**（D5-D1）；本表**无 `updated_at`**（勿写） |
| `supervise_report.retain_until` 写入口 = B10（`now + 5y`）；`idx_retain` 已建 | `SuperviseReportService.java:78/92`；`02_schema.sql:642` | 写入口已就绪，**只缺消费方** |
| `supervise_report` **无任何对外读端点**（`SuperviseReportMapper` 是空 `BaseMapper`） | `SuperviseReportMapper.java:9` | 归档在 API 层**不可观测**；验收只能落 **DB 行差异 + 动作返回值 + 日志** |
| `elder_family_bind.auth_end_at` **全仓无写入口**（全表恒 NULL）；`status=4` = 已解绑/过期；`idx_expire(auth_end_at,status)` 已建 | 全仓 grep 仅 `02_schema.sql` 命中；`FamilyBindingService` 明确「`auth_end_at` **不动**」 | D5-D5：动作落地但**生产零命中**；**必须登记残留** |
| `elder_family_bind` 的 `active_uk = IF(deleted=0,1,NULL)`；「重新申请」靠 **复用活性行 UPDATE 复位** | `02_schema.sql:236`；`FamilyBindingService` §R-2 | 到期流转**只改 `status`，绝不碰 `deleted`**（否则释放唯一键 → 破坏复用语义） |
| 第三闸 `getSqlSegment` 在 `currentUser() == null` 时 **直接 return null（不改写）** | `DataScopePermissionHandler.java:237-241` | **定时任务内无登录态 ⇒ 第三闸 no-op** ⇒ 新 Mapper 手写 SQL **无需** `@InterceptorIgnore`（与既有 2 个 retention Mapper 同） |
| `verify-schema.sh` `EXPECTED_TOTAL_ASSERTIONS=83`；保留字金丝雀 = 1 | `verify-schema.sh:483 / :266` | 断言 **83 → 84**；`archived_at` **非保留字** ⇒ 金丝雀仍 = 1 |
| `RetentionChainSchedulerTest` 用**反射**断言「壳有 `@Scheduled`、两个任务方法都没有」 | `RetentionChainSchedulerTest.java:101-113` | **必须扩到 4 个任务方法**（否则新动作的注解无守卫 → 未来"顺手加回注解"会双份调度） |
| `yl-bootstrap/pom.xml` 已依赖全部模块（含 supervise/archive） | `D3-report-supervise-domain-design.md:19` | **无需改聚合依赖** |

---

## 2. 链路终态（1 壳 / 4 动作）

```
RetentionChainScheduler#runRetentionChain()          ← 全仓唯一 @Scheduled（cron yl.retention.chain-cron）
  ① ThirdPartyRetentionCleanupTask.purgeExpiredBindings()   → 物理删除（ER-14，30 天）
  ② EvaluationArchiveTask.archiveExpiredOrders()            → eval_order.status: PUBLISHED → ARCHIVED（ER-03）
  ③ SuperviseReportArchiveTask.archiveExpiredReports()      → supervise_report.archived_at 写入   【新，D5-D1】
  ④ FamilyBindingExpireTask.expireExpiredBindings()         → elder_family_bind.status: 2 → 4      【新，D5-D3】
```

- **顺序**：① → ② → ③ → ④ 串行；各动作**各自吞异常返回 0** ⇒ 前一环失败不影响后三环（承 ADR-0004 约束 4）。
- **审计门槛**：四个计数**不同时为 0** 才写一条 `RETENTION_CHAIN`；detail 增 `archivedReports` / `expiredBindings`。
- **日志**：一条链路日志含**四个计数**（承 P1-4b 的「日志含实际处理行数」验收口径）。

---

## 3. 逐文件设计

### 3.1 DDL（**+1 列，非破坏、可回退**）

| 落点 | 内容 |
|---|---|
| `docker/mysql/init/02_schema.sql` | `supervise_report` 建表语句内**内联**新列（供全新库）：`archived_at DATETIME NULL COMMENT '归档时间（ER-03 到期只读封存，非物理删除）'`，置于 `retain_until` 之后 |
| `docs/reviews/D5-supervise-report-DDL-Patch-Proposal.sql`（新） | 对**存量库**的 `ALTER TABLE supervise_report ADD COLUMN archived_at DATETIME NULL … AFTER retain_until;` + 头部状态登记（照 `D3-export-task-DDL-Patch-Proposal.sql` 体例） |
| 索引 | **不新增**（`idx_retain(retain_until)` 已存在，扫描支撑就绪） |
| 门禁 | `verify-schema.sh` 断言 **83 → 84**：新增「`supervise_report.archived_at` 存在且为 `datetime`」1 条 |

> **可回退**：`DROP COLUMN archived_at` 即还原；无数据搬迁。

### 3.2 新端口 × 2（**逐字复刻 `ExpiredOrderArchiver` 形态**）

| 模块 | 端口 | 方法 |
|---|---|---|
| `yl-module-supervise` | `domain/retention/ExpiredSuperviseReportArchiver` | `int archiveExpired(LocalDateTime cutoff, int limit)` / `long countExpired(LocalDateTime cutoff)` |
| `yl-module-archive` | `domain/retention/ExpiredFamilyBindingExpirer` | `int expireExpired(LocalDateTime cutoff, int limit)` / `long countExpired(LocalDateTime cutoff)` |

javadoc 必须写明：**幂等约定**（命中条件含"未处理"标志，处理后离开集合 ⇒ 无需分布式锁）+ **动作性质**（封存 ≠ 销毁 / 流转 ≠ 删除）。

### 3.3 Mapper 手写 SQL × 2

**③ `SuperviseReportMapper`（继承 `BaseMapper<SuperviseReport>` + `ExpiredSuperviseReportArchiver`）**

```sql
UPDATE supervise_report
   SET archived_at = NOW()
 WHERE archived_at IS NULL
   AND retain_until IS NOT NULL
   AND retain_until < #{cutoff}
 ORDER BY retain_until
 LIMIT #{limit}
```
```sql
SELECT COUNT(*) FROM supervise_report
 WHERE archived_at IS NULL AND retain_until IS NOT NULL AND retain_until < #{cutoff}
```

> **幂等**由 `archived_at IS NULL` 保证；**表无 `updated_at`，勿写**；`retain_until` **不清空**（留存事实，同 `EvalOrderMapper` 口径）。

**④ `ElderFamilyBindMapper`（新增 `ExpiredFamilyBindingExpirer` 实现）**

```sql
UPDATE elder_family_bind
   SET status = 4
 WHERE status = 2
   AND deleted = 0
   AND auth_end_at IS NOT NULL
   AND auth_end_at < #{cutoff}
 ORDER BY auth_end_at
 LIMIT #{limit}
```
```sql
SELECT COUNT(*) FROM elder_family_bind
 WHERE status = 2 AND deleted = 0 AND auth_end_at IS NOT NULL AND auth_end_at < #{cutoff}
```

> ★ **绝不碰 `deleted`**（`active_uk` 让位条件只认 `deleted=1`；改 `deleted` 会释放唯一键、破坏「重新申请复用活性行」语义）。`updated_at` 由 DDL 的 `ON UPDATE CURRENT_TIMESTAMP` 自动维护。

### 3.4 任务类 × 2（**照 `EvaluationArchiveTask` 逐字复刻**）

| 模块 | 任务 | 业务方法 |
|---|---|---|
| `yl-module-supervise` | `service/retention/SuperviseReportArchiveTask` | `int archiveExpiredReports()` |
| `yl-module-archive` | `service/retention/FamilyBindingExpireTask` | `int expireExpiredBindings()` |

共同要求：
- **方法上不带 `@Scheduled`**（触发壳在 `yl-bootstrap`）；
- 构造器注入 `enabled` 由 `@ConditionalOnProperty` 控制 + `batch-size`/`max-rounds`/`dry-run` 三件套（`@Value` 默认值）；
- `dry-run` 只 `countExpired` 并返回 0，**不写审计**；
- 限批循环：`for round in 1..maxRounds { n = action(cutoff, batchSize); total += n; if (n < batchSize) break; }`；
- `total == 0` → 仅 info 日志「无到期…本轮未…」；
- `total > 0` → **info 日志含批次**（**不写自身审计**，见 §3.4.1 订正）；
- **异常只记 error、返回 0**（下周期自然重试）。

#### 3.4.1 ★ 订正（实施期证伪，2026-10-09）

**原文（本设计初稿）要求**：③/④ 动作**各自写审计**（动作名 `SUPERVISE_REPORT_ARCHIVE` / `FAMILY_BIND_EXPIRE`）。

**实施期证伪 —— 架构上不可行**：`AuditService` 位于 `yl-module-account`；而 `yl-module-supervise` / `yl-module-archive` **仅依赖 `yl-api`**（「平级兄弟模块」不变量，§1 已核「无需改聚合依赖」）。给二者加 account 依赖会破坏该分层，**重演 D-3 已否决的「路径语义 ≠ 模块归属」**。

**订正后口径（已落地）**：**只做链路级留痕** —— `RetentionChainScheduler` 写一条 `RETENTION_CHAIN`，detail 含四计数 `{purged, archived, archivedReports, expiredBindings, costMs}`，门槛「四者不同时为 0」。

**与既有先例一致**：ER-03 的 `EvaluationArchiveTask`（同属非 account 模块）**本就不写自身审计**，其留痕「并入链路级一条」（ADR-0004 补充 §5 明记「有意」）；ER-14 的 `ThirdPartyRetentionCleanupTask` 因**在 account 模块内**才自写 `THIRD_PARTY_PURGE`。⇒ 现行口径**不是妥协，而是与既有分层的既有判例对齐**。

### 3.5 壳改造（`RetentionChainScheduler`）

- 注入 2 个新任务（构造器参数 + final 字段）；
- `runRetentionChain()` 串行增 2 环，日志改为**四计数**；
- `recordAudit` detail JSON 增 `archivedReports` / `expiredBindings`；门槛改「四者不同时为 0」；
- javadoc 更新为「一次调度做四件事」+ 迁移边界说明保持。

### 3.6 配置（`application.yml`，与既有两族同形）

```yaml
yl:
  retention:
    supervise-archive:
      enabled:    ${RETENTION_SUPERVISE_ARCHIVE_ENABLED:true}
      batch-size: ${RETENTION_SUPERVISE_ARCHIVE_BATCH:500}
      max-rounds: ${RETENTION_SUPERVISE_ARCHIVE_MAX_ROUNDS:20}
      dry-run:    ${RETENTION_SUPERVISE_ARCHIVE_DRY_RUN:false}
    family-bind-expire:
      enabled:    ${RETENTION_FAMILY_BIND_EXPIRE_ENABLED:true}
      batch-size: ${RETENTION_FAMILY_BIND_EXPIRE_BATCH:500}
      max-rounds: ${RETENTION_FAMILY_BIND_EXPIRE_MAX_ROUNDS:20}
      dry-run:    ${RETENTION_FAMILY_BIND_EXPIRE_DRY_RUN:false}
```

### 3.7 形态归一（D5-D4，纯移动）

`yl-module-account/domain/thirdparty/ExpiredBindingPurger.java` → `domain/retention/ExpiredBindingPurger.java`；
同步改 import：`SysUserThirdPartyMapper`、`ThirdPartyRetentionCleanupTask`、`ThirdPartyRetentionCleanupTaskTest`；同步改 `ADR-0004:45` 的路径引用。**零行为变更**（编译期即暴露遗漏）。

### 3.8 关键实现约束（易错点清单）

1. **无登录态 ⇒ 第三闸 no-op**（`getSqlSegment` 首行 `user == null → return null`）⇒ 新 Mapper **不加** `@InterceptorIgnore`；
2. **幂等条件必须含"未处理"标志**（`archived_at IS NULL` / `status = 2`），否则重复跑会重复计数；
3. **`deleted` 非必要不动**（④ 尤其）；
4. **`@ConditionalOnProperty(matchIfMissing = true)`** ⇒ 开关默认开，与既有两族一致；
5. **不得给任务方法加 `@Scheduled`**（否则双份调度）。

---

## 4. 门禁影响

| 载体 | 变更 | 期望 |
|---|---|---|
| `mvn -o verify` | +2 任务单测（dry-run / 幂等 / 异常吞掉 / 限批轮次 / 无数据返回 0） | 单测 **255 → 255+**（只升不降） |
| `RetentionChainSchedulerTest` | ① `runsBothActionsInOrder` → 四动作；② `recordsSingleAuditWithBothCounts` → 四计数；③ **反射断言扩到 4 个任务方法均无 `@Scheduled`** | 全绿 |
| `verify-schema.sh` | 断言 **83 → 84**（`archived_at` 存在 + `datetime`）；金丝雀仍 = 1 | 84/0 |
| `verify-openapi.sh` | **无新端点** ⇒ 零改动 | 8/8（implemented 44 / planned 0，漂移 0） |
| `smoke-report-review-chain.sh` | 无新端点、未改既有端点 ⇒ 预期不受影响 | 基线持平 |
| Checkstyle / SpotBugs | 新类须过 AOSP + Medium+（控制器注解与 `@SuppressFBWarnings(justification=…)` 规则照旧） | 0/0 |
| **保留期链路实跑** | 构造超期数据 → 跑一次 → **四动作**（照 P1-4b 验收法） | 见 §5 |
| **DB 级保留期链路门禁**（新，闭环 D5-R1） | 新增 `RetentionChainDbIT`（T1–T4 行为断言 + **T5 SQL 守卫存在性** + **T6 NULL 到期语义**）+ `yl-bootstrap` profile `db-it`（surefire `includes=**/*IT.java`，**固定 `<version>3.5.4</version>`** 消 WARNING）+ 脚本 `scripts/verify-retention-chain.sh`；突变工具入库 `scripts/dev/`（`run-retention-mutations.sh` + `mutate-retention-sql.py`，标注**开发工具非门禁**） | 真连 MySQL：种数据→跑真任务→DB 断言→自清理；**用例 6/0**；**4 个 SQL 条件突变均变红**；`*IT` 不进默认 surefire ⇒ **不影响 `mvn -o verify` = 270/0** |

---

## 5. 验收判据（拟，交 QA 独立复核）

| # | 判据 | 方法 |
|---|---|---|
| 1 | `supervise_report` 超期行 → 跑一次 → `archived_at` 被写、**行保留**（非删除） | 种子超期行 + 实跑链路 + DB 查 |
| 2 | `elder_family_bind` 到期行（`status=2` + `auth_end_at < now`）→ 跑一次 → `status=4`，且 **`deleted` 仍 = 0** | 种子过期行 + 实跑 + DB 查 |
| 3 | 未到期行 / `retain_until IS NULL` / `auth_end_at IS NULL` / `status≠2` 行**全部未动** | 反例四类 |
| 4 | **四动作在同一次调度内串行完成**，日志含四计数 | 日志断言 |
| 5 | **重复跑幂等**（第二次 0 行 / 0 单） | 连跑两次 |
| 6 | 前一环异常**不阻断**后三环 | 单测（mock 抛异常） |
| 7 | `supervise_report` 归档**不影响**其 `status`（上报状态） | DB 查：`status` 保持不变 |
| 8 | **形态归一零行为变更**：移动后单测全绿、无 import 泄漏 | `mvn verify` |

---

## 6. 任务分解（串行）

| 序 | 任务 | 产出 | 依赖 |
|---|---|---|---|
| T1 | DDL 列 + 补丁提案 + 存量库 ALTER | `02_schema.sql` / `D5-supervise-report-DDL-Patch-Proposal.sql` | — |
| T2 | 形态归一（端口移动 + import） | 4 个文件路径/import | — |
| T3 | ③ supervise 端口 + Mapper + 任务 + 单测 | `yl-module-supervise` 4 文件 | T1 |
| T4 | ④ archive 端口 + Mapper + 任务 + 单测 | `yl-module-archive` 4 文件 | T1 |
| T5 | 壳改造 + 配置 + `RetentionChainSchedulerTest` 扩展 | `yl-bootstrap` 3 文件 | T3,T4 |
| T6 | 门禁三件套 + schema 断言 84 + 链路实跑 | 证据 | T5 |
| T7 | **归档-0004 回写**（任务数 2→4 的显式复评 + 结论）+ `BACKLOG` D-5 行改 ✅ + P2-7 卡面计数改 5 | 文档 | T6 |
| T8 | **QA 独立验证**（突变 ≥6 条） | QA 报告 | T6 |

---

## 7. 边界（本卡不做）

- ❌ **不碰文件删除/回收**（`export_task.expire_at`、`proof_cos_key` 一律归 **P2-7**）；
- ❌ **不新增第二个 `@Scheduled`**；
- ❌ **不给 `supervise_report` 加对外读端点**（超范围）；
- ❌ **不给 `elder_family_bind.auth_end_at` 补写入口**（D5-D5：需改契约，超范围；登记残留）；
- ❌ **不动 `elder_family_bind.deleted`**（唯一键让位语义）。
