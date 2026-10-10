# D-5 QA 独立验证报告 · 保留期链路收官

> 角色：QA 工程师（Edward）｜ 类型：**独立验证**（不复用工程师结论，主动找反例）
> 被验对象：D-5 保留期链路收官（`RetentionChainScheduler` 2 动作 → **4 动作** + `supervise_report.archived_at` DDL + 形态归一）
> 判据来源：`docs/design/D5-retention-chain-finale-design.md`（§2 链路终态 / §3 逐文件设计 / §4 门禁 / §5 验收判据 / §7 边界）、`docs/reviews/D5-foundation-check.md`
> 日期：2026-10-09 ｜ 环境：MySQL 8.0.46（3306）/ Redis（6379）；开工前 8080 空闲
> 工件：`/tmp/d5-qa/logs`、`/tmp/d5-qa/`（含 SQL 解析脚本 `extract_sql.py`）

---

## 0. 结论先行：**PASS**

工程师自报结论全部**独立复现**；设计 §5 的 **8 条验收判据全部独立通过**。另发现 **1 项工程师未提的高价值残留（D5-R1）+ 2 项低危（D5-R2 / 文档）**。

**卫生确认**：测试数据已自清理、**全部突变已还原**、`git status` 与开工前**逐字节一致**、8080 已释放、无 java 进程残留。

---

## 1. 任务 A：突变测试（8 条，均「变红 → 还原 → 复绿」）

| # | 突变点 | 预期变红位置 | 实测结果 | 还原 |
|---|---|---|---|---|
| **M1** | `SuperviseReportMapper#archiveExpired` 摘 `archived_at IS NULL` | 幂等 | 实跑 SQL：`affected=3`（正确 2），并把已归档行 S2 的 `archived_at` 从 `2020-06-01` **重置为 now**；隔 2s 再跑 `affected=3`（应 0）⇒ **幂等被破坏** | ✓ md5 `caca974c` |
| **M2** | `ElderFamilyBindMapper#expireExpired` 摘 `status = 2` | 反例被误改 | 实跑 `affected=4`（正确 1）；`status=0/3/1` 行被误改 → 4 | ✓ `5f55e7a5` |
| **M3** ★**高危哨兵** | `SET status = 4` 改成 `SET status = 4, deleted = 1` | 「`deleted` 仍为 0」 | **被抓住**：B1 `deleted→1`、`active_uk→NULL`（**唯一键被释放**）；断言失败 | ✓ |
| **M4** | 摘 `deleted = 0` | 反例 `deleted=1` 被误改 | 实跑 `affected=2`（正确 1）；B2(`deleted=1`) `status 2→4` | ✓ |
| **M5** | 给 `FamilyBindingExpireTask#expireExpiredBindings` 加 `@Scheduled` | 反射断言 | `RetentionChainSchedulerTest#schedulerIsTheOnlyTrigger` + `FamilyBindingExpireTaskTest#hasNoScheduledTrigger` **双双变红** | ✓ `f0a416a8` |
| **M6** | 摘掉壳第 4 环调用 | 顺序断言 | `runsFourActionsInOrder:56`、`recordsSingleAuditWithFourCounts:85`、`writesAuditWhenOnlyLastActionHappened:122` **三红** | ✓ `ef4f6dd3` |
| **M7** | 壳审计门槛改「总是写」 | 空跑不写审计 | `skipsAuditWhenNothingHappened:103` 红 | ✓ |
| **M8** | 摘掉任务 `dry-run` 分支 | dry-run 用例 | `dryRunDoesNotArchive:76` 红 | ✓ `7fed4b7f` |

> **M1–M4 的门禁口径**：这两张表的 SQL 条件**无自动化测试覆盖**（见 §5-1），故以「**实跑 SQL**」为门禁；SQL 由 `extract_sql.py` **从被突变的源文件解析**（非手抄），原始输出见 `/tmp/d5-qa/logs`。
> **M3 的意义**：证明「④ 动作绝不碰 `deleted`」这条红线在**实跑层**是可被抓住的——但**不在自动化套件内**（见 §5-1）。

---

## 2. 任务 B：独立实跑（自造边界/反例，非照抄工程师脚本）

**Run1**（自建 jar；cron `*/15s`；sup `batch=2` / bind `batch=2`）
- 审计**恰 1 条**：`{"purged":0,"archived":0,"archivedReports":5,"expiredBindings":3,"costMs":165}`
- tick#1：`SUPERVISE-ARCHIVE 归档完成：5 条，批次 3`、`FAMILY-BIND-EXPIRE 流转完成：3 条，批次 2` ⇒ **多轮遍历正确（总处理数 = 应处理数：5 / 3）**
- tick#2–4：`无到期`；4 个 tick **只写 1 条审计** ⇒ **幂等 ✓**
- `supervise_report` 终态：S1–S5 `archived_at=now` 且 **`status` 仍 = 1（未变）**、行保留；S6（已归档）不变；S7（未到期）/ S8（`retain_until NULL`）**未动**
- `elder_family_bind` 终态：C1–C3 → 4 且 **`deleted` = 0**；C4(`deleted=1`)→2、C5(`status=4`)→4、C6(`status=0`)→0、C7(`auth_end_at NULL`)→2、C8（未到期）→2、C9(`status=3`)→3、C10(`status=1`)→1 ⇒ **反例七类全未误改**
- **跨动作隔离**：`purged=0 / archived=0` 而三、四环非零 ⇒ **互不影响 ✓**

**Run2**（`max-rounds=1`、`batch=2`）
- 首 tick 审计 `{"archivedReports":2,"expiredBindings":2}`、日志 `2 条，批次 1`
- 剩余 sup **3 未归档** / bind **1 未流转** ⇒ **只处理一批即止、不越界 ✓**

---

## 3. 任务 C：独立源文件抽检

- **两个任务类**：方法**无 `@Scheduled`**；`@ConditionalOnProperty(matchIfMissing=true)`；限批循环 `for round 1..maxRounds { n = action(cutoff, batch); total += n; if (n < batch) break; }` **边界正确**；异常 `catch RuntimeException → log.error → return 0`（**不向调度器抛**）；`total == 0` 仅日志、**任务本身从不写审计**（审计在壳）。
- **两个 Mapper SQL**：**只用** `02_schema.sql` 真实存在的列；`supervise_report` 的 UPDATE **只写 `archived_at`、未写 `updated_at`**（该表确无 `updated_at`，已核 `02_schema.sql:625-644`）。两实体**未越界映射**生成列（`ElderFamilyBind` 未映射 `active_uk`）。
- **`verify-schema.sh`**：`EXPECTED_TOTAL_ASSERTIONS=84`；**实跑 84/0**（保留字金丝雀 = 1；D-5 `archived_at` 断言通过）。对 `git diff HEAD` 统计：**新增断言行 +20 / 删除断言行 0**（D1–D5 累积）；D-4 基线 83 → D-5 84（+1）。⇒ **无「删旧断言凑数」**。

### 附加门禁（独立重跑）
| 门禁 | 结果 |
|---|---|
| `mvn -o verify`（还原后终态） | **270 / 0**，Checkstyle **0**（14 模块），SpotBugs pass，**BUILD SUCCESS** |
| `verify-openapi.sh` | **8/8**（implemented 44 / planned 0） |
| `verify-schema.sh` | **84 / 0** |
| `smoke-report-review-chain.sh` | **119 / 0 / 0**（工程师 116/0/1：QA 额外设了 `SECOND_VERIFY_DEV_CODE`，少 1 跳过、多 3 断言，**数值自洽**） |

---

## 4. 审计粒度「设计偏差」理由独立核实 → **成立**

| 核查点 | 证据 | 结论 |
|---|---|---|
| `AuditService` 所在模块 | `cn.yl.modules.account.service.AuditService`（**account**） | — |
| supervise / archive 模块依赖 | 两 `pom.xml` **仅依赖 `yl-api`**（+ mybatis-plus / spring-boot-starter-test），**不含 account** | 编译期即拿不到 `AuditService` ✓ |
| 非 account 模块先例 | `EvaluationArchiveTask`（evaluation 模块）**零 audit 引用** | 「非 account 模块不自写审计」先例为真 ✓ |
| account 内先例 | `ThirdPartyRetentionCleanupTask` 确实自写 `THIRD_PARTY_PURGE` | ✓ |

⇒ 「只做链路级 `RETENTION_CHAIN`」的理由与**既有分层判例一致，不是妥协**。
- **唯一瑕疵（低危）**：设计 §3.4.1 引「ADR-0004 补充 §5 明记『有意』」，但 ADR 仅记「链路级留痕按有事发生为门槛」（item 5），**未显式记录粒度有意**。引用略松，不影响结论。

---

## 5. 独立发现（工程师未提）

### 5-1 ★ **D5-R1（中–高 · 残留风险）：保留期链路的 Mapper 手写 SQL 条件「无任何自动化回归守卫」**

**实测**：把 `archived_at IS NULL`（幂等标志）摘掉后，`mvn -o verify` **仍 270/0 SUCCESS**；同理 `status = 2` / `deleted = 0` / `SET deleted = 1` **都不会被自动化套件发现**。

**根因**：单测一律用 **Fake/mock 端口**，**永不触达真实 SQL**。⇒ 这些约束目前**只靠一次性实跑**证明，**缺可持续回归守卫**。

**影响面**：**不限于 D-5 新增** —— 既有 ER-03（`EvalOrderMapper#archiveExpired`）/ ER-14（`SysUserThirdPartyMapper#purgeExpired`）两个 Mapper **同样无覆盖**。

**修复建议（供裁决）**：
| 选项 | 做法 | 代价 |
|---|---|---|
| **A**（推荐） | 新增 **DB 级可重复门禁**：独立脚本（种数据 → 跑链路 → 断言幂等/反例/不碰 `deleted` → 清理）或 `@SpringBootTest` 集成测试挂 Maven profile | 需 MySQL；需决定进哪条门禁链 |
| B | 扩 `smoke-report-review-chain.sh` 增设保留期段 | 复用现有真实 MySQL 链路，但 smoke 目前走 HTTP、链路非 HTTP |
| C | 登记残留，仅验收期手工实跑 | 零成本；**门禁对该语义零区分力**（现状） |

> 已由 team-lead 登记进 `docs/change/BACKLOG-M2-P1P2.md` 残留清单（**D5-R1**，标「待裁决」）。

### 5-2 **D5-R2（低）：限批循环在整除时多跑一轮空批**
待处理数恰为 `batch-size` 整数倍时（如 4 行 / `batch=2` → 批次 3，**末轮空**）。**结果正确**（空批无副作用），仅多一次空查询。⇒ 已登记**保持残留**。

### 5-3 **（低 · 文档）** 见 §4 的 ADR 引用不精确。

---

## 6. 总体结论：**PASS**

- 工程师自报 **1 / 2 / 3 / 5** 全部独立复现；**第 4 条**（形态归一）已核：旧文件 `domain/thirdparty/ExpiredBindingPurger.java` **已删**、新 `domain/retention/` **存在**、**零残留 import**（3 处引用新包）。
- 设计 §5 全部 **8 条验收判据独立通过**（含「归档不改 `status`」「行保留」「`deleted` 不变」）。

**残留风险清单**
| 编号 | 内容 | 级别 |
|---|---|---|
| **D5-R1** | 两 retention Mapper 的 SQL 条件**缺自动化回归守卫**（mutation 不红）；建议补 DB 级可重复门禁 | **中–高** |
| **D5-R2** | 限批循环整除时多跑一轮空批（无害） | 低 |
| **R2** | `elder_family_bind.auth_end_at` **无写入口** ⇒ `FamilyBindingExpireTask` **生产零命中**（D5-D5 已登记；QA 独立确认全仓**仅读不写**，待有效期来源卡） | 已知（已登记） |
| **R3** | 设计 §3.4.1 的 ADR 引用略松 | 低（文档） |

---

## 7. 可复现命令（要点）

```bash
# 突变红/绿
mvn -o -pl <module> -am test -Dtest=<Cls> -Dsurefire.failIfNoSpecifiedTests=false
# 实跑起服务（cron 用 RETENTION_CHAIN_CRON 压低）
cd /tmp/d4-run && source ./env.sh && java -cp /tmp/d4-run/yl-bootstrap.jar \
  org.springframework.boot.loader.launch.JarLauncher --spring.profiles.active=dev --server.port=8080
# 门禁
bash scripts/verify-schema.sh
bash scripts/verify-openapi.sh
bash scripts/smoke-report-review-chain.sh
```
原始输出：`/tmp/d5-qa/logs/*.log`。
