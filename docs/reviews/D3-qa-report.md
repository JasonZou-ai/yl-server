# D-3 报表与监管域后端 · 独立 QA 验证报告

> 角色：QA 工程师（严过关）｜类型：**独立证伪式验证**（源码非我所写、非团队我写）
> 被测：`yl-server` 报表与监管域 **7 端点**（B6/B7/B8/B9/B10 + R6×2），跨 4 模块（evaluation / account / report / supervise）+ 2 处数据域登记修正 + `export_task.org_id` DDL
> 基线：`openapi/yl-api.yaml` v1.1.1 ｜ 设计：`docs/design/D3-report-supervise-domain-design.md` ｜ 地基：`docs/reviews/D3-foundation-check.md` ｜ 工程师验收清单：`docs/reviews/D3-e2e-checklist.md`
> 日期：2026-09-29（随做随写，增量落盘） ｜ 环境：Windows + MySQL 8.0.46（`C:\mysql`）+ Redis（`C:\redis`）+ 8080

---

## TL;DR

**放行** —— 四门禁独立复跑全绿（`mvn -o verify` **185/0/0/0**、`verify-schema.sh` **83/0**、`verify-openapi.sh` **8/8**、冒烟 **79/0/0**）；突变 5 条（M1~M4 全红 + M5 口径校验）均符合预期（守卫具真实区分力）；10 项自行证伪全部通过；**未发现源码缺陷**（源码缺陷 0 条）。

一句话理由：**最关键的两条反向证伪都实测成立** —— ① 未认证 = `HTTP 401 + code=10002`、已认证无权 = `HTTP 200 + code=20003`（两条拒绝路径不重叠，CR-M2-007 修复未吞第二闸）；② 从 `SELF_DENY_TABLES`/`ORG_COLUMN` 摘条目、从控制器摘注解、从契约测试摘登记，**四处突变全部变红**（门禁不是"看着绿"，是真有区分力）。

- 缺陷数：**源码 0 条**；**测试脚本 1 条（低危，QA 已修复并复跑验证）**
- 突变测试：**M1~M4 全红 + M5 口径符合**（全部已还原，终态 `mvn -o verify` 复绿）
- 全量回归：`mvn -o verify` BUILD SUCCESS（185/0/0/0）、`verify-schema.sh` 83/0、`verify-openapi.sh` 8/8（impl 33/planned 10/drift 0）、HTTP 冒烟 79/0/0
- 未覆盖：见 §5（诚实声明）

---

## 0. 被测环境与"新 jar"证据（本项目踩过的坑：旧进程锁 jar → 假失败）

| 项 | 值 |
|---|---|
| 日期 | 2026-09-29 |
| OS / JDK / Maven | Windows + JDK **17.0.20.1** + Maven **3.9.16** |
| MySQL | 8.0.46，`C:\mysql`，`127.0.0.1:3306`（root / `Mysql8@2026#Dev`），库 `yl_evaluation` |
| Redis | `C:\redis`，`127.0.0.1:6379`，**无密码**（启动须 `REDIS_PASSWORD=''`） |
| 8080 | 起服务前 `netstat -ano \| grep :8080` **为空**（无实例）；故 `mvn verify` 一次成功，**未复现** D-2 的 repackage 假失败 |

启动方式（sandbox 静默丢弃 `env -u …`，故用 `unset` + 显式 launcher；`java -jar` 秒退，故用 `-cp … JarLauncher`）：
```sh
unset SERVER__PORT
export DB_PASSWORD='Mysql8@2026#Dev' REDIS_PASSWORD='' SECOND_VERIFY_DEV_CODE=246810 \
       SENSITIVE_FIELD_KEY="$(head -c 32 /dev/urandom | base64 -w0)" \
       SENSITIVE_FIELD_HASH_KEY="$(head -c 32 /dev/urandom | base64 -w0)" \
       EXPORT_DIR="$PWD/target/smoke-export"
java -cp yl-bootstrap/target/yl-bootstrap.jar \
     org.springframework.boot.loader.launch.JarLauncher \
     --spring.profiles.active=dev --server.port=8080
```

**"跑的是新 jar"证据** |

| 证据 | 结果 |
|---|---|
| 进程启动日志 | `Tomcat started on port 8080` / `Started YlApplication in 11.211 seconds`（20:08:13） |
| jar 新鲜度 | `find yl-*/src -name '*.java' -newer yl-bootstrap/target/yl-bootstrap.jar` → **空**（无源码晚于 jar） |
| jar 含 D-3 类 | `BOOT-INF/lib/yl-module-{report,supervise}*.jar` 内解出 `SuperviseExportController`/`ExportCsvWriter`/`SuperviseReportController` 等 class |

> curl 一律加 `--noproxy '*'`；令牌/密钥不落报告。

---

## 1. 阶段 A —— 四门禁独立复跑

> 全部由 QA 亲自执行（`mvn -o -q spotless:apply` 先行，随后 `mvn -o verify`；进入前已确认 8080 无实例）。

### A1. `mvn -o verify`（单测 + Checkstyle + SpotBugs）

命令：
```sh
mvn -o -q spotless:apply          # 先落地格式（Spotless 拦 Javadoc 折行/行尾）
mvn -o verify                     # → target/qa-verify-round1.log
```

原始输出摘要（`BUILD SUCCESS`，耗时 01:46 min）：
```
[INFO] You have 0 Checkstyle violations.      （×14 模块）
[INFO] BugInstance size is 0
[INFO] Error size is 0
[INFO] BUILD SUCCESS
```
单测汇总（自日志 `Tests run` 行聚合）：

| 指标 | 实测 |
|---|---|
| Total tests | **185** |
| failures | **0** |
| errors | **0** |
| skipped | **0** |
| Checkstyle | **0 违规 × 14 模块** |
| SpotBugs | **BugInstance 0 / Error 0** |
| 结论 | **BUILD SUCCESS** ✓ |

> 说明：verify 前 `netstat -ano | grep :8080` 为空（无实例锁 fat jar）；本次 verify **一次成功**，未复现 D-2 的 repackage 假失败。

### A2. `verify-schema.sh`

```sh
MYSQL_BIN=/c/mysql/bin/mysql.exe MYSQL_PASSWORD='Mysql8@2026#Dev' bash scripts/verify-schema.sh
```
原始输出尾部：
```
 结果：通过 83 项，失败 0 项，待批准守卫 0 项（合计 83 项）
已保留库 yl_evaluation（清理方式：bash scripts/verify-schema.sh --drop）
```
判定：**83 / 0 / 0** ✓（脚本会 DROP 并重建 `yl_evaluation`，属预期；重建后由 01~04 init 重新播种。）

### A3. `verify-openapi.sh`

```sh
bash scripts/verify-openapi.sh
```
原始输出尾部：
```
契约操作总数：43（implemented 33 / planned 10）
[5/5] 漂移：契约 x-status=implemented ↔ 控制器端点（双向核对）
  ✓ 控制器端点数 = 33
  ✓ 契约缺登的已实现端点（错误路径/漏登）条数 = 0
  ✓ 契约多登的 implemented 端点（未实现却标 implemented）条数 = 0
 契约门禁结果：通过 8 项，失败 0 项（契约操作 43，其中 implemented 33）
```
判定：**8/8；implemented 33 / planned 10；双向漂移 0** ✓

### A4. `smoke-report-review-chain.sh`

起后端（逐字照抄，绕开三个坑）：
```sh
unset SERVER__PORT
export DB_PASSWORD='Mysql8@2026#Dev' REDIS_PASSWORD='' SECOND_VERIFY_DEV_CODE=246810 \
       SENSITIVE_FIELD_KEY="$(head -c 32 /dev/urandom | base64 -w0)" \
       SENSITIVE_FIELD_HASH_KEY="$(head -c 32 /dev/urandom | base64 -w0)" \
       EXPORT_DIR="$PWD/target/smoke-export"
java -cp yl-bootstrap/target/yl-bootstrap.jar \
     org.springframework.boot.loader.launch.JarLauncher \
     --spring.profiles.active=dev --server.port=8080
```
启动日志证据：`Tomcat started on port 8080` / `Started YlApplication in 11.211 seconds`（2026-09-29 20:08:13）。
jar 新鲜度：`yl-bootstrap.jar` mtime 晚于全部 `*.java`（`find *.java -newer jar` 为空）；jar 内 `BOOT-INF/lib/yl-module-{report,supervise,evaluation,account}*.jar` 均含 D-3 类。

冒烟命令：
```sh
MYSQL_BIN=/c/mysql/bin/mysql.exe MYSQL_PASSWORD='Mysql8@2026#Dev' \
  SECOND_VERIFY_DEV_CODE=246810 EXPORT_DIR="$PWD/target/smoke-export" \
  bash scripts/smoke-report-review-chain.sh
```
原始结果：
```
 结果：通过 79 项，失败 0 项，跳过 0 项
```
判定：**79 / 0 / 0**，与工程师自报（79）一致 ✓。`[8/8]` 段 26 条全绿（B6/B7/B8/B9/B10/R6/未认证 401），含：
```
  ✓ B8 验真返回最小面（无 name/idCard/phone）
  ✓ B9 CSV 产物魔数（UTF-8 BOM efbbbf） = efbbbf
  ✓ B9 CSV 首行表头（评估单号,能力等级,老人姓名,...）
  ✓ B9 CSV 公式注入转义（单元格以 ' 前缀，§2.4）
  ✓ B10 同月重放不新增行（uk_month_org） = 1
  ✓ D-3 未携带凭据访问受保护端点 HTTP 状态码（CR-M2-007） = 401
  ✓ D-3 未携带凭据响应体业务码 R.code=10002 = 10002
```

### A5. 四门禁汇总

| # | 门禁 | 期望 | 实测 | 判定 |
|---|---|---|---|---|
| 1 | `mvn -o verify` | 185 / 0 / 0；CS 0×14；SB 0 | **185 / 0 / 0 / 0 skipped**；Checkstyle **0×14**；SpotBugs **0/0** | ✓ |
| 2 | `verify-schema.sh` | 83 / 0 | **83 / 0 / 0** | ✓ |
| 3 | `verify-openapi.sh` | 8/8；impl 33 / planned 10；drift 0 | **8/8；33/10；drift 0** | ✓ |
| 4 | `smoke-report-review-chain.sh` | ≥53 | **79 / 0 / 0** | ✓ |

### A6. 突变全部还原后的终态复跑（必做）

5 条突变全部还原（含 3 处源码 + 3 处复原后回归）后，再跑一次全量：
```sh
mvn -o verify     # → target/qa-verify-final.log
```
```
[INFO] BUILD SUCCESS
TOTAL tests=185 failures=0 errors=0 skipped=0
```
判定：**回到全绿（185/0/0）** ✓ —— 还原不残留、门禁未被突变污染。

> 另：修复冒烟脚本后（§4.2）**复跑冒烟**（`target/qa-smoke2.log`）→ **79 / 0 / 0**，`B9 CSV 公式注入转义`/`产物魔数`/`首行表头` 均绿，**无回归** ✓。

---

## 2. 阶段 B —— 突变矩阵（5 条）

> 方法：临时改源码 → 跑目标测试**必须变红并记录红在哪条断言** → **立刻还原** → 终态 `mvn -o verify` 复跑转绿。
> 每条突变均"观察完红色即还原"，从未带着未还原的突变跑长命令（吸取上一轮教训②）。
> 目标测试运行方式（跳过格式/静态检查噪声，与 D-2 一致）：
> ```sh
> mvn -o test -Dtest=<TestClass> -Dsurefire.failIfNoSpecifiedTests=false \
>     -Dspotless.check.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true
> ```

| # | 突变动作 | 是否变红 | 红在哪条断言（原始证据） | 是否已还原 |
|---|---|---|---|---|
| **M1** | `SELF_DENY_TABLES` 摘除 `"supervise_report"` | ✅ **红** | `DataScopePermissionHandlerTest#selfScopeFailsClosedOnSuperviseReport`（**L103**）；`NullPointer ... "expr" is null` —— 即"未登记 → 返回 null（放行）"，正是被测缺陷语义 | ✅ 已还原（L152 复原） |
| **M2** | `ORG_COLUMN` 摘除 `Map.entry("export_task","org_id")` | ✅ **红** | `DataScopePermissionHandlerTest#orgScopeFiltersExportTaskByOrgId`（**L117**）；`NullPointer ... "expr" is null`（同源） | ✅ 已还原（L107 复原） |
| **M3** | 摘掉 `SuperviseExportController` 的 `@RequiresPermission(PermissionCode.DATA_EXPORT)`（L43） | ✅ **红** | `ControllerAuthorizationCoverageTest#everyHandlerEndpointDeclaresPermission`（**L91**）；断言消息含裸奔端点 **`SuperviseExportController#create`** | ✅ 已还原（L43 复原） |
| **M4** | `OpenApiContractTest.CONTROLLERS` 摘除 `SuperviseExportController.class` | ✅ **红** | `OpenApiContractTest#declaredImplementedOperationsMatchControllersExactly`（**L112**）；`契约多：[POST /api/v1/supervise/exports]`（双登方向） | ✅ 已还原（L76 复原） |
| **M5** | 登录态缺失必 401（**口径校验，非改码**） | ✅ **符合口径** | 无 `Authorization` → `GET /evaluation/tasks` 得 **HTTP=401** + `code=10002`；**反向证伪**：`assessor_dev`（无 `data:export`）→ `POST /supervise/exports` 得 **HTTP=200** + `code=20003` | N/A（未改码） |

**M5 原始输出（★反向证伪，证明两条拒绝路径不重叠、未被吞并）**：
```
===== M5a: no Authorization -> GET /evaluation/tasks =====
HTTP=401
BODY={"code":10002,"message":"未登录或登录已过期","traceId":"f84dfa35…","success":false}

===== M5b: assessor (no data:export) -> POST /supervise/exports =====
HTTP=200
BODY={"code":20003,"message":"角色无权执行该操作","traceId":"ac918aa3…","success":false}
```
> 判定：**第一闸（框架层，未认证）= 401+10002；第二闸（权限切面，已认证无权）= 200+20003**，两者泾渭分明。CR-M2-007 的 401 修复**没有**把第二闸吞成 403。
> 注：M5b 用 `data:export`（仅 ORG_ADMIN/SUPERVISOR）作受限端点，评估员令牌调它必被第二闸拒 —— 与 D-2（`data:reveal`）同一判据。

**突变全还原后终态复跑**：见 §A6 → **185/0/0，BUILD SUCCESS**。

---

## 3. 阶段 C —— 10 项自行证伪

> 全部由 QA 亲自设计/执行，**不复述工程师跑过的东西**；能上真实后端的一律上后端，不能的给出机制级证据并显式声明证据层级。

### C1. B9 CSV 真实魔数 —— ✅ 通过

真跑一次导出（`orgadmin_dev` + 二次验证令牌），对产物 `od -A x -t x1z -N 16`：
```
000000 ef bb bf e8 af 84 e4 bc b0 e5 8d 95 e5 8f b7 2c  >...............,<
```
前 3 字节 = **`ef bb bf`（UTF-8 BOM）** ✓。首行（去掉 BOM）为
`评估单号,能力等级,老人姓名,身份证号,手机号,评估状态,所属机构,出具时间` ✓。
行分隔字节：`od` 全文含 **`0d 0a`（CRLF）× 2**（表头行 + 数据行）✓（`cat -A` 不显 `^M` 系工具显示差异，`od` 为准）。

### C2. 公式注入转义端到端 + 脏数据风险 —— ✅ 转义通过 / ⚠️ **发现脚本级脏数据风险（1 条低危）**

**转义（真跑）**：把一张本机构评估单 `eval_order.order_no` 临时改为 `=1+1` → 导出 → 产物数据行为
```
'=1+1,GRADE_INTACT,,,,PUBLISHED,1,2026-09-29T20:13:12
```
即单元格首字符 `=` → 前置 `'` ✓（`grep -c "'=1+1"` = 1）。核对后立即还原（`order_no` 复原为 `EO5adf99b9-9c7`）。
单测 `ExportCsvWriterTest#escapesFormulaInjectionPrefixes` / `#injectionSampleCellIsNeutralizedInOutput` 亦钉死该口径。

**★脏数据风险（`smoke-report-review-chain.sh`）**：脚本在 **L542** `UPDATE eval_order SET order_no='=1+1'`，直到 **L587** 才还原；**全脚本无 `trap`（`grep -n trap` 无命中）**。
→ **若脚本在此区间被中断（如上一轮 QA 遭遇的 502/断网、Ctrl-C、`set -e` 之外的杀进程），`eval_order.order_no` 会永久停在 `=1+1`**，且清理段（L629+）只删 `id IN ($ORDER,…)` 的行、**不会**修回值。
→ 判定：**测试脚本健壮性缺陷（低危）**——不污染源码、不影响门禁正确性，但会在联调库留下脏单号，需人工发现。
→ **处置**：QA 按职责**已自行修复**——新增 `restore_injection_sample()` + `trap restore_injection_sample EXIT INT TERM`（见 §4.2），并**复跑冒烟确认 79/0/0 无回归**（`target/qa-smoke2.log`）。

### C3. B8 最小返回面（无 PII）—— ✅ 通过

真跑（public，无令牌）：
| 场景 | HTTP | 响应体 | 判定 |
|---|---|---|---|
| 不存在 code（`RP-QA-NOTEXIST-*`） | 200 | `{"code":0,…,"data":{"valid":false}}` | **200 + code=0 + valid=false**（非 404/500）；不泄露存在性 ✓ |
| 有效已发布报告（真链生成+发布后验真） | 200 | `{"code":0,…,"data":{"valid":true,"gradeCode":"GRADE_INTACT","publishedAt":"2026-09-29T20:13:12"}}` | **仅 3 字段**，**无** `name`/`idCard`/`phone` ✓ |

旁证：单测 `ReportVerifyServiceTest#responseExposesOnlyThreeFields` 用反射断言 DTO 字段集 == `{valid, gradeCode, publishedAt}`。

### C4. `export_task.org_id` NULL 语义 —— ✅ 机制级通过（无 HTTP 读端点，见声明）

手工插一行 `org_id IS NULL` 的 `export_task` 后：
```
全表 COUNT(*)                                   = 3   （含 NULL 行）
scope=2 谓词  WHERE org_id IN (1) 命中          = 2   （NULL 行不参与 IN）
NULL 行 AND org_id IN (1) 命中                  = 0   ← "NULL 失败即拒" 成立
scope=3/4（无谓词）该行命中                      = 1   ← 全量 scope 可见
```
核对后已清理该行。
> **证据层级声明（诚实）**：D-3 **不存在** `export_task` 的 HTTP list/detail 端点，故无法做"机构管理员账号 HTTP 拉列表看不见 NULL 行"的端到端断言。本项为**机制级**证据：① 第三闸产出的谓词形态由单测 `orgScopeFiltersExportTaskByOrgId`（`(export_task.org_id IN (1))`）钉死；② ①的谓词在真实 MySQL 下对 NULL 行确为排除。两者串起来即证明"NULL 失败即拒"。**该 NULL 行不可见的 HTTP 行为本身未直接观测**。

### C5. `export_task`/`supervise_report` 是否存在可跨机构利用的读路径 —— ✅ 未发现红线

- 全仓对这两表**无任何手写 SQL 直读**（`grep` report/supervise 模块 `FROM export_task|supervise_report` → 0 命中）。
- B10 的"同月同机构"判定 `SuperviseReportService#findExisting` 走 `reportMapper.selectOne(QueryWrapper)` → **经 MyBatis 拦截器（第三闸）**，`supervise_report` 已登记 `ORG_COLUMN`（scope=2 追加 `org_id IN (orgIds)`），叠加显式 `org_id = 登录态机构`，无跨机构面。
- B9/B10 的跨域取数读的是 `eval_order/eval_report/elder`（**均已登记**），非这两表。
- **无缓存/文件/三方直读**（COS 无实现、无本地缓存读这两表）。
→ 判定：**未发现"不经 Mapper 的直读"**（红线未触发）。

### C6. 边界约束（设计 §6.2）—— ✅ 通过

- **全仓唯一调度仍在 `RetentionChainScheduler`**：`grep -rn @Scheduled yl-*/src/main` → **仅 1 处**（`RetentionChainScheduler.java:73`），D-3 **零新增 `@Scheduled`** ✓
- **D-3 零文件删除逻辑**：`grep Files.delete|deleteIfExists|.delete()` 于 evaluation/report/supervise 三模块 `src/main` → **0 命中** ✓

### C7. 裁决 3 独立核查（`supervise_report` 是否被归档覆盖）—— ✅ 工程师结论属实

`RetentionChainScheduler#runRetentionChain` 只调 `thirdPartyTask.purgeExpiredBindings()`（`sys_user_third_party`）+ `archiveTask.archiveExpiredOrders()`（`eval_order`）。全仓 `grep supervise_report` 在 retention/archiver **0 命中**。
→ **`supervise_report` 确未被保留期链路覆盖**；D-3 只写 `retain_until`（值），归档/清理排 P2-7/D-5 —— **与工程师自报一致，证实** ✓

### C8. 裁决 2 残留口径（XLSX/PDF/STAT）—— ✅ 通过

真跑（带二次验证令牌，逐一换新一次性令牌；注：首次因脚本把 `token` 误取为 `accessToken` 导致空令牌→10008，**系 QA 脚本自身错误，非源码问题**，修正后）：
```
PDF  (exportType=EVAL_LIST, format=PDF ) → HTTP=200 {"code":10001,"message":"暂不支持的导出格式（仅 CSV）: PDF"}
STAT (exportType=STAT,      format=CSV ) → HTTP=200 {"code":10001,"message":"暂不支持的导出类型（仅 EVAL_LIST）: STAT"}
XLSX (exportType=EVAL_LIST, format=XLSX) → HTTP=200 {"code":10001,"message":"暂不支持的导出格式（仅 CSV）: XLSX"}
```
→ 三者**均为 `10001`**（而非 500 或其他码）✓

### C9. `gb_rule_mapping` 是有意放行的字典表 —— ✅ 通过

`grep gb_rule_mapping DataScopePermissionHandler.java` → **仅 javadoc 提及**，**未**出现在 `ORG_COLUMN`/`SELF_DENY_TABLES`/`SELF_COLUMN` 任一映射。→ 字典表放行，B7 对所有账号可读（与 `eval_item` 同口径）✓

### C10. B10 幂等 —— ✅ 通过

冒烟实测：首次上报 `code=0` 且 `supervise_report.retain_until IS NOT NULL`；同月同机构重放 `code=0` 且**行数不变**（`uk_month_org` 命中 → 走 `findExisting` update，不新增）✓
> 附带风险（低）：实现为 `SELECT → INSERT/UPDATE`（非原子 upsert），**顺序**重放安全；仅极端**并发**同月同机构双写才可能撞 `uk_month_org` 抛 `DuplicateKeyException`（→ 500）。D-3 未强制并发幂等，登记为低危残留，非本轮验收项。

---

## 3.5 阶段 C 汇总

| # | 项 | 结论 |
|---|---|---|
| C1 | CSV 真实魔数（BOM+表头+CRLF） | ✅ |
| C2 | 公式注入转义 | ✅；⚠️ 冒烟脚本无 trap → 中断留脏（测试脚本低危缺陷） |
| C3 | B8 最小返回面 | ✅ |
| C4 | `export_task.org_id` NULL 语义 | ✅（机制级；无 HTTP 读端点，已声明） |
| C5 | 跨机构读路径 | ✅ 无红线 |
| C6 | 边界约束（无删除/无 @Scheduled） | ✅ |
| C7 | 裁决 3（supervise_report 未覆盖） | ✅ 证实 |
| C8 | XLSX/PDF/STAT → 10001 | ✅ |
| C9 | gb_rule_mapping 字典表 | ✅ |
| C10 | B10 幂等 | ✅（并发为低危残留） |

---

## 4. 缺陷清单

### 4.1 源码缺陷：**0 条**

四门禁 + 5 突变 + 10 证伪均未暴露任何源码缺陷（无 bug 需要回派工程师）。

### 4.2 测试脚本缺陷：**1 条（低危，QA 已修复）**

| 项 | 内容 |
|---|---|
| 文件 | `scripts/smoke-report-review-chain.sh` |
| 行号 | 注入应用 **L542**、还原 **L587**（修复前） |
| 复现 | `MYSQL_BIN=… MYSQL_PASSWORD=… SECOND_VERIFY_DEV_CODE=246810 EXPORT_DIR=… bash scripts/smoke-report-review-chain.sh`；在 L542 与 L587 之间**中断进程**（断网 502 / Ctrl-C / TERM） |
| 期望 | 中断后 `eval_order.order_no` 应被还原为原值 |
| 实际 | **全脚本无 `trap`** → 中断后 `eval_order.order_no` **永久停在 `=1+1`**，且清理段只删行不修值 → 联调库留脏单号（正是上一轮 QA 遭遇断网的场景） |
| 判定 | 测试脚本健壮性缺陷（**非源码缺陷**）；同一安全属性（注入转义）本身**已验证正确**（C2 转义通过） |
| **QA 修复** | 新增 `restore_injection_sample()` + `trap restore_injection_sample EXIT INT TERM`（L479-488），登记注入单号 `B9_INJ_ORDER`，还原后清空（幂等 no-op）；`sh -n` 语法通过；**修复后复跑冒烟仍 79/0/0**（见 §6） |

---

## 5. 遗留风险 / 未覆盖面（诚实声明）

以下**本次 QA 未直接验证**或属设计**已登记残留**，不应被视为"已验证通过"：

1. **`export_task`/`supervise_report` 无 HTTP 读端点** → 跨机构 / NULL 可见性只能做**机制级**验证（C4/C5），无法做端到端 HTTP 断言。
2. **B10 并发幂等**：实现为 `SELECT → INSERT/UPDATE`（非原子 upsert），**顺序**重放安全（冒烟实证）；**极端并发**同月同机构双写才可能撞 `uk_month_org` 抛异常（→ 500）。未做并发压测。
3. **B9 落盘先于落库、无事务**：先 `write(key,csv)` 再 `export_taskMapper.insert`；落库失败会留**孤儿 CSV 文件**（属`export_task.expire_at`/孤儿文件清理 P2-7 范围）。
4. **格式/类型残留（R2/R6）**：`format ∈ {XLSX,PDF}`、`exportType ∈ {REPORT,STAT}` 一律 `10001`，未实现；异步大导出（>10000 → COS，R1）无实现。
5. **保留期链路残留（R3/R4）**：`export_task.expire_at` 清理（P2-7）与 `supervise_report.retain_until` 归档（D-5）均未接线；`supervise_report` 未被 `RetentionChainScheduler` 覆盖（C7 已证实）。
6. **B10 未加 `@Audit`**（设计 §2.2 记为"可选"），本期无监管上报留痕。
7. **角色数据域落点**：D-3 端点仅真实验证了 `assessor_dev`(ASSESSOR/scope=2) 与 `orgadmin_dev`(ORG_ADMIN/scope=2) 两条令牌；**SUPERVISOR(scope=4)/ELDER(1)/FAMILY(5) 未做真实 HTTP E2E**（其落点依赖 `DataScopePermissionHandlerTest` 的 SQL 片段断言）。
8. **B8 限频**：依赖既有 `RateLimitInterceptor`（匿名 30 次/60s），**未做枚举压测**。
9. **B8 对"已作废(status=2)"报告**：单测覆盖 `invalidWhenNotPublished`（status=0），**未单独验 status=2** 的 `valid=false`（语义上同属 valid=false 分支）。
10. **`canonicalContent` 一致性**：以"逐字段文本比对 + 单测"钉死（两处 `String.join("|", 8 字段)` 逐字节相同），未做"改动 order 后验真转 false"的端到端篡改演练。

---

## 6. 与工程师自报结论不一致之处

**核心数字与结论一致**：`mvn -o verify` = 185/0/0（工程师 185）、`verify-schema.sh` = 83/0（工程师 83）、`verify-openapi.sh` = 8/8 且 implemented 33 / planned 10（工程师同）、冒烟 = 79/0/0（工程师 79）；工程师 checklist §6 自检的突变 M1~M5 结论**均可独立复现**。

**唯一差异（不构成结论冲突）**：
- 工程师 `D3-e2e-checklist.md` §5 #7 描述了"注入样本改法"（把真实评估单编号临时改为 `=1+1`），但**未披露**"脚本无 `trap` → 中断会留脏单号"的风险。QA 独立发现该风险（C2），并**已修复脚本 + 复跑验证**（§4.2）。
- 该差异属**发现增补**，不改写工程师任何已自报的通过结论。

---

## 7. 路由判定

**`NoOne`（放行）** —— 源码 **0 缺陷**；测试代码（含门禁/数据域单测）**具真实区分力**（4 处突变全红）。
> 说明：1 条**测试脚本**缺陷（冒烟注入样本无 trap）按 QA 职责**已自行修复**并复跑确认，不触发回派工程师分支。

---

## 附：关键命令清单（可复现）

```sh
# 门禁前置：先停运行实例 + spotless
netstat -ano | grep :8080                     # 有 → taskkill //F //PID <pid>
mvn -o -q spotless:apply
mvn -o verify                                 # 185/0/0；CS 0×14；SB 0
MYSQL_BIN=/c/mysql/bin/mysql.exe MYSQL_PASSWORD='Mysql8@2026#Dev' bash scripts/verify-schema.sh   # 83/0
bash scripts/verify-openapi.sh                # 8/8；impl 33/planned 10/drift 0

# 起后端（逐字照抄）
unset SERVER__PORT
export DB_PASSWORD='Mysql8@2026#Dev' REDIS_PASSWORD='' SECOND_VERIFY_DEV_CODE=246810 \
       SENSITIVE_FIELD_KEY="$(head -c 32 /dev/urandom | base64 -w0)" \
       SENSITIVE_FIELD_HASH_KEY="$(head -c 32 /dev/urandom | base64 -w0)" \
       EXPORT_DIR="$PWD/target/smoke-export"
java -cp yl-bootstrap/target/yl-bootstrap.jar \
     org.springframework.boot.loader.launch.JarLauncher \
     --spring.profiles.active=dev --server.port=8080

# 冒烟
MYSQL_BIN=/c/mysql/bin/mysql.exe MYSQL_PASSWORD='Mysql8@2026#Dev' \
  SECOND_VERIFY_DEV_CODE=246810 EXPORT_DIR="$PWD/target/smoke-export" \
  bash scripts/smoke-report-review-chain.sh   # 79/0/0

# 突变（改源码后，跑完立即还原）
mvn -o test -Dtest=DataScopePermissionHandlerTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dspotless.check.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true
mvn -o test -Dtest=ControllerAuthorizationCoverageTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dspotless.check.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true
mvn -o test -Dtest=OpenApiContractTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dspotless.check.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true

# 终态复跑
mvn -o verify                                 # BUILD SUCCESS（突变全还原后）
```
> curl 一律加 `--noproxy '*'`；令牌/密钥不落报告。
