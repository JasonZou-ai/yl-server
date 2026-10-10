# P2-7 QA 独立验证报告 · 孤儿文件回收（保留期链路第 5 个动作）

> 角色：独立 QA 工程师（**未参与实现**，以「证明它真能抓住错误」为目标，非复跑实现者脚本）
> 被验对象：P2-7 孤儿文件回收（`ObjectKeyResolver` + `RetentionReferenceMapper` + `OrphanFileSweepTask` + 壳第 5 环 + T7/T8/T9 + verify-schema +2）
> 判据来源：`docs/design/P2-7-orphan-file-sweep-design.md`（§7 验收判据 9 条 / §4 硬约束）、`docs/reviews/P2-7-foundation-check.md`（★ 报告 PDF 在用锚点 = `eval_report.report_no`）、`docs/change/BACKLOG-M2-P1P2.md` 卡 P2-7
> 日期：2026-10-10 ｜ 环境：MySQL 8（3306）/ Redis（6379）/ JDK 17 / Maven 3.9.16；开工前 8080 空闲
> 工件：`/tmp/p27-*.log`、`/tmp/p27-baseline-md5.txt`、`/tmp/p27-after-md5.txt`

---

## 0. 结论先行：**PASS**

设计 §7 的 9 条验收判据**独立通过**；核心 6 条突变**全部按预期变红并精确还原**（md5 逐一复归）。未发现恒真断言/OR 逃生口/拿退出码当通过等假绿。另登记 **2 项低危残留建议（P2-7-R5 / R6）+ 1 项数据卫生低危**。有 **1 类项如实声明「无法回溯验证」**（改造前两处 `resolve()` 原始字节）。

**卫生确认**：全部突变已**逐字节还原**（7 个被触碰文件 md5 与基线**逐一一致**）；未修改任何既有断言；8080 已释放、无 java 残留；仓内未留探针文件。

---

## 1. 突变测试（7 条，核心 6 条 + 1 加分；均「突变 → 变红/如实记录 → 精确还原 → md5 复归」）

> 跑法：DbIT 用 `bash scripts/verify-retention-chain.sh`（`mvn -o -P db-it`）；单测用 `mvn -o -pl yl-modules/yl-module-report -am test -Dtest=OrphanFileSweepTaskTest`。
> **方法说明**：M5–M7 的 `mvn test` 另加 `-Dspotless.check.skip=true` —— 因摘除 `Files.move` 调用会使 `StandardCopyOption` 成为未用 import，而 spotless-check 绑定在 test **之前**的相位、会以「格式违规」先让构建失败（与断言区分力无关）。此为隔离格式噪声，不影响断言结论。

| # | 改了什么 | 期望红点 | 实际结果 | md5 复归 |
|---|---|---|---|---|
| **M1** | `RetentionReferenceMapper.selectReportKeys()` 两处 `WHERE deleted = 0` 摘除（改 `WHERE 1 = 1` / 去 `deleted = 0 AND`） | DbIT **T8** | ✓ **红**：`t8OrphanSweepReportReferenceExcludesSoftDeleted:497`，软删报告的推导键 `report/IT-P27-9603.pdf` 重现于在用集 | ✓ `efbf156a` |
| **M2** | `selectExportKeys()` 两处 `status <> 4` → `status > -1`（恒真） | DbIT **T9** | ✓ **红**：`t9OrphanSweepExportReferenceExcludesExpired:525`，`status=4` 行键重现 | ✓ `efbf156a` |
| **M3** | `markExpired()` 摘 `AND status <> 4` | DbIT **T7**（幂等） | ✓ **红**：`t7OrphanSweepMarksExpiredExportTasks:472`，幂等断言 `expected: 0 but was: 3`（已 4 的行被重复命中） | ✓ `efbf156a` |
| **M4** | `markExpired()` 摘 `expire_at IS NOT NULL` | 预期可能**不变红** | ✓ **如实记录：未变红**（DbIT **9/0/0**、`BUILD SUCCESS`）——三值逻辑下 `NULL < cutoff` 求值 UNKNOWN，行为层**不可区分**；且本 Mapper **无静态守卫**（见 §6 P2-7-R6） | ✓ `efbf156a` |
| **M5** | `OrphanFileSweepTask.moveToTrash()`：`Files.move(...)` → `Files.delete(source)` | `OrphanFileSweepTaskTest` | ✓ **红 2 例**：`recyclesOrphansAndKeepsReferenced`（`.trash 下可寻回` 断言失败）+ `singleFileFailureIsIsolated`（计数）⇒ **「可还原窗口」确被断言**，非覆盖缺口 | ✓ `76553028` |
| **M6** | `listOrphans()` 摘 `Files.isRegularFile(p, NOFOLLOW_LINKS)` 守卫 | 子目录用例 | ✓ **红**：`skipsSubdirectoriesWithoutRecursing`（`report/subdir` 被当孤儿移走，nested.pdf 随之消失） | ✓ `76553028` |
| **M7**（加分） | `listOrphans()` `Files.newDirectoryStream(dir)` → 递归 `Files.walk(dir)` | 「只列一层」用例 | ✓ **红**：`skipsSubdirectoriesWithoutRecursing:152`（`仅根内一层的孤儿被回收` isEqualTo 1 失败） | ✓ `76553028` |

**突变后的 md5 逐一比对**（`diff` 空）：`RetentionReferenceMapper.java` `efbf156a…`、`OrphanFileSweepTask.java` `76553028…`、`RetentionReferencePort.java` `52476594…`、`ExpiredExportTaskReaper.java` `05351299…`、`ObjectKeyResolver.java` `ec831203…`、`OrphanFileSweepTaskTest.java` `0ee79f78…`、`RetentionChainDbIT.java` `24fcd163…` —— **全部 == 基线**。

**突变后复绿**：`bash scripts/verify-retention-chain.sh` → **`RetentionChainDbIT` 9/0/0 + `OrphanFileSweepTaskTest` 11/0/1 + BUILD SUCCESS**。

---

## 2. 断言质量审查

- **假绿风险：无。** `OrphanFileSweepTaskTest`（11 例）与 `RetentionChainDbIT` T7/T8/T9 的断言均为真值比较（`isEqualTo/isTrue/isFalse/isZero/isRegularFile/contains/doesNotContain`），**无恒真断言、无 OR 逃生口、无以「HTTP 200 / 退出码 0」代替业务码**。
  - 用例 `singleFileFailureIsIsolated` 用「非空目录占位 ⇒ `Files.move` 必失败」制造真实 IO 失败，断言「失败文件留在根内、仅成功 1 个」 —— 是有效隔离验证。
  - `dryRunMovesNothing` 同时断言「零移动 + 不建 `.trash` + 不调 `markExpired`」 —— 三面收口。
- **`scripts/verify-schema.sh::check()` 真做比较**：`check() { if [ "$2" = "$3" ]; then pass_msg; else fail_msg; fi; }`（`scripts/verify-schema.sh:97-103`）——确为比较，非只打印。新增 2 条 `*cos_key` 断言**真连库取值**：
  - `COS_KEY_COUNT`（`count(*)=6`）与 `COS_KEY_SET`（`GROUP_CONCAT` 逐列集合 == 登记集）。
  - **实连库验证**（非破坏性，直接执行同款 SQL）：`COS_KEY_COUNT=6` ✓；`COS_KEY_SET=elder.photo_cos_key,elder_family_bind.proof_cos_key,eval_evidence.cos_key,eval_report.pdf_cos_key,export_task.file_cos_key,sys_user.avatar_cos_key` ✓（逐字等于期望）。
  - 脚本另有 `EXPECTED_TOTAL_ASSERTIONS=86` 自检 —— 断言被静默增删即告警（防「删旧凑数」）。
- **T7/T8/T9 细读**：
  - T8 双向（`contains` 推导键/并集第二支 + `doesNotContain` 软删行的两个键）—— 正是「手写 `@Select` 不走全局逻辑删除」的行为守卫。
  - T9 双向（`status≠4` 在用 + `status=4` 两个键均退出）—— 正是导出投影排除已过期行的守卫。
  - T7 逐行验命中/反例/幂等；`marked` 与 `pendingBefore` 用**下界** `≥2`（共享表让路），**非恒真**（错值不会被放过）。
- **符号链接用例（Skipped: 1）——独立复核**：
  - 跳过判据**确为** `Files.isSymbolicLink(link)`（`OrphanFileSweepTaskTest:175`），且在 `createSymbolicLink` 之后二次判定 —— 确定性跳过，不 flaky。
  - **平台行为独立复现**（jshell 实跑）：`createSymbolicLink` **不抛**却落 **0 字节普通文件**；`isSymbolicLink=false`、`isRegularFile(NOFOLLOW)=true`、`readSymbolicLink→NotLinkException`、`size=0` —— 与设计 §4 平台限制记载**完全吻合**。
  - **在支持符号链接的平台，该用例是有意义的（非空断言）**：真符号链接下 `isRegularFile(p, NOFOLLOW_LINKS)` 恒 `false`（`report/link.pdf` 被天然跳过，`swept==0`）；若把 `NOFOLLOW` 去掉，则 `isRegularFile` 跟随到目标 `outside.txt` 为真 ⇒ 链接被当孤儿移走 ⇒ `Files.exists(link, NOFOLLOW_LINKS)` 变假 ⇒ **断言必红**。故该用例对「符号链接防逃逸」**有区分力**。
  - 本机无法构造该输入（`createSymbolicLink` 假成功）且**本地门禁恒跳过** ⇒ 建议登记残留（见 §6 **P2-7-R5**）。

---

## 3. 端到端与调度

- **壳第 5 环**（`RetentionChainScheduler.runRetentionChain`）：顺序为 三方删除 → 评估单归档 → 监管上报封存 → 亲情绑定流转 → **孤儿文件回收**（`OrphanFileSweepTask.sweepOrphanFiles()` 第 5 调用，`RetentionChainScheduler.java:104`）。
- **五计数 + 门槛 + 审计**：日志与审计 detail 均含 `purged/archived/archivedReports/expiredBindings/swept` 五字段；门槛为「**五者全 0 才 return**」（`if (purged==0 && archived==0 && archivedReports==0 && expiredBindings==0 && swept==0) return;`）。`RetentionChainSchedulerTest` 7 例覆盖：五动作顺序、五计数审计、全 0 不写、**仅第 4 环** / **仅第 5 环**有事仍写、审计失败不抛、**反射断言五个任务方法均无 `@Scheduled`**。
- **全仓仅 1 处 `@Scheduled`**：`grep -rnE "^[[:space:]]*@Scheduled" --include=*.java . | grep -v /target/` ⇒ **命中 1**（仅 `RetentionChainScheduler:97`）；其余 32 处均为注释/字符串。
- **DbIT 独立重跑**：`bash scripts/verify-retention-chain.sh` ⇒ **`RetentionChainDbIT` 9/0/0**（T1–T4 + T5 静态 + T6 语义 + **T7/T8/T9**）。

---

## 4. 真实 HTTP 冒烟（`scripts/smoke-report-review-chain.sh`）

自建 jar（`mvn -o -pl yl-bootstrap -am -DskipTests package`，**从当前已还原源码重建**）+ 真实 MySQL/Redis 拉起 dev 后端（另注：本沙箱环境注入了 `SERVER__PORT=53430`，须显式 `--server.port=8080` 覆盖，否则撞 harness 自身端口）。

**结果：通过 119 项 / 失败 0 项 / 跳过 0 项**（带 `MYSQL_BIN+MYSQL_PASSWORD`、`SECOND_VERIFY_DEV_CODE=246810`、`EXPORT_DIR=…/p27-smoke/export`，故 **B9 CSV 段未被跳过**）。

**★ 自证断言引擎**（回应本项目「只打印不比较 / 失败硬编码 ok:true」假绿史）：脚本有真比较引擎，非驱动式：
- 计数与判定：`pass()/fail()/skip()` 维护 `PASS/FAIL/SKIP`；`check() { if [ "$2" = "$3" ]; then pass; else fail; fi; }`；`check_ge()` 数值下界比较。
- 拒绝口径不用 HTTP 状态码（本项目业务失败恒 200）：`code_of()` 取 `R.code` 判定；二进制端点另验 **PDF 魔数** `is_pdf_file()`（`head -c 4 == %PDF`）、CSV **BOM 魔数**（`efbbbf`）+ 表头 `case` 前缀匹配。
- 关键通过项（直接证明 ObjectKeyResolver 改造后两写点行为不变）：`下载 PDF 魔数 = %PDF`、`B9 存储键名口径 = export/EX2026…csv`、`B9 落库机构锚 org_id 非空`、`B9 CSV 产物魔数 = efbbbf`、`B9 CSV 首行表头`、`B9 CSV 公式注入转义`；跨机构/无归属下载被业务码 `10004` 拒绝且**未泄露 PDF 字节**。
- **落盘人工复核**：`<EXPORT_DIR>/export/EX20261010010121507392.csv`、`<REPORT_PDF_DIR>/report/RP69e6bdd1-818.pdf` 均真实存在 ⇒ 复用同一 `ObjectKeyResolver` 的两写点键名口径（`report/`、`export/`）**未漂移**。

---

## 5. 安全红线复核（设计 §4 / 核查 §4）

| 红线 | 结论 | 证据 |
|---|---|---|
| ① 绝不先删文件后删行 | **PASS** | 动作**只移文件**（`Files.move`）+ D1-B 仅 `export_task.status→4`（非删行）；`eval_report` 行不触碰 |
| ② 必须先反查 DB 确认无引用 | **PASS** | 在用集 = 推导键 ∪ 非空 `*cos_key`（两处 UNION，见 Mapper）；孤儿 = 键不在集合内 |
| ③ 遍历限定在存储根内、禁通配删除 | **PASS** | 清扫器**只列一层**（`Files.newDirectoryStream(baseDir/<前缀>)`）、**全仓无 `Files.walk`**、无通配删除；M6/M7 突变证明该约束被断言钉死 |
| ④ 保留可还原窗口 | **PASS** | `Files.move` 至同盘 `<baseDir>/.trash/<yyyyMMdd>/<前缀>/`；M5 突变证明「`.trash` 下可寻回」断言存在 |
| `resolve()` 越界确实抛 | **PASS** | jshell 实跑（`ObjectKeyResolver` 编译产物）：键 `../../etc/passwd` / `report/../../etc/passwd` / `..` / `../` **全部抛 `BizException`**，`code=10001`（== `PARAM_INVALID`）、`msg` 以 `非法存储路径: ` 开头；正常键 `report/RP-1.pdf` 落于 base 内 |
| 根外文件/符号链接目标不受影响 | **PASS（条件性）** | 变量项：符号链接分支本地**恒跳过**（平台限制，见 §2 / R5）；即便本机降级为普通文件，`Files.move` 只搬文件本身、不跟随链接 |

**回退子目录与改造前「逐字一致」的论证（★ 重要限制见 §7）**：`baseDir()` 空配置回退 `Paths.get(java.io.tmpdir, "yl-reports"|"yl-exports").toAbsolutePath().normalize()`，jshell 实证 `fallbackA=<tmp>/yl-reports`、`fallbackB=<tmp>/yl-exports`，与 `application.yml`（`REPORT_PDF_DIR`/`EXPORT_DIR` 留空即回退）及地基核查 §2.1 **一致**。

---

## 6. 缺陷与残留清单（分级 + 建议编号/措辞）

| 级别 | 编号（建议） | 内容 | 建议处置 |
|---|---|---|---|
| 低 | **P2-7-R5** | **符号链接「天然跳过」分支在本机（Windows）无法构造输入、本地门禁恒跳过**：`createSymbolicLink` 假成功（落 0 字节普通文件、`isSymbolicLink` 恒 false）——已独立复现。该分支无本地覆盖，仅由支持符号链接的 Linux CI 覆盖。 | 保持残留并**显式登记**（与设计 §4 平台限制呼应）；建议文案：「`OrphanFileSweepTask` 符号链接跳过分支本地不可构造输入（Windows 假成功降级），本地门禁恒跳过，由 Linux CI 覆盖」。 |
| 低 | **P2-7-R6** | **`RetentionReferenceMapper.markExpired`/`countExpired` 的 `expire_at IS NOT NULL` 行为层不可区分且无静态守卫**：摘除后 DbIT 仍 9/0/0（三值逻辑 UNKNOWN）；且静态守卫 `RetentionChainDbIT.T5` **只覆盖 4 个既有 Mapper**，未含本 Mapper（D-5 的 T5 是同类条件静态守卫先例）。 | 建议比照 T5 补一条静态断言：`sqlOf(RetentionReferenceMapper.class,"markExpired",LocalDateTime.class,int.class).contains("expire_at IS NOT NULL")`；在此之前登记残留。 |
| 低（数据卫生） | （可选登记） | T7 的 `markExpired` 是**全表 UPDATE**（非仅本用例 id 段），且 `@AfterEach` 只清 `[T7_LO,T9_HI]`；若 dev 库存在其它到期行会被永久置 `status=4` 而不复原。非缺陷（动作本身即全局到期标记），但测试**非完全自清理**。 | 提示：可在用例前快照/用例后复位非本段行，或在注释中声明该副作用。 |
| 低（文档） | （可选登记） | 设计 §3.1 注释称 `resolve()` 越界抛 `IllegalArgumentException`，实现抛 `BizException(PARAM_INVALID)` —— 措辞不一致。 | 统一文档措辞（实现为准）；行为影响见 §7（越界分支不可达）。 |

> 说明：以上均**非功能性缺陷**，不阻断 P2-7 收口；R5/R6 为「覆盖/守卫缺口」类残留。

---

## 7. 无法验证的项（如实列出，未编造）

1. **改造前两处私有 `resolve()` 的原始字节**：`ReportPdfService.java` / `SuperviseExportService.java` **未被 git 跟踪**（`HEAD` 及所有本地/远端分支均无该路径），**无法逐字回溯比对**。以「可验证方式」间接论证行为等价：
   - ① **越界分支在所有调用点不可达** —— `ReportPdfService.read()` 先以 `[A-Za-z0-9_-]{1,64}` 正则校验 `reportNo` 再 `resolve`；`generate()` 键由 DB 的 `reportNo` 推导；`SuperviseExportService` 键由生成式 `taskNo` 推导；清扫器用**常量**前缀 `"report"/"export"`。故异常类型（`IllegalArgumentException` vs `BizException`）**无行为影响**。
   - ② 落盘口径经 **HTTP 冒烟实测**一致（PDF 落 `<root>/report/`、CSV 落 `<root>/export/`）。
   - ③ 回退子目录名与 yml/设计/地基核查一致（`yl-reports`/`yl-exports`，jshell 实证）。
   - **结论：无法回溯验证「逐字一致」；已以行为等价 + 可达性分析间接证实。**
2. **符号链接跳过分支在支持符号链接平台的实际运行**：本机恒跳过（R5），本地无法验证其真实断言行为；已以「若去掉 `NOFOLLOW` 必红」论证该用例有区分力，但未在 Linux 实跑。
3. **未运行完整 `verify-schema.sh` 全量（86 项）**：因其 `DROP DATABASE` 会清空**共享 dev 库**（可能影响并发中的其他校验）。改为**非破坏性**直接执行两条新增 `*cos_key` 断言的同款 SQL（结果逐字符合），并静态确认 `check()` 真比较、`EXPECTED_TOTAL_ASSERTIONS=86` 自检在位。

---

## 8. 卫生确认

- 突变涉及文件**全部逐字节还原**：7 个文件 md5 与开工基线 `diff` 为空（见 §1）。
- **未修改任何既有断言**（门禁只升不降）。
- 冒烟所用联调数据由脚本自清理（评估单 0 行）；自建后端实例已停，**8080 已释放**、无 java 残留。
- 仓内**未留探针文件**（jshell 探针置于 `/tmp`，已核 `find` 无 `p27-qa-*`）。
