# D-3 报表与监管域后端 · 增量设计 + 任务分解

> 角色：架构师（高见远）｜产出类型：**设计 + 裁决 + 任务分解**（不含业务代码；DDL 补丁提案与 mermaid 属设计产物）
> 范围：`yl-server` **7 个 planned 端点**翻旗（B6~B10 五个 + R6 组织管理两个）+ 2 处数据域登记修正（含 1 处 DDL）
> 真源：`docs/reviews/D3-foundation-check.md`（地基核查，已实测）｜`openapi/yl-api.yaml` v1.1.1｜`docker/mysql/init/0*.sql`｜`yl-api`/`yl-module-account` 既有四闸基建
> 节拍：**严格照 `docs/design/D2-archive-domain-design.md` 的结构与粒度打，不重新发明范式**
> 阅读顺序：§1 裁决（可独立抄入 CR 卡/任务卡）→ §2 实现方案与文件列表 → §4 端点契约表 → §6 DDL 与数据域 diff → §7 门禁影响 → §8 任务列表

---

## 0. 事实核对（本设计采信的现状，均已逐文件实读确认）

| 事实 | 位置 | 对本设计的影响 |
|---|---|---|
| D-3 真实范围 = **7 端点**（B6~B10 + R6-1/R6-2），跨 **4 个模块** | `D3-foundation-check.md` §0/§2｜`D1-openapi-contract-delta.md` L108-112 / L199 | 落点：`evaluation(B6/B7/B8)` / `account(R6×2)` / `report(B9)` / `supervise(B10)` |
| `yl-module-report`、`yl-module-supervise` **仅 `pom.xml`，`src/` 下 0 个 java** | `ls yl-modules/yl-module-report` / `...-supervise` | 两模块为**空壳**，B9/B10 全新代码，无历史包袱 |
| 两空壳模块 pom **仅依赖 `yl-api`** | `yl-module-report/pom.xml` | 需补 `mybatis-plus-spring-boot3-starter`（BaseMapper/`@Mapper`/`@Select`）+ `spring-boot-starter-test`（test），**同 `yl-module-evaluation` pom 范式** |
| `@MapperScan("cn.yl.**.mapper")` + `scanBasePackages="cn.yl"` | `YlApplication`（D-2 §0 已核） | 新模块的 `mapper`/`@Component` **自动被发现**，无需改扫描 |
| `yl-bootstrap/pom.xml` **已依赖全部模块**（含 report/supervise） | `yl-bootstrap/pom.xml` L29-54 | **无需改聚合依赖** |
| `ExportTask`/`SuperviseReport`/`GbRuleMapping` 实体 + Mapper **均不存在** | 全仓 `find` 0 命中 | 三类实体/Mapper **需新建** |
| `EvalTask` 实体 + `EvalTaskMapper` **已存在**于 evaluation | `.../domain/entity/EvalTask.java` / `.../mapper/EvalTaskMapper.java` | B6 直接复用 |
| `Org` 实体**已存在**于 account；**但 `OrgMapper` 不存在** | `.../account/domain/entity/Org.java`；`find Org*Mapper` → 0 | R6 需**新建 `OrgMapper`** |
| `export_task` 表**无 `org_id`**，仅 `operator_id`(NOT NULL)；**无 `deleted` 列** | `02_schema.sql` L600-619 | 裁决 1-A 补 `org_id`；实体**不映射 `deleted`**（该表无软删） |
| `supervise_report`：`report_month CHAR(7)`、`org_id NOT NULL`、`payload JSON NOT NULL`、`idempotent_key VARCHAR(64) NOT NULL`（唯一 `uk_idem`）、唯一 `uk_month_org`、`retain_until`；**无 `deleted`** | `02_schema.sql` L621-639 | B10 键口径见 §9 |
| `eval_task` 有 `org_id`+`assessor_id`，**已双登记**（`ORG_COLUMN`+`SELF_COLUMN`） | `DataScopePermissionHandler.java` L86/L104 | B6 第三闸**自动收口**，服务层不手写 org 过滤 |
| `export_task` **只有 `SELF_COLUMN(operator_id)`**，**不在 `ORG_COLUMN`** | 同上 L102 | scope=2 **静默放行**（发现 2） |
| `supervise_report` **只在 `ORG_COLUMN`**，不在 `SELF_COLUMN`/`SELF_DENY_TABLES` | 同上 L90 | scope=1/5 **放行**（发现 3，暗区） |
| `gb_rule_mapping` **无任何归属列** → 字典类表（与 `eval_item` 同口径） | `02_schema.sql` L455-466 | B7 **不得**登记进 `ORG_COLUMN`/`SELF_DENY_TABLES`（须显式论证为字典表） |
| **全仓无 Excel 库**（无 POI/EasyExcel）；**COS 只有配置无任何调用实现** | 根 `pom.xml`；`grep CosClient`→0 | 导出复刻 PDF 本地目录范式（`yl.export.dir` + `export/<taskNo>.csv`） |
| PDF 本地落盘范式：`yl.report.pdf.dir` + 键 `report/<reportNo>.pdf`（注释明示"键名与 COS 形态一致，后续只换写入实现"） | `ReportPdfService.java` L44/75/104/112/286-313 | B9 直接复刻该范式 |
| `verify_hash` 计算源 = `eval_order`（`canonicalContent(order)` 8 字段 join → sha256） | `AssessmentReportService.java` L159-195 | **B8 必须落 evaluation 模块**（否则拿不到 `EvalOrder`） |
| `RetentionChainScheduler` = `ThirdPartyRetentionCleanupTask`(sys_user_third_party) + `EvaluationArchiveTask`(eval_order 归档) | `RetentionChainScheduler.java` L53-77；`ExpiredOrderArchiver.java`（javadoc 明示 eval_order） | **均不覆盖 `supervise_report`** → B10 只写 `retain_until` + 登记残留（裁决 3） |
| 业务失败一律 **HTTP 200 + `R.code`**；未认证 = **HTTP 401 + `code=10002`**；仅校验 400 / 未预期 500 改状态码 | `GlobalExceptionHandler#handleBiz`（无 `@ResponseStatus`）；`SecurityConfig` L96-99 + CR-M2-007 | 所有"被拒"断言下沉到 `R.code`；文件端点**另验魔数** |
| `SecurityConfig.filterChain` **已 `permitAll` `/api/v1/reports/verify`** | `SecurityConfig.java` L81 | **B8 无需改 SecurityConfig**（零改动，仅核对） |
| `RateLimitInterceptor` 对 `/api/**` 生效（匿名 30 次/60s per IP） | `WebMvcConfig.java` L22-24；`RateLimitInterceptor.java` L31-51 | B8"须限频"**已被既有拦截器覆盖**，零新增 |
| 两个门禁测试的 `CONTROLLERS` 均为**硬编码 list**（各 8 名）；白名单现 **4 条** | `OpenApiContractTest.java` L55-64；`ControllerAuthorizationCoverageTest.java` L44-53 / L56-61 | 新控制器**必须登记**；B8 白名单**新增第 5 条且禁冗余** |
| `verify-schema.sh`：`EXPECTED_TOTAL_ASSERTIONS=82`；保留字金丝雀 = **1**（唯一命中 `audit_log.sensitive`） | `verify-schema.sh` L479 / L266 | 新增 `org_id`（**非保留字**）→ 金丝雀仍 = 1；断言总数 82→83 |
| `verify-openapi.sh` 漂移双向断言；当前 implemented 26 / planned 17 | `verify-openapi.sh` L99-104/151-189 | 7 个 planned→implemented；漂移双向须 = 0 |
| `ErrorCode.EXPORT_TASK_TOO_LARGE(60001)` **已定义**；无 `SmsCodeService` | `ErrorCode.java` L35 | B9 超限复用 60001；无新增错误码 |
| 权限种子：`data:export`(2012, need_second_verify=1, 授 ORG_ADMIN 3019/SUPERVISOR 3020)、`supervise:report:submit`(2011, 授 3017/3018)、`account:org:manage`(2009,3015)/`:read`(2010,3016)、`evaluation:task:read`(2020,3032/3033/3034)、`rule:view`(2022, 五角色 3036-3040) | `03_seed_rbac.sql` L54-119 | 全部命中现有授权，**零种子改动** |

---

## 1. 裁决记录

### 1.0 已拍板四项（用户 2026-09-28，**不得推翻**，逐条落地口径）

| # | 裁决 | D-3 落地口径（机械可验收） |
|---|---|---|
| **1** | **`export_task` 采纳 A** | `ALTER TABLE export_task ADD COLUMN org_id BIGINT NULL` + 回填（`operator_id`→`sys_org_member`）+ `ORG_COLUMN` 登记 `export_task → org_id`。NULL 行在 scope=2 下因 `IN` 不匹配自然不可见（"NULL 失败即拒"）。与既有 `SELF_COLUMN(operator_id)` **并列共存**（同 `eval_task` 范式）。`DROP COLUMN` 可回退。schema 断言 **82→83** |
| **2** | **导出仅 CSV**（零新依赖） | XLSX/PDF 返 `10001`（登记残留）。CSV 带 **UTF-8 BOM** + **公式注入转义**（单元格以 `= + - @ \t \r` 开头时前缀 `'`）；冒烟含**注入样本断言** |
| **3** | **B10 `retain_until` 只写值** | 已核查：`RetentionChainScheduler`（→ `ThirdPartyRetentionCleanupTask` + `EvaluationArchiveTask`）**不覆盖 `supervise_report`**。D-3 只写 `retain_until` + 登记残留，**不新增 `@Scheduled`**、不扩 D-5 调度边界 |
| **4** | **B8 最小返回面** | 只返契约声明字段 `{valid, gradeCode, publishedAt}`，**绝不返回**姓名/身份证/手机号 |

> 边界约束（写入任务卡）：**D-3 不碰任何文件删除逻辑** —— `export_task.expire_at` 的清理语义与扫描/回收实现**一律排 P2-7**，D-3 只**写入** `expire_at` 值；**全仓唯一调度仍在 `RetentionChainScheduler`**（ADR-0004）。

---

### 1.1 张力一裁决：B9（`POST /supervise/exports`）跨模块取数 —— **采纳 (c)：report 模块自定义只读投影 Mapper**

**结论**：B9 **整体落 `yl-module-report`**（控制器 + 服务 + CSV 渲染 + `ExportTask` 实体/Mapper）；导出内容所需的 evaluation/archive 数据，由 report 模块**自持的「跨域只读投影 Mapper」**（`ExportSourceMapper`，手写 `@Select`，**不含兄弟模块实体依赖**）取回。

**与既有先例的一致性（关键）**

1. **与 D-2 范式一致**：D-2 把 `Elder` 实体 + `ElderController` 放进**域模块** `yl-module-archive`，未丢进公共层。D-3 对齐 —— `export_task` 属 schema 域 7（监管/报表域），其实体/控制器落**域模块** `yl-module-report`。
2. **与「兄弟模块只依赖 `yl-api`」不变量一致**：`yl-module-report/pom.xml` 仍**只依赖 `yl-api`**。(c) **不产生任何编译期模块依赖**（report 不 `import` `cn.yl.modules.evaluation.*` / `...archive.*`），只发出只读 SQL。
3. **与 ADR-0004 不冲突**：ADR-0004 的约束对象是「**引用兄弟模块的类 / Bean**」（e.g. 拿不到对方的 task 类），故编排只能上 `yl-bootstrap`。(c) **不引用兄弟模块的任何类**，只读同一物理库（`yl_evaluation`）内的表 —— 这是**数据**层行为，由**第三闸（数据域拦截器）全局治理**，不是**模块**依赖。故 ADR-0004 字面与实质均未被违反。
4. **与「全表同库」事实一致**：全部表在同一 database（`02_schema.sql` 单库），跨表 JOIN 无跨库成本。

**被否决方案**

- **(a) 编排落 `yl-bootstrap`（ADR-0004 判例）** —— 否决。理由：① 本仓**所有业务控制器均在模块内**，`yl-bootstrap` 现有的跨模块代码是**调度壳**（`RetentionChainScheduler`），**不是** HTTP 控制器；把 `SuperviseExportController` 放进去会让"组合根"承载业务，与既有分层冲突；② 会让 `yl-module-report` 退化为"只有实体"的半空壳，与 D-2"域模块承载完整纵向切片"的范式割裂。
- **(b) 放 `evaluation` 模块** —— 否决。理由：该先例（`care_plan`/报告 PDF 逻辑写在 evaluation）**之所以成立**，是因为 `eval_report`/`care_plan` **本身由 `eval_order` 派生**（evaluation 是本征归属域）；而**导出产物 `export_task` 并非 evaluation 本征**（它是 report 域的持久化对象，只是"内容"来自 evaluation）。把 `/supervise/**` 路由塞进 evaluation 会制造"路径语义 ≠ 模块归属"的长期混乱，并把 evaluation 继续膨胀为万能域。

**风险与缓解**：(c) 的已知失效面 == `care_plan.org_id` 那一类"跨域硬编码了不存在的列 → SQL 报错/500"。缓解三条：(i) 本设计**逐列给出** `ExportSourceMapper` 的 SELECT 列清单（§2.4，全部取自 `02_schema.sql`，可直接照抄）；(ii) `verify-schema.sh` 已把关键表列钉死；(iii) `smoke-report-review-chain.sh` 新增段**真跑该 SQL**（真实 MySQL），列名错误立即暴露。

---

### 1.2 张力二裁决：B10（`POST /supervise/report`）归属 —— **`supervise_report` 实体/持久化落 `yl-module-supervise`；payload 跨域聚合用「只读投影 Mapper」，与张力一同一裁决**

**结论**：B10 **整体落 `yl-module-supervise`**（控制器 + 服务 + `SuperviseReport` 实体/Mapper + 聚合只读 Mapper）。

**`payload` 需要哪些数据？**：`payload JSON NOT NULL` 是**监管上报报文（月报）**，须由**服务端聚合**生成（契约**无 requestBody**，见 §10 待明确 #1）。本设计取最小口径 —— 聚合**评估域**若干计数（`eval_order` 按月/机构的评估单数、已发布数、等级分布），经 `SuperviseAggregateMapper` 只读投影取回。**是否跨模块？答案：是**（数据源在 evaluation），处理方式与张力一**完全一致**（自持只读投影 Mapper，不引用兄弟模块类）。

**一致性**：与张力一同一套判据（D-2 范式 + 只依赖 yl-api + ADR-0004 未违反 + 同库 JOIN），不再重复论证。

---

### 1.3 张力三裁决：B8 public 端点（`GET /api/v1/reports/verify`）落地 —— 四项逐条裁定

| 子项 | 裁定 | 依据 / 落点 |
|---|---|---|
| **(a) 控制器归属** | **落 `yl-module-evaluation`** —— 新建 `ReportVerifyController`（`@RequestMapping("/api/v1/reports")` + `@GetMapping("/verify")`） | `verify_hash` 计算源 = `eval_order`（`AssessmentReportService#canonicalContent` 用 8 个 `EvalOrder` 字段），验真须**重算并比对**，故**必须**能引用 `EvalOrder`/`EvalReport` → 落 evaluation（地基报告 §0★7 已定） |
| **(b) `SecurityConfig` 放行** | **零改动** —— `/api/v1/reports/verify` **已在** `permitAll` 列表（`SecurityConfig.java` L81） | 实现期**只需核对**该行未被误删；不新增放行路径 |
| **(c) `ControllerAuthorizationCoverageTest` 白名单** | **新增第 5 条** `"ReportVerifyController#verify"`（**禁冗余**） | 类注释白名单理由需**扩为三类**：「会话入口 / 健康探针 / **公开验真（扫码即验，无登录态）**」。`whitelistHasNoRedundantEntry()` 会拦截"加了注解却没摘白名单" —— 故 B8 handler **必须不挂** `@RequiresPermission` |
| **(d) 是否进 `OpenApiContractTest.CONTROLLERS`** | **必须进** | B8 翻 `implemented` → 双向漂移断言要求"契约 implemented 集合 = 控制器端点集合"；`ReportVerifyController` 不登记即"契约多登"红 |

> **public 端点的数据域特殊性**：无登录态 → `DataScopePermissionHandler.currentUser()==null` → 返回 `null`（不加限）。故 B8 的可见性**不能**依赖第三闸，而由**服务层的"最小返回面"**（只返 `{valid, gradeCode, publishedAt}`，不返任何个人信息）保证 —— 这正是裁决 4 的落点。

---

## 2. 实现方案与完整文件列表

### 2.1 文件清单

> 标记：**新**=全新文件｜**改**=修改既有文件。相对路径均以 `<ROOT>` 为根。

**A) evaluation 模块（B6 / B7 / B8）**

| # | 相对路径 | 状态 | 职责 |
|---|---|---|---|
| 1 | `yl-modules/yl-module-evaluation/src/main/java/cn/yl/modules/evaluation/controller/EvalTaskController.java` | 新 | B6 列表端点（`GET /api/v1/evaluation/tasks`） |
| 2 | `.../evaluation/service/EvalTaskQueryService.java` | 新 | B6 查询逻辑（复用 `EvalTaskMapper`） |
| 3 | `.../evaluation/dto/EvalTaskQuery.java` | 新 | B6 入参（pageNum/pageSize/assessorId/status） |
| 4 | `.../evaluation/dto/EvalTaskView.java` | 新 | B6 出参 |
| 5 | `.../evaluation/controller/RuleMappingController.java` | 新 | B7（`GET /api/v1/rule/mapping`） |
| 6 | `.../evaluation/service/RuleMappingService.java` | 新 | B7 查询逻辑 |
| 7 | `.../evaluation/domain/entity/GbRuleMapping.java` | 新 | `gb_rule_mapping` 实体 |
| 8 | `.../evaluation/mapper/GbRuleMappingMapper.java` | 新 | `BaseMapper<GbRuleMapping>` |
| 9 | `.../evaluation/dto/RuleMappingView.java` | 新 | B7 出参 |
| 10 | `.../evaluation/controller/ReportVerifyController.java` | 新 | B8（`GET /api/v1/reports/verify`，public） |
| 11 | `.../evaluation/service/ReportVerifyService.java` | 新 | B8 验真（重算 sha256 比对，最小返回面） |
| 12 | `.../evaluation/dto/ReportVerifyResponse.java` | 新 | B8 出参 `{valid, gradeCode, publishedAt}` |
| 13 | `.../evaluation/src/test/.../controller/EvalTaskControllerTest.java` | 新 | B6 单测 |
| 14 | `.../evaluation/src/test/.../service/RuleMappingServiceTest.java` | 新 | B7 单测 |
| 15 | `.../evaluation/src/test/.../service/ReportVerifyServiceTest.java` | 新 | B8 单测（有效/无效/不存在/最小面） |

**B) account 模块（R6-1 / R6-2）**

| # | 相对路径 | 状态 | 职责 |
|---|---|---|---|
| 16 | `yl-modules/yl-module-account/src/main/java/cn/yl/modules/account/controller/OrgController.java` | 新 | R6 两端点（`GET /api/v1/account/orgs`、`/{orgId}`） |
| 17 | `.../account/service/OrgQueryService.java` | 新 | R6 查询逻辑 |
| 18 | `.../account/mapper/OrgMapper.java` | 新 | `BaseMapper<Org>`（**当前不存在**） |
| 19 | `.../account/dto/OrgQuery.java` | 新 | R6 入参 |
| 20 | `.../account/dto/OrgView.java` | 新 | R6 出参 |
| 21 | `.../account/src/test/.../service/OrgQueryServiceTest.java` | 新 | R6 单测 |
| 22 | `.../account/src/test/.../controller/OrgControllerTest.java` | 新 | R6 单测 |

**C) report 模块（B9）**

| # | 相对路径 | 状态 | 职责 |
|---|---|---|---|
| 23 | `yl-modules/yl-module-report/src/main/java/cn/yl/modules/report/controller/SuperviseExportController.java` | 新 | B9（`POST /api/v1/supervise/exports`） |
| 24 | `.../report/service/SuperviseExportService.java` | 新 | B9 编排（校验 → 取数 → CSV → 落盘 → 落库） |
| 25 | `.../report/service/ExportCsvWriter.java` | 新 | CSV 渲染（BOM + 公式注入转义） |
| 26 | `.../report/mapper/ExportTaskMapper.java` | 新 | `BaseMapper<ExportTask>` |
| 27 | `.../report/mapper/ExportSourceMapper.java` | 新 | **跨域只读投影**（`@Select` 联接 eval_order/eval_report/elder） |
| 28 | `.../report/domain/entity/ExportTask.java` | 新 | `export_task` 实体（含新列 `orgId`） |
| 29 | `.../report/dto/ExportCreateRequest.java` | 新 | B9 入参 |
| 30 | `.../report/dto/ExportTaskView.java` | 新 | B9 出参 |
| 31 | `.../report/dto/ExportSourceRow.java` | 新 | 投影行（CSV 数据源） |
| 32 | `.../report/src/test/.../service/SuperviseExportServiceTest.java` | 新 | B9 单测（超限/落库/键名） |
| 33 | `.../report/src/test/.../service/ExportCsvWriterTest.java` | 新 | CSV 转义单测（**注入样本**） |
| 34 | `yl-modules/yl-module-report/pom.xml` | 改 | 补 `mybatis-plus-spring-boot3-starter` + `spring-boot-starter-test` |

**D) supervise 模块（B10）**

| # | 相对路径 | 状态 | 职责 |
|---|---|---|---|
| 35 | `yl-modules/yl-module-supervise/src/main/java/cn/yl/modules/supervise/controller/SuperviseReportController.java` | 新 | B10（`POST /api/v1/supervise/report`） |
| 36 | `.../supervise/service/SuperviseReportService.java` | 新 | B10 编排（聚合 → payload → 幂等落库 → 写 retain_until） |
| 37 | `.../supervise/mapper/SuperviseReportMapper.java` | 新 | `BaseMapper<SuperviseReport>` |
| 38 | `.../supervise/mapper/SuperviseAggregateMapper.java` | 新 | **跨域只读投影**（`@Select` 聚合 eval_order/eval_report） |
| 39 | `.../supervise/domain/entity/SuperviseReport.java` | 新 | `supervise_report` 实体 |
| 40 | `.../supervise/dto/SuperviseAggregateRow.java` | 新 | 聚合行 |
| 41 | `.../supervise/dto/SuperviseReportView.java` | 新 | B10 出参 |
| 42 | `.../supervise/src/test/.../service/SuperviseReportServiceTest.java` | 新 | B10 单测（幂等/retain_until 写入） |
| 43 | `yl-modules/yl-module-supervise/pom.xml` | 改 | 补两个依赖（同上） |

**E) 跨模块 / 基线 / 门禁（修改）**

| # | 相对路径 | 状态 | 改动 |
|---|---|---|---|
| 44 | `yl-modules/yl-module-account/src/main/java/cn/yl/modules/account/security/DataScopePermissionHandler.java` | 改 | `ORG_COLUMN` +`export_task→org_id`；`SELF_DENY_TABLES` +`supervise_report`；类 Javadoc 补 D-3 段 |
| 45 | `docker/mysql/init/02_schema.sql` | 改 | `export_task` 内联 `org_id`（+`idx_org_time`） |
| 46 | `scripts/verify-schema.sh` | 改 | +1 断言（`export_task.org_id`）；`EXPECTED_TOTAL_ASSERTIONS 82→83`；头注释同步 |
| 47 | `yl-modules/yl-module-account/src/test/java/cn/yl/modules/account/security/DataScopePermissionHandlerTest.java` | 改 | +2 用例（`selfScopeFailsClosedOnSuperviseReport`、`orgScopeFiltersExportTaskByOrgId`） |
| 48 | `openapi/yl-api.yaml` | 改 | **7 个操作** `x-status: planned → implemented`（B6~B10 + R6×2） |
| 49 | `yl-bootstrap/src/test/java/cn/yl/bootstrap/contract/OpenApiContractTest.java` | 改 | `CONTROLLERS` 登记 **6 个新控制器** |
| 50 | `yl-bootstrap/src/test/java/cn/yl/bootstrap/security/ControllerAuthorizationCoverageTest.java` | 改 | 同上登记 6 个；白名单**+第 5 条** `ReportVerifyController#verify`；类注释理由扩为三类 |
| 51 | `scripts/smoke-report-review-chain.sh` | 改 | 新增 `[8/8] 报表与监管段`（CSV 魔数/BOM + 注入样本 + B8 验真 + 未认证 401 断言） |
| 52 | `docs/reviews/D3-export-task-DDL-Patch-Proposal.sql` | 新 | DDL 变更留痕（§6） |
| 53 | `docs/reviews/D3-e2e-checklist.md` | 新 | 验收清单（冒烟对照） |
| 54 | `docs/design/D3-class-diagram.mermaid` / `D3-sequence-diagram.mermaid` | 新 | 图（本设计附） |
| 55 | `yl-bootstrap/src/main/resources/application.yml` | 改 | `yl.export` 段新增 `dir`（本地导出目录）与 `expire-days`（默认 7） |

> **无需改动**：`yl-bootstrap/pom.xml`（已依赖全模块）、`03_seed_rbac.sql`（7 端点全部命中现有授权）、`04_seed_dev_accounts.sql`、`SecurityConfig.java`（B8 路径已放行）、`ReservedWordColumnMappingTest`（`org_id` 非保留字）。

### 2.2 关键类职责与方法签名（供工程师直接落笔）

**B6 `EvalTaskController`**（evaluation，`@RequestMapping("/api/v1/evaluation/tasks")`）

```java
@GetMapping
@RequiresPermission(PermissionCode.EVAL_TASK_READ)
public R<PageResult<EvalTaskView>> list(EvalTaskQuery query);
```
- 服务层 `EvalTaskQueryService#list`：`QueryWrapper<EvalTask>` 显式 `eq("deleted",0)` + `assessorId`/`status` 可选 + 分页。**不手写 `org_id` 过滤**（第三闸自动：scope=2 → `eval_task.org_id IN (orgIds)`；scope=1 → `eval_task.assessor_id = me`；scope=4 → 不限）。

**B7 `RuleMappingController`**（evaluation，`@RequestMapping("/api/v1/rule")`）

```java
@GetMapping("/mapping")
@RequiresPermission(PermissionCode.RULE_VIEW)
public R<List<RuleMappingView>> mapping(@RequestParam Long ruleVersionId);
```
- `RuleMappingService#mapping`：`QueryWrapper<GbRuleMapping>().eq("rule_version_id", ruleVersionId)`。**字典类表**：`gb_rule_mapping` **不得**登记进数据域任何映射（有意对所有账号可读，同 `eval_item`）。

**B8 `ReportVerifyController`**（evaluation，`@RequestMapping("/api/v1/reports")`）

```java
@GetMapping("/verify")
// 无 @RequiresPermission：public 端点（覆盖测试白名单第 5 条）
public R<ReportVerifyResponse> verify(@RequestParam("code") String code);
```
- `ReportVerifyService#verify(String code)`：
  1. 归一化 `code` → `reportNo`（兼容「纯 reportNo」与「完整 QR URL `...?no=RPxxx&hash=...`」）；白名单 `[A-Za-z0-9_-]{1,64}`，否则 `BizException(PARAM_INVALID)`；
  2. `reportMapper.selectOne(report_no = reportNo)`（匿名 → 第三闸不加限）；
  3. 未命中 → `{valid:false, gradeCode:null, publishedAt:null}`（**不泄露存在性**）；
  4. 命中 → 取 `report.order_id` → `orderMapper.selectById` → **重算** `sha256(canonicalContent(order))` 与 `report.verify_hash` 比对；`valid = (report.status == 1 && 摘要一致)`；
  5. 返回 `{valid, gradeCode, publishedAt}` —— **绝不含** name/idCard/phone。
- `canonicalContent` 口径**必须与 `AssessmentReportService` 完全一致**（8 字段 `|` join）。本设计**在 `ReportVerifyService` 内联复刻 + 单测钉死与源一致**（不动既有 C3 服务签名；抽公共方法列为可选重构，见 §10）。

**R6 `OrgController`**（account，`@RequestMapping("/api/v1/account/orgs")`）

```java
@GetMapping
@RequiresPermission(value = {PermissionCode.ACCOUNT_ORG_MANAGE, PermissionCode.ACCOUNT_ORG_MANAGE_READ},
                    logical = Logical.OR)
public R<PageResult<OrgView>> list(OrgQuery query);

@GetMapping("/{orgId}")
@RequiresPermission(value = {PermissionCode.ACCOUNT_ORG_MANAGE, PermissionCode.ACCOUNT_ORG_MANAGE_READ},
                    logical = Logical.OR)
public R<OrgView> detail(@PathVariable Long orgId);
```
- `org` 已登记 `ORG_COLUMN(org→id)` → scope=2 自动 `org.id IN (orgIds)`；scope=4 不限。`detail` 查不到 → `10004`。

**B9 `SuperviseExportController`**（report，`@RequestMapping("/api/v1/supervise/exports")`）

```java
@PostMapping
@RequiresPermission(PermissionCode.DATA_EXPORT)                       // 敏感点 → 第二/四闸自动联动
@Idempotent(verifyBody = true)                                        // Idempotency-Key 必填（缺 → 10007）
@Audit(action = "EXPORT", bizType = "EXPORT_TASK", sensitive = true)  // 成功后 audit_log(sensitive=1)
public R<ExportTaskView> create(@Valid @RequestBody ExportCreateRequest request);
```
- `SuperviseExportService#create(req, LoginUser operator)`：
  1. `format ∈ {CSV}` 否则 `PARAM_INVALID`（XLSX/PDF → `10001`，残留）；
  2. `exportType ∈ {EVAL_LIST}` 否则 `10001`（REPORT/STAT 残留）；
  3. `sourceMapper.selectEvalList(operator.orgIds(), yl.export.max-sync-rows)`；
  4. `rowCount > max-sync-rows` → `BizException(EXPORT_TASK_TOO_LARGE=60001)`；
  5. `ExportCsvWriter#writeCsv` → `byte[]`（BOM 开头）；
  6. `key = "export/" + taskNo + ".csv"` → 写 `yl.export.dir`（未配置回退 `${java.io.tmpdir}/yl-exports`，**复刻 `ReportPdfService.resolve()` 的防目录穿越**）；
  7. `insert(ExportTask{ orgId=operator.requireOrgId(), operatorId=operator.userId(), expireAt=now.plusDays(yl.export.expire-days 默认7), status=2, fileCosKey=key, rowCount, taskNo })`。

**B10 `SuperviseReportController`**（supervise，`@RequestMapping("/api/v1/supervise/report")`）

```java
@PostMapping
@RequiresPermission(PermissionCode.SUPERVISE_REPORT_SUBMIT)
@Idempotent(verifyBody = true)
public R<SuperviseReportView> submit();
```
- `SuperviseReportService#submit(orgId, idempotentKey)`：
  1. `reportMonth = YearMonth.now()`（`YYYY-MM`）；
  2. `aggregateMapper.aggregate(orgId, reportMonth)` → 聚合行；
  3. `payload = objectMapper.writeValueAsString(row)`；
  4. `insert(SuperviseReport{ reportMonth, orgId, payload, idempotentKey, status=1, sentAt=now, retainUntil=now.plusYears(5) })`；
     - 幂等：`uk_idem(idempotent_key)` 命中 → 回放既有行（或 `IDEMPOTENT_REPLAY=10006`，实现时二选一并注释说明）；
     - `uk_month_org(report_month, org_id)` 命中（同月同机构重复）→ 更新既有行（`payload`/`sent_at`/`retain_until`），**不新增行**；
  5. 返回 `{reportMonth, orgId, status, sentAt}`。
- **不新增 `@Scheduled`**；`retain_until` **只写值**，清理排 P2-7/D-5（裁决 3）。

### 2.3 跨域只读投影 Mapper 的**精确列清单**（防 `care_plan.org_id` 那类列名错误）

> 列名全部取自 `docker/mysql/init/02_schema.sql`（唯一事实源）。实现时必须**逐列照抄**，不得臆造。

**`ExportSourceMapper#selectEvalList`（report 模块，B9）**

```sql
SELECT  o.order_no        AS order_no,
        COALESCE(r.grade_code, o.grade_code) AS grade_code,
        e.name_enc        AS elder_name_enc,     -- 供服务层解密/脱敏；库内无明文
        e.id_card_mask    AS id_card_mask,
        e.phone_enc       AS phone_enc,          -- 供服务层脱敏；库内无明文
        o.status          AS status,
        o.org_id          AS org_id,
        o.published_at    AS published_at
FROM    eval_order o
LEFT JOIN eval_report r ON r.order_id = o.id AND r.deleted = 0
LEFT JOIN elder       e ON e.id = o.elder_id
WHERE   o.deleted = 0
ORDER BY o.created_at DESC
LIMIT   #{limit}
```
- 参与 JOIN 的三表（`eval_order`/`eval_report`/`elder`）**均在第三闸登记**（`ORG_COLUMN`）→ 拦截器会为每个表追加 `org_id IN (orgIds)`（scope=2 时），天然把导出范围收在登录态数据域内。
- 列的取用原则（对齐裁决 4「最小返回面」精神）：**只取脱敏/密文列**，不取任何明文个人信息列（`elder` 表本就无明文姓名/手机列）。

**`SuperviseAggregateMapper#aggregate`（supervise 模块，B10）**

```sql
SELECT  COUNT(*)                                                    AS assessment_count,
        SUM(CASE WHEN o.status = 'PUBLISHED' THEN 1 ELSE 0 END)     AS published_count,
        SUM(CASE WHEN o.grade_code = 'GRADE_INTACT' THEN 1 ELSE 0 END) AS grade_intact_count,
        SUM(CASE WHEN o.grade_code IN ('GRADE_SEVERE','GRADE_TOTAL') THEN 1 ELSE 0 END) AS grade_severe_count
FROM    eval_order o
WHERE   o.deleted = 0
  AND   o.org_id  = #{orgId}
  AND   DATE_FORMAT(o.created_at, '%Y-%m') = #{month}
```
- `eval_order` 已登记 `ORG_COLUMN`/`SELF_COLUMN`；拦截器会追加行级条件（scope=4 监管：不限）。

> ⚠️ **列名/取值断言**：`eval_order.status` 为 `VARCHAR(16)`（值 `DRAFT/SUBMITTED/PUBLISHED/REJECTED/VOID/ARCHIVED`，**不是** TINYINT）；`eval_order.grade_code` ∈ `GRADE_INTACT/MILD/MODERATE/SEVERE/TOTAL`（**带 `GRADE_` 前缀**，见 `02_schema.sql` L299）。B10 聚合的**等级码口径以 `eval_order.grade_code` 实际枚举为准**，实现期须核 `03_seed_rbac`/契约 `GradeResult`。

### 2.4 CSV 渲染口径（`ExportCsvWriter`，B9）

| 规则 | 口径 |
|---|---|
| 编码 | UTF-8，**文件首字节 = BOM `0xEF 0xBB 0xBF`**（`\uFEFF`） |
| 行分隔 | `\r\n`（CRLF） |
| 字段分隔 | `,` |
| 引号包裹 | 字段含 `,` / `"` / `\n` / `\r` 时用 `"` 包裹，内部 `"` → `""` |
| **公式注入转义** | 单元格以 `= + - @ \t \r` 任一字符**开头**时，前置加 `'`（单引号） |
| 表头 | 首行固定表头（`导出类型 EVAL_LIST` 的列名集合） |
| 魔数断言 | 冒烟：`head -c 3` == `\xEF\xBB\xBF`；且**注入样本单元格**必须以 `'` 开头 |

---

## 3. 数据结构与接口（类图）

完整类图见 `docs/design/D3-class-diagram.mermaid`。实体列口径逐条如下（均以 `02_schema.sql` 为准）：

### 3.1 实体

**`ExportTask`（`export_task`，**含 D-3 新列 `orgId`**）** — report 模块

| 属性 | 列 | 类型 | 备注 |
|---|---|---|---|
| `id` | `id` | Long | `@TableId(type = IdType.ASSIGN_ID)`（ADR-0003，无 AUTO_INCREMENT） |
| `taskNo` | `task_no` | String | 唯一 `uk_export_no`；生成规则见 §9 |
| `exportType` | `export_type` | String | `EVAL_LIST`（D-3 仅此一种） |
| `format` | `format` | String | `CSV`（库默认 `XLSX`，实体显式写 `CSV`） |
| `queryParams` | `query_params` | String | JSON 快照 |
| `maskLevel` | `mask_level` | Integer | 库默认 1 |
| `rowCount` | `row_count` | Integer | 导出条数 |
| `status` | `status` | Integer | 0-排队 1-生成中 2-完成 3-失败 4-已过期 |
| `fileCosKey` | `file_cos_key` | String | `export/<taskNo>.csv` |
| `operatorId` | `operator_id` | Long | 发起人（取自登录态） |
| **`orgId`** | **`org_id`** | **Long** | **D-3 新增（裁决 1-A，可空）** |
| `startedAt` / `finishedAt` | 同名 | LocalDateTime | |
| `expireAt` | `expire_at` | LocalDateTime | **只写值**；清理排 P2-7 |
| `createdAt` | `created_at` | LocalDateTime | 依赖 DB 默认值 |

> `export_task` **无 `deleted` 列**（无软删）→ 实体**不映射** `deleted`；查询不追加 `deleted=0`。无生成列（`active_uk`）。

**`SuperviseReport`（`supervise_report`）** — supervise 模块

| 属性 | 列 | 类型 | 备注 |
|---|---|---|---|
| `id` | `id` | Long | ASSIGN_ID |
| `reportMonth` | `report_month` | String | `CHAR(7)`，`YYYY-MM` |
| `orgId` | `org_id` | Long | NOT NULL |
| `payload` | `payload` | String | JSON（服务端生成） |
| `tsToken` / `lastError` | 同名 | String | D-3 不强求写入（保留 NULL） |
| `status` | `status` | Integer | 0-待上报 1-成功 2-失败 3-重试中 |
| `retryCount` | `retry_count` | Integer | 默认 0 |
| `idempotentKey` | `idempotent_key` | String | 唯一 `uk_idem` |
| `sentAt` / `retainUntil` / `createdAt` | 同名 | LocalDateTime | `retain_until` **只写值** |

> `supervise_report` **无 `deleted` 列** → 实体不映射 `deleted`。

**`GbRuleMapping`（`gb_rule_mapping`）** — evaluation 模块

字段：`id`(ASSIGN_ID)、`ruleVersionId`、`clauseNo`、`clauseTitle`、`itemCode`、`ruleCode`、`remark`；**无 `deleted`、无归属列**（字典类表）。

### 3.2 DTO

| DTO | 模块 | 字段 |
|---|---|---|
| `EvalTaskQuery` | evaluation | `pageNum`,`pageSize`,`assessorId?`,`status?` |
| `EvalTaskView` | evaluation | `id,taskNo,elderId,orgId,assessorId,scene,status,dueAt,createdAt` |
| `RuleMappingView` | evaluation | `clauseNo,clauseTitle,itemCode,ruleCode,remark` |
| `ReportVerifyResponse` | evaluation | `valid:boolean, gradeCode:String, publishedAt:LocalDateTime`（**仅此三字段**） |
| `OrgQuery` | account | `pageNum,pageSize,keyword?` |
| `OrgView` | account | `id,orgCode,orgName,orgType,regionCode,address,contactName,status`（**不含 `contactPhoneEnc`**） |
| `ExportCreateRequest` | report | `exportType:@NotBlank`, `format:@NotBlank`, `queryParams:Map` |
| `ExportTaskView` | report | `id,taskNo,exportType,format,rowCount,status,fileCosKey` |
| `ExportSourceRow` | report | `orderNo,gradeCode,elderNameEnc,idCardMask,phoneEnc,status,orgId,publishedAt` |
| `SuperviseAggregateRow` | supervise | `assessmentCount,publishedCount,gradeIntactCount,gradeSevereCount` |
| `SuperviseReportView` | supervise | `id,reportMonth,orgId,status,sentAt` |

---

## 4. 端点契约表（7 个 planned → implemented）

权限码来源：`03_seed_rbac.sql` 的 `sys_role_permission`（**权威事实**）。

| # | Method / Path | operationId | 归属模块 | 权限码（来源 id → 持有角色） | 数据域 mode | 二次验证 | 幂等 | 错误码（复用现有 `ErrorCode`） | 留痕 |
|---|---|---|---|---|---|---|---|---|---|
| B6 | `GET /api/v1/evaluation/tasks` | `evalTaskList` | evaluation | `evaluation:task:read`（2020 → ASSESSOR 3032 / ORG_ADMIN 3033 / SUPERVISOR 3034） | org（by-role SUPERVISOR→all） | 否 | 否 | `10001` 分页；`20003` | 无 |
| B7 | `GET /api/v1/rule/mapping` | `ruleMapping` | evaluation | `rule:view`（2022 → 五角色 3036-3040） | **all（字典）** | 否 | 否 | `10001`（`ruleVersionId` 必填） | 无 |
| B8 | `GET /api/v1/reports/verify` | `reportVerify` | evaluation | **none（public）** | **public** | 否 | 否 | `10001`（code 非法）；`10429`（超限） | 无（最小返回面） |
| B9 | `POST /api/v1/supervise/exports` | `superviseExportCreate` | report | `data:export`（2012 → ORG_ADMIN 3019 / SUPERVISOR 3020） | org | **是**（`X-Second-Verify-Token`，自动） | `Idempotency-Key` 必填 | `10001`（format/type）；`10007` 缺幂等键；`10008` 缺二次验证；`60001` 超限 | **`audit_log(action=EXPORT, sensitive=1)`** |
| B10 | `POST /api/v1/supervise/report` | `superviseReportSubmit` | supervise | `supervise:report:submit`（2011 → ORG_ADMIN 3017 / SUPERVISOR 3018） | org | 否 | `Idempotency-Key` 必填 | `10001`；`10006` 重复；`10007` 缺幂等键 | 可选（建议后续补 `@Audit(SUPERVISE_REPORT)`） |
| R6-1 | `GET /api/v1/account/orgs` | `orgList` | account | `account:org:manage`(2009,3015) **OR** `account:org:manage:read`(2010,3016) | org(by-role SUPERVISOR→all) | 否 | 否 | `10001` | 无 |
| R6-2 | `GET /api/v1/account/orgs/{orgId}` | `orgDetail` | account | 同上（OR） | org | 否 | 否 | `10004`（越界/不存在，同码不泄露存在性） | 无 |

**数据域落点说明（7 端点 × 角色）**

| 端点 | ORG_ADMIN(2) / ASSESSOR(2) | ELDER(1) | FAMILY(5) | SUPERVISOR(4) |
|---|---|---|---|---|
| `evalTaskList` | `eval_task.org_id IN (orgIds)` | 无权限码 | 无权限码 | 不限（by-role all） |
| `ruleMapping` | 字典，不加限（五角色同） | 同左 | 同左 | 同左 |
| `reportVerify` | public（无登录态 → 第三闸不加限，靠最小返回面） | 同左 | 同左 | 同左 |
| `superviseExportCreate` | `export_task.org_id IN (orgIds)`（仅持码两角色） | 无权限码 | 无权限码 | 不限（by-role all） |
| `superviseReportSubmit` | 写 `supervise_report`（`org_id` 取登录态；scope=1/5 读它恒假） | 无权限码 | 无权限码 | 写（`org_id` 口径见 §10#4） |
| `orgList` / `orgDetail` | `org.id IN (orgIds)` | 无权限码 | 无权限码 | 不限（by-role all） |

**错误码复用声明（无新增）**：全部命中现有 `ErrorCode`（`10001/10004/10006/10007/10008/20003/10429/60001`），**不新增任何号段**。

---

## 5. 时序图

完整时序图见 `docs/design/D3-sequence-diagram.mermaid`，含 4 条链：
1. **5.1 B9 CSV 导出全链**（第二/四闸 → 幂等 → 第三闸收口 → CSV（BOM+注入转义）→ 落盘 `export/<taskNo>.csv` → 落库 → 留痕）；
2. **5.2 B8 验真**（public：既有 rate-limit 覆盖 → 归一化 code → 反查 `eval_report` → 重算 sha256 比对 → 最小返回面）；
3. **5.3 B10 上报**（第二闸 → 聚合 → payload → 幂等 upsert → 只写 `retain_until`）；
4. **5.4 拒绝链路**（未认证 401+10002 / 第二闸 200+20003 / public 放行）。

> **断言口径（务必遵守，见 §7）**：拒绝看 `R.code`（业务失败恒 HTTP 200）；未认证额外看 **HTTP 401**；CSV 端点看**魔数**（`\xEF\xBB\xBF` + 首行表头）。

---

## 6. DDL / 种子 / 数据域影响清单

### 6.1 `ALTER TABLE` 草案（→ 内联进 `02_schema.sql` 的 `export_task` 建表语句）

提案文件：`docs/reviews/D3-export-task-DDL-Patch-Proposal.sql`（已生成）

```sql
ALTER TABLE `export_task`
  ADD COLUMN `org_id` BIGINT NULL
    COMMENT '归属机构（D-3 裁决 1-A：导出范围不得超过登录态数据域；可空）' AFTER `operator_id`,
  ADD KEY `idx_org_time` (`org_id`, `created_at`);

-- 回填既有行（由 operator_id 经机构成员关系推导；仅填 NULL 行）
UPDATE `export_task` et
  JOIN `sys_org_member` m
    ON m.`user_id` = et.`operator_id` AND m.`deleted` = 0 AND m.`status` = 1
  SET et.`org_id` = m.`org_id`
  WHERE et.`org_id` IS NULL;
```
- **内联方式**：按项目惯例，直接在 `02_schema.sql` 的 `export_task` 建表内加入该列与索引（提案文件保留为变更留痕）。
- **回退**：`ALTER TABLE export_task DROP INDEX idx_org_time, DROP COLUMN org_id;`（非破坏）。

### 6.2 数据域映射表 **逐行 diff**（`DataScopePermissionHandler.java`）

**① `ORG_COLUMN`（scope=2，机构级）—— 新增 1 行**

```diff
  private static final Map<String, String> ORG_COLUMN =
          Map.ofEntries(
                  Map.entry("org", "id"),
                  Map.entry("org_bed", "org_id"),
                  Map.entry("staff", "org_id"),
                  Map.entry("sys_org_member", "org_id"),
                  Map.entry("elder", "org_id"),
                  Map.entry("eval_task", "org_id"),
                  Map.entry("eval_order", "org_id"),
                  Map.entry("eval_report", "org_id"),
                  Map.entry("care_task", "org_id"),
-                 Map.entry("supervise_report", "org_id"));
+                 Map.entry("supervise_report", "org_id"),
+                 // D-3 裁决 1-A：export_task 补可空锚列 org_id 并登记，修「机构管理员跨机构可读导出任务」
+                 // （原无机构列 → 未登记 → scope=2 静默放行）。NULL 行因 IN 不匹配自然不可见。
+                 Map.entry("export_task", "org_id"));
```

**② `SELF_DENY_TABLES`（scope=1/5 恒假）—— 新增 1 行**

```diff
  private static final Set<String> SELF_DENY_TABLES =
          Set.of(
                  "eval_report",
                  "report_dimension_score",
                  "eval_answer",
                  "eval_evidence",
                  "consent_record",
                  "elder_authorization",
                  "elder_family_bind",
                  "org_bed",
-                 "care_task");
+                 "care_task",
+                 // D-3：supervise_report 是监管域报文，scope=1/5（老人/家属）不得读。
+                 // 原仅登记 ORG_COLUMN（scope=2/4 可读），scope=1/5 下走「未登记 → 放行」= 暗区，故显式补入。
+                 "supervise_report");
```

**③ `SELF_COLUMN` —— 不动**（`export_task → operator_id` 保留，与新增的 `ORG_COLUMN(export_task→org_id)` 并列共存，同 `eval_task` 范式）。

**④ `RELATION_ELDER_COLUMN` / `RELATION_VIA_PARENT` / `MY_ELDER_SUBQUERY` —— 不动**（`export_task`/`supervise_report` 不在其中；scope=5 下 `export_task` 会落到 `SELF_COLUMN` → `(export_task.operator_id = me)`，`supervise_report` 会落到 `SELF_DENY_TABLES` → `(1=0)`）。

**⑤ 类 Javadoc** —— 追加一段 D-3 说明（`export_task` 补锚列 + `supervise_report` 入 deny 表；`gb_rule_mapping` 为有意放行的字典表）。

### 6.3 `verify-schema.sh` 新增断言（+1 条）

```sh
# 导出归属机构列 export_task.org_id（D-3 裁决 1-A：机构级导出任务可见性锚列）
check "导出归属机构列 export_task.org_id（bigint，可空）" "$(sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DB_NAME' AND table_name='export_task' AND column_name='org_id' AND data_type='bigint';")" "1"
```
- **`EXPECTED_TOTAL_ASSERTIONS` 82 → 83**；头注释补一行 `82 → 83：D-3 新增 export_task.org_id 断言 1 条`。

### 6.4 `application.yml` 新增（`yl.export` 段）

```yaml
  export:
    max-sync-rows: 5000                     # 既有
    async-threshold-rows: 10000             # 既有
    dir: ${EXPORT_DIR:}                     # 新增：导出落盘目录（空 → ${java.io.tmpdir}/yl-exports）
    expire-days: ${EXPORT_EXPIRE_DAYS:7}    # 新增：expire_at = now + N 天（只写值，清理排 P2-7）
```

### 6.5 是否触发保留字金丝雀？—— **否**（不撞车证明）

金丝雀断言（`verify-schema.sh` L266）恒为 `1`（唯一命中 `audit_log.sensitive`）。`org_id` **非** MySQL 8 保留字：库内 `org_bed`/`staff`/`sys_org_member`/`eval_task`/`eval_order`/`eval_report`/`care_task`/`supervise_report` 等**已有多列 `org_id`**；若 `ORG_ID` 命中 `KEYWORDS(RESERVED=1)`，命中数早已 > 1。故新增 `export_task.org_id` **不改变**命中数（仍 = 1），实体侧**无需反引号映射**。

### 6.6 是否影响其它既有断言？—— **否**

| 断言 | 期望值 | 新增列影响 |
|---|---|---|
| `deleted` 列数 | `21`（精确） | **无**（未增删 `deleted`；`export_task`/`supervise_report` 本无 `deleted`） |
| 生成列 `active_uk` 数 | `5` | **无** |
| 密文列 `*_enc` | `≥10` | **无** |
| 索引总数 | `≥40` | 新增 1 索引 → 更宽松（仍通过） |
| 自增列 | `1`（仅 `gb_rule_version`） | **无** |
| 外键数 | `0` | **无**（不建 FK） |
| 个人信息表明文敏感列 | `0` | **无**（`org_id` 不在断言列名清单内） |
| 关键表白名单 | 逐表存在 | **无**（未新增表） |
| 契约权限码条数下界 | `≥20` | **无**（契约码集合不变） |

### 6.7 种子影响

- **`03_seed_rbac.sql`：零改动**（7 端点全部命中现有授权；无新增权限码/授权）。
- **`04_seed_dev_accounts.sql`：零改动**。

---

## 7. 对既有门禁与测试的影响清单

> ⚠️ **"翻旗后不同步会立刻变红"的位置已加粗。**

| # | 门禁 / 测试 | 受影响 | 必须同步的动作 |
|---|---|---|---|
| 1 | **`OpenApiContractTest.declaredImplementedOperationsMatchControllersExactly`** | **是（红）** | **翻旗 7 操作 + `CONTROLLERS` 登记 6 个新控制器**，二者缺一即双向漂移失败。`CONTROLLERS` 是硬编码 list |
| 2 | `OpenApiContractTest.everyOperationDeclaresPermissionAndDataScope` / `dataScopeModesAreInEnum` / `sensitiveCodesRequireSecondVerify` | 软 | 7 操作本身合规；`superviseExportCreate` 已带 `x-second-verify: true`，翻旗不动扩展键 → **预期不红** |
| 3 | **`scripts/verify-openapi.sh`［5/5］漂移（双向）** | **是（红）** | 与 #1 同源：契约 `implemented` 集合须 = 控制器端点集合（脚本 `find ... '*Controller.java'` 扫全模块，含新模块） |
| 4 | **`ControllerAuthorizationCoverageTest.everyHandlerEndpointDeclaresPermission`** | **是（红）** | **6 个新控制器必须登记进 `CONTROLLERS`；每个 handler 须挂 `@RequiresPermission`（除 B8）** |
| 5 | **`ControllerAuthorizationCoverageTest.whitelistHasNoRedundantEntry`** | **是（红）** | 白名单**新增第 5 条** `ReportVerifyController#verify`；且该 handler **不得**有 `@RequiresPermission`（否则"冗余豁免"红） |
| 6 | **`scripts/verify-schema.sh` 断言总数自检** | **是（红）** | +1 断言后必须 `EXPECTED_TOTAL_ASSERTIONS → 83`，且 `02_schema.sql` 内联列后复跑全绿 |
| 7 | `scripts/verify-schema.sh` 保留字金丝雀（=1） | 否（软） | 不改期望值（§6.5 证明不撞车） |
| 8 | `DataScopePermissionHandlerTest` | 是（增量） | 新增 2 用例钉死 §6.2 的两处登记（**先行失败单测**，见 T01） |
| 9 | `ReservedWordColumnMappingTest` | 否 | `org_id` 非保留字，实体无需反引号映射 |
| 10 | `scripts/smoke-report-review-chain.sh`（当前 53 通过/0 失败） | 是（增量） | 新增 `[8/8]` 报表与监管段（**无固定断言总数，勿硬编码**） |
| 11 | `mvn -o verify`（Checkstyle/Spotless/SpotBugs） | 是（软） | 禁 `System.out`/`java.util.Date`/星号导入/Tab/行尾空格；圈复杂度 ≤15；控制器注入用 `@RequiredArgsConstructor(onConstructor_=@SuppressFBWarnings("EI_EXPOSE_REP2"))` **且带 justification**；格式跑 `mvn -o -q spotless:apply` |
| 12 | **`mvn verify` 前置**：运行实例锁 fat jar | — | 进门禁前先 `netstat -ano \| grep :8080` → `taskkill`（D-2 坑④） |

**门禁期望值（只向上，禁改既有断言）**

| 门禁 | 当前基线（D-2 收口） | D-3 目标 |
|---|---|---|
| `mvn -o verify` 单测 | **148 例 / 0 失败** | **≥148（只增）**；新增：evaluation 3 类 + account 2 类 + report 2 类 + supervise 1 类 + 数据域 2 例 |
| `verify-schema.sh` | **82 断言** | **83**（+1：`export_task.org_id`） |
| `verify-openapi.sh` 漂移 | **8/8**；implemented 26 / planned 17 | **8/8**；implemented **33** / planned **10** |
| 冒烟 `smoke-report-review-chain.sh` | **53 通过 / 0 失败** | **≥53（只增）**；新增 `[8/8]` 报表与监管段 |
| `Checkstyle` / `SpotBugs` | 0 / 0 | **0 / 0**（多余 suppression 亦阻断） |

---

## 8. 有序任务列表（供工程师直接执行）

> 硬约束：**≤5 个任务**；每任务 **≥3 文件**；T01 为数据层/安全基线；**同一 Maven 工程禁并行改**（串行推进）。
> 关键路径：**T01 → T02 → T03 → T04 → T05**（严格串行，防门禁互踩）。

### T01（P0）数据域安全基线 + DDL 补丁（含**先行失败单测**）
- **Source Files**：
  - `yl-modules/yl-module-account/.../security/DataScopePermissionHandler.java`（改：`ORG_COLUMN` +`export_task→org_id`；`SELF_DENY_TABLES` +`supervise_report`；Javadoc）
  - `docker/mysql/init/02_schema.sql`（改：`export_task` 内联 `org_id` + `idx_org_time`）
  - `docs/reviews/D3-export-task-DDL-Patch-Proposal.sql`（**已产出**，纳入提交）
  - `scripts/verify-schema.sh`（改：+1 断言；`EXPECTED_TOTAL_ASSERTIONS 82→83`；头注释）
  - `yl-modules/yl-module-account/src/test/.../security/DataScopePermissionHandlerTest.java`（改：**+2 先行失败单测**）
- **要点**：先写测试再改处理器 —— `selfScopeFailsClosedOnSuperviseReport`（scope=1 查 `supervise_report` → 断言 `(1 = 0)`）、`orgScopeFiltersExportTaskByOrgId`（scope=2 查 `export_task` → 断言 `(export_task.org_id IN (1))`）。**这两条在改处理器前必须红**。
- **Dependencies**：无
- **作为独立提交单元**：✅（门禁不翻旗，仅 schema +1 断言 → 需与 `02_schema.sql` 同步否则 #6 红）
- **Priority**：P0

### T02（P0）契约冻结 + 两处硬编码清单预置（**红点收敛起点**）
- **Source Files**：
  - `openapi/yl-api.yaml`（改：**7 操作** `x-status: planned → implemented`）
  - `yl-bootstrap/src/test/.../contract/OpenApiContractTest.java`（改：`CONTROLLERS` +6）
  - `yl-bootstrap/src/test/.../security/ControllerAuthorizationCoverageTest.java`（改：`CONTROLLERS` +6；白名单 **+第 5 条** `ReportVerifyController#verify`；类注释理由扩为三类）
- **要点**：翻旗后**立刻跑一次** `bash scripts/verify-openapi.sh` + `mvn -o -q -pl yl-bootstrap test`，**让已知红点（漂移双向）+ 编译错误（引用了尚不存在的控制器类）一次性亮出来**，确认红得"对"（因控制器未建），随后由 T03/T04 逐个收敛至全绿。
- **Dependencies**：T01
- **Priority**：P0

### T03（P0）evaluation 域 3 端点（B6 / B7 / B8）
- **Source Files**：`EvalTaskController.java`、`EvalTaskQueryService.java`、`EvalTaskQuery.java`、`EvalTaskView.java`、`RuleMappingController.java`、`RuleMappingService.java`、`GbRuleMapping.java`、`GbRuleMappingMapper.java`、`RuleMappingView.java`、`ReportVerifyController.java`、`ReportVerifyService.java`、`ReportVerifyResponse.java` + 3 个测试类
- **要点**：B6 `deleted=0` 显式；B7 **不登记数据域**（字典）；B8 public（**无 `@RequiresPermission`**）+ 归一化 code + 重算摘要 + 最小返回面；`canonicalContent` 与 `AssessmentReportService` **逐字段一致**（单测钉死）。
- **Dependencies**：T02
- **Priority**：P0

### T04（P0）account（R6×2）+ report（B9）+ supervise（B10）—— **域模块串行**
- **Source Files**：
  - account：`OrgController.java`、`OrgQueryService.java`、`OrgMapper.java`、`OrgQuery.java`、`OrgView.java` + 2 测试类
  - report：`SuperviseExportController.java`、`SuperviseExportService.java`、`ExportCsvWriter.java`、`ExportTaskMapper.java`、`ExportSourceMapper.java`、`ExportTask.java`、`ExportCreateRequest.java`、`ExportTaskView.java`、`ExportSourceRow.java`、`yl-module-report/pom.xml`（改）+ 2 测试类
  - supervise：`SuperviseReportController.java`、`SuperviseReportService.java`、`SuperviseReportMapper.java`、`SuperviseAggregateMapper.java`、`SuperviseReport.java`、`SuperviseAggregateRow.java`、`SuperviseReportView.java`、`yl-module-supervise/pom.xml`（改）+ 1 测试类
  - `yl-bootstrap/src/main/resources/application.yml`（改：`yl.export.dir` / `expire-days`）
- **要点**：**严格串行**（先 account → 再 report → 后 supervise，禁同工程并行）；B9 精确列清单照 §2.3；CSV 转义照 §2.4；B10 幂等口径照 §2.2；`export_task`/`supervise_report` 实体**不映射 `deleted`**。
- **Dependencies**：T03（并收敛 T02 红点）
- **Priority**：P0

### T05（P1）冒烟增量 + QA 突变 5 条 + 验收清单
- **Source Files**：
  - `scripts/smoke-report-review-chain.sh`（改：新增 `[8/8] 报表与监管段`）
  - `docs/reviews/D3-e2e-checklist.md`（新）
- **要点（冒烟断言，下沉到 `R.code` + 魔数）**：
  - B6：`GET /evaluation/tasks` → `code=0`；`evaluation:task:read` 授权。
  - B7：`GET /rule/mapping?ruleVersionId=<有效>` → `code=0`；`ruleVersionId` 缺失 → `10001`。
  - B8：`GET /reports/verify?code=<published reportNo>` → `code=0` 且 `valid=true`；`code=<不存在>` → `code=0` 且 `valid=false`；返回体**不含** `name/idCard/phone`。
  - B9：缺 `X-Second-Verify-Token` → `10008`；带令牌 + `format=CSV` → `code=0` 且落盘文件**首 3 字节 = `\xEF\xBB\xBF`** + 首行表头；**注入样本**（`queryParams` 造一条以 `=` 开头的值）→ 单元格以 `'` 开头；`format=XLSX` → `10001`；`export_task.org_id` 已落库非空。
  - B10：首次 → `code=0` 且 `supervise_report.retain_until IS NOT NULL`；同月同机构重放 → 不新增行（`uk_month_org`）。
  - **CR-M2-007 断言**：无令牌 `GET /evaluation/tasks` → **HTTP 401** + `R.code=10002`。
  - **越权断言**：跨机构（临时改 `export_task.org_id=999`）→ 该机构管理员列表/详情均不可见。
- **QA 突变 5 条**（独立验证，删一处应全红）：
  1. 从 `SELF_DENY_TABLES` 删 `supervise_report` → `selfScopeFailsClosedOnSuperviseReport` 红。
  2. 从 `ORG_COLUMN` 删 `export_task` → 数据域单测红 + 冒烟跨机构可读。
  3. 把 B9 控制器 `@RequiresPermission(DATA_EXPORT)` 摘掉 → `ControllerAuthorizationCoverageTest` 红。
  4. 把 B8 handler 加 `@RequiresPermission` → `whitelistHasNoRedundantEntry` 红。
  5. 把某操作 `x-status` 由 `implemented` 翻回 `planned` → `OpenApiContractTest` / `verify-openapi.sh` 双向漂移红。
- **Dependencies**：T04
- **Priority**：P1

---

## 9. 共享知识（跨文件约定）

1. **模块与包根**：`cn.yl.modules.evaluation`（B6/B7/B8）、`cn.yl.modules.account`（R6）、`cn.yl.modules.report`（B9）、`cn.yl.modules.supervise`（B10）；分层对齐 evaluation：`controller/service/mapper/dto/domain/entity`。控制器 `XxxController`，服务 `XxxService`，实体无后缀，入参 `XxxRequest`/`XxxQuery`，出参 `XxxView`/`XxxResponse`。
2. **身份来源**：一律 `SecurityUtils.requireUser()`/`requireUserId()` 取登录态；**请求体不得承载身份字段**（`operatorId`/`orgId` 由服务端取登录态）。B9 的 `orgId` 取 `operator.requireOrgId()`。
3. **CSV 转义函数（唯一约定，勿各写一份）**：`ExportCsvWriter#escapeCell(String)` ——
   - 若首字符 ∈ `{=, +, -, @, \t, \r}` → 前置 `'`；
   - 若含 `, " \n \r` → `"..."` 包裹 + `"`→`""`；
   - 文件首字节 **UTF-8 BOM**；行分隔 **CRLF**。
4. **存储键名口径**：`export/<taskNo>.csv`（与 `report/<reportNo>.pdf` 同形态；后续接 COS **只换写入实现，不改键名**）。本地目录 `yl.export.dir`，未配置回退 `${java.io.tmpdir}/yl-exports`；`resolve()` 必须**防目录穿越**（`file.startsWith(base)` 校验，复刻 `ReportPdfService`）。
5. **幂等键口径**：HTTP 头 `Idempotency-Key`（`@Idempotent(verifyBody=true)`，缺失 → `10007`）；`supervise_report.idempotent_key`（唯一 `uk_idem`，`VARCHAR(64)`）由服务端生成（建议 `"SR-" + orgId + "-" + reportMonth`，≤64）。
6. **留痕口径**：业务失败 = `HTTP 200 + R.code`；断言看 `R.code`。未认证 = `HTTP 401 + code=10002`。敏感操作（B9 `data:export`）由 `@Audit(sensitive=true)` 写 `audit_log(sensitive=1, second_verify=1)`（**由第四闸联动，不得新增权限码**）。
7. **数据域（第三闸）**：读操作的机构/行级收口由 `DataScopePermissionHandler` **自动**完成，服务层**不要**再手写 `org_id` 过滤；**新增表必须显式登记，或显式论证为字典表**（不留暗区 —— D-2/D-3 已两次实证"无归属列的表在对应 scope 下被静默放行"）。
8. **主键 / 时间 / 软删**：`@TableId(type = IdType.ASSIGN_ID)`；手写 SQL 必须显式赋 `id`（表无 AUTO_INCREMENT）；时间列一律 `DATETIME`；`created_at/updated_at` 依赖 DB 默认值（实体不填充）。`export_task`/`supervise_report`/`gb_rule_mapping` **均无 `deleted` 列** → 实体不映射、查询不追加 `deleted=0`（**注意**：`eval_task` 有 `deleted`，B6 必须显式 `eq("deleted",0)`）。
9. **保留字**：新增列名**不得**撞 MySQL 8 保留字（`verify-schema.sh` 金丝雀恒 = 1）；命中即须实体侧反引号映射并同步 `ReservedWordColumnMappingTest`。`org_id` 已核非保留字。
10. **跨域只读投影**：仅限 report/supervise 向 evaluation/archive 的**只读** `@Select`；**列名严格照 `02_schema.sql`**；**禁止**写入非本域表；**禁止** `import` 兄弟模块的实体类。
11. **门禁自检**：提交前 `mvn -o verify` + `bash scripts/verify-openapi.sh` + `bash scripts/verify-schema.sh` 全绿；格式 `mvn -o -q spotless:apply`；进门前先停运行实例（防 fat jar 锁）。

---

## 10. 待明确事项（按阻塞度排序，≤7 条）

| # | 事项 | 阻塞度 | 建议处置 |
|---|---|---|---|
| 1 | **B10 契约无 `requestBody`/`query`（仅幂等键）** —— `report_month` 口径未定义（当前/上月/入参？） | **中（阻塞实现口径）** | 设计取**当前自然月**（`YearMonth.now()`）；登记为**契约待订正项**（建议补 `reportMonth` 可选入参）。**不改契约** |
| 2 | **B9 `exportType` 范围** —— 契约 enum `[EVAL_LIST, REPORT, STAT]`；D-3 拟仅实现 `EVAL_LIST` | 中 | 本期 `REPORT`/`STAT` 返 `10001`（"暂不支持"）并**登记残留**；不加新权限码 |
| 3 | **B9 `format` 越界** —— 契约 enum 含 `XLSX/PDF`；D-3 仅 CSV（裁决 2） | 低（已裁决） | `XLSX/PDF` 返 `10001`；登记残留（待接 COS/POI 时补齐） |
| 4 | **SUPERVISOR（scope=4）的机构归属** —— `data:export`/`supervise:report:submit` 均授予 SUPERVISOR，但监管类账号**可能无默认机构**（`LoginUser.orgId` 可空 → `requireOrgId()` 抛） | 中 | B9/B10 中若 `orgId==null`：返回 `PARAM_INVALID`（提示需机构上下文）而非 500；登记残留（监管跨机构上报口径待业务确认） |
| 5 | **B8 `code` 参数形态** —— 契约只声明 `code:string`；QR 实为 `...?no=RPxxx&hash=...` | 低 | 设计**兼容两种**（纯 reportNo + 完整 URL）；实现期以冒烟用例钉死 |
| 6 | **`canonicalContent` 复用** —— 当前在 `AssessmentReportService` 内为 `private` | 低 | 本期 B8 **内联复刻 + 单测钉死一致**；若后续多处引用，再抽 `public static`（登记可选重构） |
| 7 | **`retain_until` 归档覆盖** —— `RetentionChainScheduler`/`ExpiredOrderArchiver` **不覆盖 `supervise_report`**（§0 已核） | 低（已裁决 3） | D-3 **只写值**；登记残留 `D-3R?`（监管报文归档/清理归 P2-7 / D-5），**不新增 `@Scheduled`** |

**残留登记（供实施记录回填）**

| # | 残留 | 说明 |
|---|---|---|
| R1 | B9 异步大导出（>10000 走 COS）未实现 | 契约描述含异步路径；D-3 仅同步 CSV ≤5000；COS 无实现（只有配置） |
| R2 | XLSX/PDF 导出未支持 | 返 `10001`（裁决 2） |
| R3 | `export_task.expire_at` 清理/回收未实现 | 一律排 **P2-7**；D-3 只写值 |
| R4 | `supervise_report.retain_until` 归档未接线 | 排 P2-7 / D-5（裁决 3） |
| R5 | B10 `ts_token`（可信时间戳）未写入 | 库列保留 NULL；无可信时间戳服务 |
| R6 | `exportType=REPORT/STAT` 未实现 | 返 `10001`（待明确 #2） |

---

## 附：图文件

- 类图：`docs/design/D3-class-diagram.mermaid`
- 时序图：`docs/design/D3-sequence-diagram.mermaid`
- DDL 提案：`docs/reviews/D3-export-task-DDL-Patch-Proposal.sql`

> 命名沿用本仓 `docs/design/` 惯例（`D3-` 前缀），与 `D2-*.mermaid` 并列。
