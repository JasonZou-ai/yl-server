# D-3 报表域后端 · 地基核查报告（开工前置）

> 依据：用户 2026-09-26 指令「D-3 照 D-2 那套打，起手先做地基核查」。
> 方法：D-2 已证明「地基核查能改变落点」（`elder` 无 user_id 锚列 → 差点按错误方案写码），故本报告在**写任何代码前**逐表、逐码、逐文件核对。
> 结论口径：只写**已实测**的事实，每条附证据（文件 / 行号 / 命令）；推测一律标注「待验」。

---

## 0. 结论摘要（TL;DR）

| # | 发现 | 对 D-3 的影响 |
|---|---|---|
| ★1 | **D-3 的真实范围不是「报表域 3 个」，而是 7 个端点** —— 按 `D1-openapi-contract-delta.md` 的权威分派，D-3 = **B6~B10（5）+ R6 组织管理（2）** | 落点扩大：跨 **4 个模块**（evaluation / supervise / report / account） |
| ★2 | `export_task` **无 `org_id`**，仅 `operator_id`；且它**不在** `ORG_COLUMN` → scope=2 时**不加限（放行）** | 机构管理员**可见全库导出任务**（跨机构可读）；与 `care_plan` 同源教训，但方向相反（不是 500，是**静默放行**） |
| ★3 | `supervise_report` 只登记了 `ORG_COLUMN`，**未登记** `SELF_COLUMN` / `SELF_DENY_TABLES` → scope=1/5 时**放行** | 属「暗区」；须显式登记（否则老人/家属可读监管上报报文） |
| ★4 | **后端不存在 `SmsCodeService`（或任何短信验证码服务/表）** —— 用户指令 #2 的「复用已装配的 SmsCodeService」前提**不成立** | R1 并入 D-4 时，短信核身需**新建**（或改用已装配的 `SecondVerifyGuard` 语义）；D-4 工作量与前置需重估 |
| 5 | `export_task` / `supervise_report` / `eval_task` / `gb_rule_mapping` 四表**均已就绪**，`ErrorCode.EXPORT_TASK_TOO_LARGE(60001)` 已定义 | **DDL 基本可零变更**（详见待裁决 1） |
| 6 | 全仓**无 Excel 库**（POI/EasyExcel 均未引入）、**COS 仅配置无实现** | 导出产物落地方式须裁决（详见待裁决 2） |
| 7 | `verify_hash` 的**输入源自 `eval_order` 而非 `eval_report` 行** | B8 验真端点**必须落在 evaluation 模块**（否则跨模块拿不到 orderNo 等要素） |

---

## 1. 核查项①：机构列名逐表核对（`care_plan` 教训）

**核查方法**：以 `docker/mysql/init/02_schema.sql` 为唯一事实源，逐列比对 `DataScopePermissionHandler` 的四张映射表。

| 表 | DDL 实际列 | 已在 `ORG_COLUMN`？ | 已在 `SELF_COLUMN`？ | 已在 `SELF_DENY_TABLES`？ | 判定 |
|---|---|---|---|---|---|
| `export_task` | `operator_id`（**无 `org_id`**） | ❌ 否 | ✅ `operator_id` | ❌ 否 | ⚠️ **scope=2 静默放行** |
| `supervise_report` | `org_id` ✅ | ✅ `org_id` | ❌ 否 | ❌ 否 | ⚠️ **scope=1/5 放行（暗区）** |
| `eval_task` | `org_id` ✅ + `assessor_id` | ✅ `org_id` | ✅ `assessor_id` | ❌ | ✅ 双登记，正确 |
| `gb_rule_mapping` | 无 `org_id` / 无归属用户列 | ❌ | ❌ | ❌ | ✅ 字典类，**有意放行**（与 `eval_item` 同口径） |
| `org` | 主键 `id` 即机构 | ✅ `id` | ❌ | ❌ | ✅ 正确（`org` 本表按 `id` 过滤） |

**证据**：`DataScopePermissionHandler.java` L79–90（`ORG_COLUMN`，含 `supervise_report`→`org_id`）、L93–111（`SELF_COLUMN`，含 `export_task`→`operator_id`）、L122–132（`SELF_DENY_TABLES`）。

### ★发现 2：`export_task` 在 scope=2 下静默放行（跨机构可读）

- 机制：处理器对「未登记表」**返回 `null` = 不加限**（L203 / L228）。`export_task` 无机构列 → 无法登记进 `ORG_COLUMN` → 机构管理员查询导出任务时**不带任何机构条件**。
- 性质：与 D-2 揪出的「权限已授 ≠ 数据可达」**互为镜像** —— 这里是「**无归属列 ⇒ 数据过度可达**」。
- 为什么 D-2 没暴露：`export_task` 此前**无任何读写代码**，表存在但未接线。
- 处置：见**待裁决 1**（A 补锚列 / B 零 DDL 服务层收口）。

### ★发现 3：`supervise_report` 在 scope=1/5 下放行（暗区）

- 机制：该表既不在 `SELF_COLUMN`（无本人列）也不在 `SELF_DENY_TABLES` → 走 L203 的 `null`（放行）。
- 现状风险：第二闸 `supervise:report:submit` 只授予 `ORG_ADMIN`/`SUPERVISOR`，故**当前不可利用**；但符合项目「不留暗区」红线，**须显式登记**。
- 处置：把 `supervise_report` 追加进 `SELF_DENY_TABLES`（scope=1/5 → 恒假）。**纯收紧、零功能损失**（能过第二闸的两个角色 scope 分别为 2 / 4，均不受影响）。

> **对照 `care_plan` 教训的差异**：`care_plan` 是「**登记了不存在的列**」→ 拼出未知列 → **500 显性失败**；本次两处是「**该登记没登记**」→ **静默放行**。后者更隐蔽，正是 D-2 识别出的缺陷类的另一面。

---

## 2. 核查项②：17 个 `planned` 里「报表域」占哪几个

**权威依据**：`docs/change/D1-openapi-contract-delta.md`
- §B 类（契约已定义、后端未实现）10 操作 → 「分派 **D-2 / D-3**」
- L108–112：**B6~B10 逐条标注归属 `D-3`**
- L199：反向缺口 **R6（组织管理）→ `D-3`**
- L271：`D-3 报表域后端 … + R6 组织管理；B6~B10；export_task/supervise_report 实体；retain_until 写入口须与发布/上报链路绑定`
- L349：复核确认 ✅

### D-3 交付面 = 7 个端点（**落点修正**：不止 3 个）

| 编号 | 方法 + 路径 | operationId | 权限码 | 数据域 | 归属模块（本轮裁决） |
|---|---|---|---|---|---|
| B6 | `GET /api/v1/evaluation/tasks` | `evalTaskList` | `evaluation:task:read` | org（by-role: SUPERVISOR→all） | `yl-module-evaluation` |
| B7 | `GET /api/v1/rule/mapping` | `ruleMapping` | `rule:view` | all（字典） | `yl-module-evaluation` |
| B8 | `GET /api/v1/reports/verify` | `reportVerify` | **none（public）** | public | `yl-module-evaluation` |
| B9 | `POST /api/v1/supervise/exports` | `superviseExportCreate` | `data:export` + **二次验证** | org | `yl-module-report` |
| B10 | `POST /api/v1/supervise/report` | `superviseReportSubmit` | `supervise:report:submit` | org | `yl-module-supervise` |
| R6-1 | `GET /api/v1/account/orgs` | `orgList` | `account:org:manage \| :read` | org | `yl-module-account` |
| R6-2 | `GET /api/v1/account/orgs/{orgId}` | `orgDetail` | `account:org:manage \| :read` | org | `yl-module-account` |

> 其余 10 个 `planned` 归属：`authLogout` → D-4；`familyBinding*` / `familyReport*` / `familyTrend` / `familyCarePlan`（F1~F9）= 9 个 → **D-4**。
> 用户问题「报表域占哪几个」的准确答案：**严格挂 `report`/`supervise` 标签的 3 个（B8/B9/B10）**；**按 D-1 的 D-3 分派共 7 个**。

### B6 为什么要落 evaluation 模块

`eval_task` 表由 `EvalTask` 实体 + `EvalTaskMapper` 持有，二者均在 `yl-module-evaluation`（`yl-module-report`/`yl-module-supervise` 仅依赖 `yl-api`，**无法**引用兄弟模块的实体/Mapper）。模块间是**平级兄弟**关系（account 与 evaluation 互不依赖），跨模块编排只能放 `yl-bootstrap`（ADR-0004 判例）。

---

## 3. 核查项③：导出产物落哪（D-4 前置）

| 维度 | 实测结论 | 证据 |
|---|---|---|
| 存储键口径 | 需与 `export_task.file_cos_key` 对齐 | DDL L609 `file_cos_key VARCHAR(255)` |
| 现有落盘范式 | **报告 PDF 走本地目录**：`yl.report.pdf.dir` + 键 `report/<reportNo>.pdf`，注释明示「键名与对象存储形态一致，后续接 COS 只替换写入实现」 | `ReportPdfService.java` L44 / L75 / L112 |
| COS 实现 | **只有配置**（`yl.storage.cos.*`，region/bucket/secret/prefix）+ 根 pom `cos_api` 版本管理，**全仓无任何 COS 调用代码** | `application.yml` storage 段；`grep -rn "CosClient" → 0` |
| Excel 生成能力 | **无 POI / EasyExcel**；仅 `hutool-all`（其 POI 模块需另引 POI 才可用） | 根 pom L128 `hutool-all`；无 `poi*` artifact |
| 导出上限错误码 | `EXPORT_TASK_TOO_LARGE(60001,"导出数据量超出单次上限，请缩小范围")` **已定义** | `ErrorCode.java` L35 |

**推论**：本轮导出**不应**引入 COS（无实现）与 XLSX（无库）；应**复刻 PDF 的本地目录范式**（新增 `yl.export.dir`，键 `export/<taskNo>.<ext>`），使后续接 COS 时**只换写入实现、不改键名**。格式能力见**待裁决 2**。

---

## 4. 核查项④：4 个已验证的坑 → D-3 的具体动作

| 继承的坑（D-2 实证） | D-3 的落地动作（写进实现卡） |
|---|---|
| **① 权限码只认 `sys_role_permission` 权威事实**（`order:void` 教训：不授予任何角色 ⇒ 单码写法变「谁都进不去」） | B8 是 public（`none`）+ `security: []`，**不进** `ControllerAuthorizationCoverageTest` 白名单**必须显式登记**（白名单现 4 条，新增即第 5 条，且**禁冗余**）；B9 `data:export` 授予 ORG_ADMIN(3019)/SUPERVISOR(3020) 两角色——**二次验证由 `need_second_verify=1` 联动第四闸**，不得靠新增权限码 |
| **②「权限已授 ≠ 数据可达」检查法** | 本报告 §1 已用它揪出 **2 处新例**（`export_task` 反向 / `supervise_report` 暗区）。实现后须补 2 条数据域单测钉死 |
| **③ 断言下沉 `R.code` / 魔数**（业务失败一律 HTTP 200） | B8/B9/B10 的冒烟断言**必须看 `R.code`**；B9 导出产物**必须验魔数**（CSV: `\uFEFF`/首行表头；PDF: `%PDF`）——否则「没返回文件」无断言在守 |
| **④ `mvn verify` 前先停服务**（运行实例锁 fat jar → repackage 失败，单测全绿却 BUILD FAILURE） | 进入门禁阶段前先 `netstat -ano \| grep :8080` → `taskkill`；本报告写作时**服务在跑**，须先停 |

---

## 5. 附带发现（改变 D-4 落点）：`SmsCodeService` 不存在

| 核查 | 结果 |
|---|---|
| `find . -name "*SmsCode*"` | **0 命中** |
| `02_schema.sql` 中 `sms` / `verify_code` / `captcha` 表 | **0 命中** |
| 全仓 `短信` 相关 Java 类 | **0**（仅设计文档 `2026-09-08-…/stage3_开发/15_短信验证码通道技术方案.md`） |
| 已装配的替代物 | `SecondVerifyGuard`（TTL 凭据）+ `SecondVerifyVerifier`（`ConfigurableSecondVerifyVerifier`，比对 `yl.security.second-verify.dev-code`）——**语义是「操作二次验证」，不是「身份核身」** |

**结论**：用户指令 #2「elder↔user 绑定流程（短信验证码核身，**复用已装配的 SmsCodeService**）」的复用前提**不成立**。
→ R1 并入 D-4 时，核身通道需**三选一**（D-4 设计时裁决）：(a) 新建短信通道（有技术方案文档，需引入短信服务商 SDK + 限流 + 幂等）；(b) 复用 `SecondVerifyGuard` 语义（**语义漂移风险**：二次验证 ≠ 身份核身）；(c) 机构线下核验（零技术成本，但端侧体验降级）。
**本报告不擅自裁决，登记为 D-4 的第一道待裁决项。**

---

## 6. 待裁决清单（4 项，均附默认建议）

| # | 议题 | 选项 | 默认建议 |
|---|---|---|---|
| **1** | `export_task` 无机构锚列 → scope=2 跨机构可读 | **A** 补可空 `org_id` 锚列（+1 列 DDL，非破坏，可 `DROP` 回退）并登记 `ORG_COLUMN`；**B** 零 DDL：服务层按 `operator_id` 收口（scope=3/4 不限、其余按本人）+ 契约 `x-data-scope.extra` 写明 | ✅ **用户 2026-09-28 拍板 A**（见 §6.1） |
| **2** | 导出格式能力 | **A** 引 POI 支持 XLSX+CSV；**B** 本轮仅 **CSV**（零新依赖），XLSX/PDF 返 `10001` 暂不支持并登记残留 | **B** —— 本轮是安全/鉴权收口批次，**不引新依赖**；CSV 已可端到端验证「导出→落盘→魔数→留痕」全链 |
| **3** | B10 `retain_until` 是否已挂归档链路 | 需核 `RetentionChainScheduler` / `ExpiredOrderArchiver` 是否覆盖 `supervise_report` | **实现前必查**；D-1 已提醒「否则归档任务空转」。若未覆盖，B10 只写 `retain_until` 并**登记残留**（不扩调度范围，避免越过 D-5 边界） |
| **4** | B8 验真是否返回 `gradeCode` / `publishedAt` | 契约：仅返回「有效/无效 + 等级 + 出具时间」，**不返回个人信息** | 按契约实现：`{valid, gradeCode, publishedAt}`，**绝不返回**姓名/身份证/手机号 |

---

### 6.1 裁决记录（用户 2026-09-28）

| # | 裁决 | 内容与理由 |
|---|---|---|
| **1** | ✅ **A** | **`export_task` 补可空 `org_id` 锚列**（+1 列 DDL，已获授权）。用户理由：B 只修「跨机构可读」，修完 scope=2 机构管理员**只剩「看自己」**——机构导出列表的**功能语义就死了**，回头仍须补这条 DDL，不如按 D-2 `elder.user_id` 先例**一次做对**。<br>落地口径三条：① 可空（NULL 行在 scope=2 下因 `IN` 不匹配而**自然不可见**，即"NULL 失败即拒"）；② **回填**既有行（由 `operator_id` 经机构成员关系推导）；③ 登记进 `ORG_COLUMN`（`export_task → org_id`），与既有 `SELF_COLUMN`（`operator_id`）并列共存，同 `eval_task` 范式。<br>**非破坏性**：`ALTER TABLE ... DROP COLUMN org_id` 即可回退。<br>**注意**：`org_id` 非保留字（金丝雀计数须仍为 **1**）；schema 断言 **82 → 83**。 |
| **2** | ✅ **B**（双方一致） | 本轮导出**仅 CSV**（零新依赖）；XLSX/PDF 返 `10001` 并登记残留。CSV 须带 **UTF-8 BOM + 公式注入转义**（`= + - @ \t \r` 前缀加 `'`），须有冒烟注入样本断言。 |
| **3** | ✅ 只写值（双方一致） | B10 `retain_until` **实现前必查** `RetentionChainScheduler`/`ExpiredOrderArchiver` 是否覆盖 `supervise_report`；未覆盖则只写值 + 登记残留，**不扩 D-5 的调度范围**。 |
| **4** | ✅ 最小返回面（双方一致） | B8 按契约只返 `{valid, gradeCode, publishedAt}`，**绝不返回**姓名/身份证/手机号。 |

### 6.2 D-3 边界约束（写入任务卡，防手滑）

- **D-3 内不碰任何文件删除逻辑**：`export_task.expire_at`（文件过期清理）的**清理语义与扫描/回收实现一律排给 P2-7**（孤儿文件机制卡），D-3 只**写入** `expire_at` 值，**不得**实现删除/回收/定时清理——避免与 P2-7 的调度与回收站口径打架。
- **不新增 `@Scheduled`**：全仓唯一调度仍在 `RetentionChainScheduler`（ADR-0004）。D-3 若感到需要定时清理，登记到 P2-7 而非自行添加。

---

## 7. D-3 门禁计划（数字只许向上，禁止回头改既有断言）

| 门禁 | 当前基线（D-2 收口） | D-3 目标 | 说明 |
|---|---|---|---|
| `mvn -o verify` 单测 | **148 例 / 0 失败** | ≥148（只增） | 新增 7 端点单测 + 2 条数据域单测 |
| `verify-schema.sh` | **82 断言** | ≥82 | 仅当采纳待裁决 1-A（+1 列）才 +1 断言 |
| `verify-openapi.sh` 漂移 | **8/8**；implemented 26 / planned 17 | 8/8；implemented **33** / planned **10** | 7 个 `planned → implemented`，漂移双向仍须 = 0 |
| 冒烟 `smoke-report-review-chain.sh` | **53 通过 / 0 失败** | ≥53（只增） | 新增 `[8/8]` 报表与监管段（含魔数 + `R.code` 断言） |
| `Checkstyle` / `SpotBugs` | 0 违规 / 0 | 0 / 0 | SpotBugs **多余 suppression 亦阻断** |

---

## 8. 实施顺序（沿用 D-2 的串行纪律）

```
① 裁决本报告 §6（4 项）
② 数据域登记修正（supervise_report → SELF_DENY_TABLES；export_task 按裁决 1 处置）
③ 契约先行：7 个 planned → implemented 的 yaml 冻结（含 x-* 扩展键）
④ 分模块实现（串行，禁并行改同一 Maven 工程）：
   evaluation（B6/B7/B8）→ account（R6-1/R6-2）→ report（B9）→ supervise（B10）
⑤ 单测 + 覆盖守卫（白名单 +1 条：B8 public）
⑥ 门禁三件套 + 冒烟增量
⑦ QA 独立验证（含突变测试：删登记 / 翻 x-status / 摘注解 应全红）
```

---

## 附：本次核查的命令与证据（可复现）

```bash
# ① 表列事实源
awk '/CREATE TABLE IF NOT EXISTS `export_task`/,/ENGINE=InnoDB/' docker/mysql/init/02_schema.sql
awk '/CREATE TABLE IF NOT EXISTS `supervise_report`/,/ENGINE=InnoDB/' docker/mysql/init/02_schema.sql
awk '/CREATE TABLE IF NOT EXISTS `eval_task`/,/ENGINE=InnoDB/' docker/mysql/init/02_schema.sql
awk '/CREATE TABLE IF NOT EXISTS `gb_rule_mapping`/,/ENGINE=InnoDB/' docker/mysql/init/02_schema.sql
# ② 数据域登记现状
sed -n '79,132p' yl-modules/yl-module-account/src/main/java/cn/yl/modules/account/security/DataScopePermissionHandler.java
# ③ planned 操作归属
grep -n "D-3\|B6\|B7\|B8\|B9\|B10\|R6" docs/change/D1-openapi-contract-delta.md
# ④ 短信通道存在性
find . -name "*SmsCode*" -not -path "*/target/*"      # → 0
# ⑤ 导出/存储能力
grep -n "artifactId" pom.xml | grep -i "poi\|excel\|cos"
```
