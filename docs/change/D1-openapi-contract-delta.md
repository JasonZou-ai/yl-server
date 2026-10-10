# D-1 契约差异清单（`yl-api.yaml` ↔ 实现 ↔ PRD 三方比对）

> 卡片：**D-1（任务集 D 首卡）** ｜ 类型：契约补登（变更） ｜ 日期：2026-09-23（盘点）/ 2026-09-24（裁决 + 实施）
> 状态：**✅ 已裁决并实施** —— 5 项裁决全部按默认建议（A）通过；契约冻结为 `info.version 1.1.0`；
> 配套门禁 2 处（`scripts/verify-openapi.sh` ＋ `OpenApiContractTest`）；**CR-M2-006 插队并已闭环**
> 关联：`openapi/yl-api.yaml`（**v1.1.0**）｜ CR-M2-001 §2.4(b) 反向缺口 ｜ CR-M2-005 家属端缺口 ｜ **PRD YL-PRD-v1.2**（§1.2 / §2.2 / §3 / §4.2）
> 前置结论：**本卡只产出差异清单与口径裁决建议，不动任何业务代码。**（实施见 §十一）

---

## 一、TL;DR

1. **契约与实现已系统性漂移**：契约 22 个操作里**只有 3 个**（`auth/refresh`、`auth/second-verify`、`system/health`）与实现完全对齐；9 个是「同一业务语义、两套路径」，10 个是「契约写了、后端根本没有」。
2. **反向发现（此前无人登记）**：后端有 **9 个已上线操作在契约里完全不存在** —— 包括复核三端点（publish/reject/void）、照护方案四端点、报告 PDF、题库元信息。**契约作为「实现镜像」的功能是失效的**。
3. **★★ 盘点副产品：一个真实的 P0 越权缺陷（已立卡 CR-M2-006）** —— `GET /api/v1/evaluation/reports/{reportNo}/pdf` **可被任何已登录用户跨机构下载任意报告 PDF**。已三重实证：① `ReportPdfService.read()` 只做格式白名单后**直接读文件、零 DB 查询** → 第三闸（数据域）根本无法介入；② 下载 **DB 记录数为 0** 的文件仍返回 `200 + %PDF-`；③ 把报告改挂 `org_id=999` 后用 `org_id=1` 令牌请求，**同一资源**走文件的路径 `200`、走 DB 的对照路径被数据域拦（"报告尚未生成"）。**建议插队至 D-2 之前修复**（~10 行），详见 **CR-M2-006**。
4. **一个阻断面**：家属（`FAMILY`）与老人（`ELDER`）的 `data_scope = 1`（本人），而现有第三闸在 scope=1 下对 `elder` / `eval_report` / `elder_family_bind` 等表**一律恒假（失败即拒）**。**D-4 就算把家属端 7 个接口全写完，家属也查不到任何数据。** 必须先在 D-1 裁决数据域口径。
5. **一个范围修正**：`getMessages()`（消息中心）属 PRD §3 功能点 21/22，**已由 CR-M2-001 裁决移入 V2**，不应纳入 D-4。
6. **契约扩展键提案**：D 的验收口径要求「新增接口全部带 `x-required-permission` 与**数据域口径**」——当前契约**没有数据域与敏感字段的机器可读标注**。本卡提出 `x-data-scope` / `x-sensitive-fields` 两个扩展键与契约门禁断言，作为可验证落点。

**需要你拍板 5 项**（均在 §七，每项附默认建议）。**其中第 2 项（数据域语义）不裁决则 D-4 无法开工。**
**另有 1 项安全缺陷（CR-M2-006）建议插队处置**，处置权在你（§五.3）。

---

## 二、盘点口径与可复现证据

### 2.1 三方比对源

| 源 | 文件 | 权威性 |
|---|---|---|
| **需求** | `PRD_YL-PRD-v1.2.md`（2026-09-19） | **权威**。契约与实现均以它为准 |
| **契约** | `openapi/yl-api.yaml`（OpenAPI 3.0.3 / info.version 1.0.0） | 目标态，但**已过期** |
| **实现** | `yl-modules/**/controller/*.java` 的 `@RequestMapping` + `@*Mapping` | 事实 |

### 2.2 可复现命令

```bash
cd <yl-server>

# ① 契约：路径 × 方法 × 权限（22 操作）
grep -n "^  /api/v1\|^    \(get\|post\|put\|delete\|patch\):\|x-required-permission" openapi/yl-api.yaml

# ② 实现：控制器全量端点
grep -rn --include=*.java -E "@(Get|Post|Put|Delete|Patch)Mapping" . | grep -v /target/

# ③ 契约侧家属端检索（应命中 0 —— 证实零供给）
grep -in -E "family|bind|trend|elder" openapi/yl-api.yaml

# ④ 数据域：失败即拒表 + 角色 scope
sed -n '87,96p' yl-modules/yl-module-account/src/main/java/cn/yl/modules/account/security/DataScopePermissionHandler.java
mysql -e "SELECT role_code, data_scope FROM sys_role WHERE deleted=0"
```

### 2.3 现状快照

| 项 | 值 |
|---|---|
| 契约 | 21 路径 / **22 操作** / 8 schema（`R` `PageResult` `LoginRequest` `TokenPair` `ElderCreate` `ElderView` `EvalOrder` `GradeResult`） |
| 实现 | 7 个控制器 / **22 端点** |
| 权限点（库） | **22 个**，其中 2 个**无任何角色持有**（`evaluation:order:void`、`account:family:unbind`，均为「运行时提权」设计） |
| 敏感点 | **4 个**（`data:export`、`evaluation:order:void`、`account:family:unbind`、`data:reveal`） |
| 已有实体 | **22 个**（无 `Elder` / `ElderFamilyBind` / `ElderAuthorization` / `ExportTask` / `SuperviseReport` / `OrgBed`） |
| 档案域 DDL | **已就绪**（`elder` / `elder_family_bind` / `elder_authorization` / `consent_record` / `org_bed` 均在 `02_schema.sql`） |

---

## 三、差异总览（四类）

| 类 | 定义 | 契约侧 | 实现侧 | 处置 |
|---|---|:--:|:--:|---|
| **A** | 路径漂移：语义等价、路径不同 | 9 操作 | 10 端点 | 契约**向实现对齐**（待裁决 1） |
| **B** | 契约已定义、后端未实现 | 10 操作 | — | 分派 **D-2 / D-3** |
| **C** | **后端已实现、契约完全缺失** | — | **9 端点** | 契约**补登**（本卡主体） |
| **D** | PRD 要求、契约与实现皆无 | — | — | 新增契约 = **D-2 / D-3 / D-4 范围** |

> 契约 22 = 一致 3 + A 9 + B 10。
> 实现 22 = 一致 3 + A 10 + C 9。

---

## 四、明细表

### 4.1 A 类 —— 路径漂移（契约须向实现对齐）

| # | 契约路径（现状） | 实现路径（事实） | 契约权限 | 说明 |
|---|---|---|---|---|
| A1 | `POST /auth/login`（`channel` 在 body） | `POST /auth/login/{channel}` | `none` | 渠道由 path 承载；**四端 `real-api.ts` 已按实现路径调用并验收通过** |
| A2 | `POST /eval/orders` | `POST /evaluation/assessments` | `evaluation:order:create` | — |
| A3 | `PUT /eval/orders/{orderId}/answers` | `PUT /evaluation/assessments/{orderId}/answers` | `evaluation:item:input` | — |
| A4 | `POST /eval/orders/{orderId}/grade` | `POST /evaluation/grade` | `evaluation:item:input` | **语义亦有差异**：实现为无状态试算（无 `orderId`），不入库 |
| A5 | `POST /eval/orders/{orderId}/submit` | `POST /evaluation/assessments/{orderId}/submit` | `evaluation:item:input` | — |
| A6 | `POST /eval/orders/{orderId}/review` | `POST .../review/publish` + `.../review/reject` | `evaluation:order:review` | **1 个契约操作 → 2 个实现端点**（动作显式化） |
| A7 | `GET /rule/versions` | `GET /evaluation/rule-meta` | `rule:view` | 实现单端点返回规则元信息 |
| A8 | `GET /reports/{reportId}` | `GET .../assessments/{orderId}/report` | `report:view` | 实现以 `orderId` 为轴（MVP 单报告/单：无需 `reportId`） |
| A9 | `POST /reports/{reportId}/generate` | `POST .../assessments/{orderId}/report` | `evaluation:order:review` | 同上 |

> **注**：A6 的 `review/void` 因契约里根本没有 void 语义，归入 C 类（C2）。

### 4.2 B 类 —— 契约已定义、后端未实现（分派子卡）

| # | 路径 | 方法 | 契约权限 | 敏感 | 分派 | 备注 |
|---|---|---|:--:|:--:|---|---|
| B1 | `/api/v1/auth/logout` | POST | `none` | — | **D-4 顺带** | 端侧 `logout()` 目前仅清本地令牌，未调后端（无服务端黑名单） |
| B2 | `/api/v1/elders` | POST | `elder:archive:create` | — | **D-2** | 建档（PRD 功能点 8） |
| B3 | `/api/v1/elders` | GET | `elder:archive:read` | — | **D-2** | 档案列表（分页） |
| B4 | `/api/v1/elders/{elderId}` | GET | `elder:archive:read` | — | **D-2** | 档案详情（脱敏） |
| B5 | `/api/v1/security/reveal` | POST | `data:reveal` | **✅** | **D-2** | 查看明文（二次验证）。敏感码与 `PermissionAspect` 均已就绪，**只缺端点** |
| B6 | `/api/v1/eval/tasks` | GET | `evaluation:task:read` | — | **D-3** | 评估任务列表（`EvalTask` 实体已存在） |
| B7 | `/api/v1/rule/mapping` | GET | `rule:view` | — | **D-3** | 规则条款映射（报告条款回溯用） |
| B8 | `/api/v1/reports/verify` | GET | `none` | — | **D-3** | 二维码验真（PRD P02 要求；需限频） |
| B9 | `/api/v1/supervise/exports` | POST | `data:export` | **✅** | **D-3** | 批量导出（`export_task` 表已就绪） |
| B10 | `/api/v1/supervise/report` | POST | `supervise:report:submit` | — | **D-3** | 监管上报（`supervise_report` 表已就绪，含 `retain_until`） |

### 4.3 C 类 —— ★ 后端已实现、契约完全缺失（本卡补登主体）

| # | 实现路径 | 方法 | 实现侧权限 | 备注 |
|---|---|---|---|---|
| C1 | `/api/v1/evaluation/assessments/{orderId}` | GET | `EVAL_ORDER_CREATE`(OR `TASK_READ`) | 评估单详情 |
| C2 | `/api/v1/evaluation/assessments/{orderId}/review/void` | POST | `EVAL_ORDER_REVIEW` **OR** `EVAL_ORDER_VOID` | **敏感**（含敏感码 → 强制第四闸） |
| C3 | `/api/v1/evaluation/assessments/{orderId}/review-logs` | GET | `REVIEW_READ` OR `REVIEW` OR `TASK_READ` | 复核流水（承载 `evaluation:order:review:read` 反向缺口） |
| C4 | `/api/v1/evaluation/assessments/{orderId}/care-plan` | POST | `CARE_PLAN_EDIT_SUGGEST`(OR `EDIT`) | 生成/建议照护方案 |
| C5 | `/api/v1/evaluation/assessments/{orderId}/care-plan/confirm` | POST | `CARE_PLAN_EDIT` | 确认照护方案 |
| C6 | `/api/v1/evaluation/assessments/{orderId}/care-plan` | GET | `REPORT_VIEW` | 读取照护方案 |
| C7 | `/api/v1/evaluation/assessments/{orderId}/report/pdf` | POST | `REPORT_VIEW`(OR `REVIEW`) | 生成 PDF（幂等） |
| C8 | `/api/v1/evaluation/items` | GET | `EVAL_TASK_READ`(OR `ITEM_INPUT`) | 26 题元信息（题库） |
| C9 | `/api/v1/evaluation/reports/{reportNo}/pdf` | GET | `REPORT_VIEW` | PDF 下载（按 `reportNo`） |

> **C7 / C9 是同一资源的两种粒度**（生成 vs 下载、`orderId` vs `reportNo`）。补登时建议**统一以 `reportNo` 为轴**并保留 `orderId` 便捷入口，或明确二者主从关系（待裁决 1 的附带项）。

### 4.4 D 类 —— PRD 要求、契约与实现皆无（= 后续子卡范围）

见 §六 逐条口径（6 类反向缺口 + 家属端）。

---

## 五、★ 两个架构缺口（阻断面，必须裁决）

### 5.1 家属/老人的数据域无法表达「关系级可见性」——**阻断 D-4**

**事实链**：

| 环节 | 现状 |
|---|---|
| 角色 scope | `FAMILY` → `data_scope = 1`（本人）；`ELDER` → `1` |
| 第三闸逻辑 | `scope=1` 时：命中 `SELF_COLUMN` → 按归属用户列过滤；否则若在 `SELF_DENY_TABLES` → **恒假**；未登记 → **放行** |
| `SELF_DENY_TABLES` | `eval_report`、`report_dimension_score`、`eval_answer`、`eval_evidence`、`consent_record`、**`elder`**、`elder_authorization`、**`elder_family_bind`**、`org_bed`、`care_task` |
| 代码自述 | `DataScopePermissionHandler` Javadoc：*"老人/家属的『本人』语义需依托 `user ↔ elder` 档案链路（ER-01 未落地），故 `eval_report` 等表对 scope=1 目前只能失败即拒（老人暂看不到本人报告），待档案域落地后改为按 `elder_id` 过滤"* |
| **推论** | **家属端接口全部实现后，家属查询 `elder` / `eval_report` 仍返回空集** —— D-4 的验收（"家属看报告 → 趋势 → 照护建议"）**必然失败** |

**还有一个附加事实**：`elder` 表**没有 `user_id` 列**（仅 `org_id` / `created_by`），因此「老人本人看自己档案」在 scope=1 下**无法用现有列表达**。好消息是 PRD §2.1 明确老人"**可不注册，家属代管**"、主用终端为"小程序（家属代办）" → **ELDER 的账号场景在本期近乎为空**，可维持现状（失败即拒），无需为它增列。

**三个候选方案**：

| 方案 | 做法 | 代价 | 风险 |
|---|---|---|---|
| **A（建议）** | 新增 `DataScope.FAMILY(5)`（关系级）；`sys_role.data_scope` 把 `FAMILY` 由 1 改 5；`DataScopePermissionHandler` 增 `scope==5` 分支：`elder` / `eval_report` / `eval_order` 等追加 `elder_id IN (SELECT elder_id FROM elder_family_bind WHERE family_user_id = ? AND status = 2 AND deleted = 0 AND (auth_end_at IS NULL OR auth_end_at > NOW()))`；`elder_family_bind` 自身按 `family_user_id` 过滤 | **零 DDL**（仅改种子值 + 新增枚举码）；与四闸模型一致；一处生效、全表兜底 | 首次引入"子查询型"数据域条件，需单测锁死 |
| B | 不加 scope，在**服务层**逐接口显式校验绑定关系 | 无需动数据域 | **漏改即越权，无兜底**；与"第三闸兜底"设计原则相悖 |
| C | 复用 `scope=1`，重定义"本人 = 绑定可达" | 改动最小 | **语义混淆**：`ELDER` 与 `FAMILY` 行为被耦合；`SELF_COLUMN` 的"自己"与"我绑定的人"混为一谈 |

> **建议 A**，并配套：D-4 的越权用例（家属拼他人 `reportId` / 用他人 `relationId`）必须在**数据域层**被拒，而非仅靠服务层。

### 5.2 权限码供给与需求的两处不一致

| # | 事实 | 冲突点 | 建议 |
|---|---|---|---|
| a | PRD §4.2 绑定五步含「**③ 老人确认**」；但权限矩阵只给老人 `account:family:bind:reject`（**无 `:confirm`**） | PRD 流程 vs 权限码口径 | **沿用现有码**（待裁决 5）：老人侧只提供"拒绝"，放行由「④ 机构审核」承载；等价于"默认通过 + 可拒绝" |
| b | 家属需读报告 / 趋势 / 照护建议；但库里**无** `family:*:read` 类码 | 要不要新增权限码 | **不新增**（待裁决 3）：复用 `report:view` + 关系级数据域。与 CR-M2-001 对 `elder:archive:read` 的裁决**完全同构**（"权限点全角色授予、可见范围由第三闸控制"） |

### 5.3 ★★ 报告 PDF 下载越权（C 类盘点的副产品，**已立卡 CR-M2-006**）

**这是本卡盘点动作顺带挖出的真实 P0 缺陷**，性质与 CR-M2-002 同源（基建已备、某条路径没接上），但危害更直接。

| 项 | 内容 |
|---|---|
| 端点 | `GET /api/v1/evaluation/reports/{reportNo}/pdf`（C9） |
| 现象 | **任何已登录用户（含家属、老人、其他机构评估员）知道 `reportNo` 即可下载任意报告 PDF 全文**（姓名、等级、分项得分、照护建议） |
| 根因 | ① `ReportPdfService.read()` 仅做格式白名单后**直接 `Files.readAllBytes()`，零 DB 查询** → 第三闸是 MyBatis 拦截器，**无 SQL 可改 → 完全失效**；② `ReportController` **无 `@RequiresPermission`** → 第二闸亦不生效 |
| 三重实证 | ① 代码：`read()` 125–139 行无 Mapper 调用；② 下载 **DB 记录数 = 0** 的孤儿文件仍返 `200 + %PDF-`（28788 字节）；③ **同资源对照**——报告改挂 `org_id=999` 后，`org_id=1` 令牌走文件路径得 `200`（20445 字节），走 DB 的 `.../assessments/{orderId}/report` 被数据域拦（`10004 报告尚未生成`）→ **缺陷精确定位在 `read()`，而非数据域配置** |
| 同类 | 另有 3 个端点无第二闸（`GET /evaluation/items`、`POST /evaluation/grade`、`GET /evaluation/rule-meta`），均低敏静态数据，风险低但建议同批补 |
| 修复 | `read()` 先按 `report_no` 经 Mapper 反查（第三闸自动生效）→ 查不到统一落 `R.code=10004`（**非 HTTP 404**，见 §11.6）；`download()` 补 `@RequiresPermission(REPORT_VIEW)`。约 10 行 |
| 处置建议 | **插队至 D-2 之前**（安全项；成本远低于 D-2）。**不阻塞 D-1** |
| 与 D-2 的耦合 | D-2 落地关系级数据域后，本修复自动覆盖家属场景，无需二次改动 |

> **为什么这条要写在 D-1 里**：C 类端点（实现有、契约无）在补登契约时**必须同时核对鉴权链**，否则会把一个"契约缺失 + 鉴权裸奔"的端点当作正常端点补进契约。本次正是这么发现的。**建议把"补登 C 类端点前先核验第二/三闸"写入 D-1 执行清单**（§十 已加）。

---

## 六、逐条口径（权限码 × 数据域 × 敏感字段）

### 6.1 6 类反向缺口（CR-M2-001 §2.4(b)）逐条落位

| # | 权限点 | 契约应补路径 | 敏感 | 数据域 | **盘点修正** |
|---|---|:--:|:--:|---|---|
| R1 | `evaluation:order:void` | `POST /eval/orders/{orderId}/void` | **✅** | 机构 | **✅ 实际已实现**（C2：`.../review/void`，OR 语义 + 强制第四闸）。**反向缺口已闭合，只是契约未登** |
| R2 | `account:family:unbind` | `POST /family/unbind` | **✅** | 关系 | 改判为 `POST /family/bindings/{id}/unbind`（与 R3 同轴，见 §6.2 路径统一） |
| R3 | `account:family:bind` | `POST /family/bind` | — | 关系 | 改判为 `POST /family/bindings` |
| R4 | `account:family:bind:reject` | `POST /family/bind/{id}/reject` | — | 关系 | 改判为 `POST /family/bindings/{id}/reject`（**老人侧**动作） |
| R5 | `care:plan:edit` / `care:plan:edit:suggest` | `PUT /care/plans/{planId}` | — | 机构 | **✅ 部分已实现**（C4/C5：生成 + 确认）。契约需按实现改判为「按 `orderId` 生成 + 确认」两个动作；**`PUT /care/plans/{planId}` 的"按 planId 编辑"当前无实现**，建议 D-2 顺带评估是否保留 |
| R6 | `account:org:manage` / `:read` | `GET /account/orgs`、`GET /account/orgs/{orgId}` | — | 机构 | 未实现（`Org` 实体已有，无 Controller）→ **D-3** |
| R7 | `evaluation:order:review:read` | 无需新增路径 | — | 机构 | **✅ 已由 C3（`review-logs`）承载** |

> **盘点净结论**：6 类反向缺口里，**3 类（R1/R5 部分/R7）实际已在实现中闭合**，只是契约未登、路径不同。真正的新增工作量收敛为 **R2/R3/R4（家属绑定）+ R6（组织管理）**。

### 6.2 家属端接口（CR-M2-005 承接）逐条口径

**★ 路径统一原则**：CR-M2-005 的端侧期望（`/family/reports`、`/family/trend`、`/family/care-plan`）与 CR-M2-001 反向缺口（`/family/bind`、`/family/unbind`）**两套路径互不一致**。本卡统一为**以 `{relationId}` 为轴的资源化路径**，杜绝两套 family 契约：

| # | 端侧函数（`real-api.ts`） | 契约路径（**本卡建议**） | 方法 | 权限码 | 数据域 | 敏感字段 | 子卡 |
|---|---|---|---|---|---|---|---|
| F1 | `getMyBindings()` | `/api/v1/family/bindings` | GET | `account:family:bind` | **关系**（`family_user_id = 自己`） | — | D-4 |
| F2 | `requestBinding(input)` | `/api/v1/family/bindings` | POST | `account:family:bind` | 写入（校验 `elderId` 存在） | `proofCosKey`（监护证明） | D-4 |
| F3 | ——（老人/机构侧，反向缺口 R4） | `/api/v1/family/bindings/{id}/reject` | POST | `account:family:bind:reject` | 关系 | — | D-4 |
| F4 | ——（机构侧审核通过，反向缺口 R3） | `/api/v1/family/bindings/{id}/approve` | POST | `account:family:bind` | 机构 | — | D-4 |
| F5 | ——（反向缺口 R2） | `/api/v1/family/bindings/{id}/unbind` | POST | `account:family:unbind` | 关系 | — | D-4 |
| F6 | `getReports(relationId)` | `/api/v1/family/bindings/{relationId}/reports` | GET | `report:view`（**复用**） | **关系** + 仅 `PUBLISHED` | 等级 / 得分 | D-4 |
| F7 | `getReport(relationId, reportId)` | `/api/v1/family/bindings/{relationId}/reports/{reportId}` | GET | `report:view`（复用） | 关系 + 仅 `PUBLISHED` | 等级 / 得分 / 分项 | D-4 |
| F8 | `getTrend(relationId)` | `/api/v1/family/bindings/{relationId}/trend` | GET | `report:view`（复用） | 关系 + 仅 `PUBLISHED` | 历次等级 / 总分 | D-4 |
| F9 | `getCarePlan(relationId)` | `/api/v1/family/bindings/{relationId}/care-plan` | GET | `report:view`（复用） | 关系 + 已确认 | 照护建议 | D-4 |
| ~~F10~~ | ~~`getMessages()`~~ | ~~`/api/v1/family/messages`~~ | — | — | — | — | **❌ 移出 D-4** |

**F10 移出的依据**：PRD §3 功能点 21/22（消息中心）已由 **CR-M2-001（ER-02 裁决）** 标注「本期不含·V2」，且 §1.2「本期范围（MVP）」显式声明不含。**为 V2 需求提前造契约属范围外**。端侧 `real-api.ts` 已按"显式返回空数组、不造数"处理，维持即可。

### 6.3 数据域口径的契约表达（扩展键提案）

D 的验收口径要求「新增接口全部带 `x-required-permission` 与**数据域口径**」。当前契约只有权限键，**无数据域与敏感字段的机器可读标注** → 无法门禁。建议新增两个扩展键：

```yaml
/api/v1/family/bindings/{relationId}/reports:
  get:
    tags: [family]
    summary: 家属查看已发布报告列表
    x-required-permission: report:view
    x-data-scope:                      # ← 新增：数据域口径（机器可读）
      mode: relation                   # self | org | relation | all
      via: elder_family_bind
      condition: "status=2 AND deleted=0 AND (auth_end_at IS NULL OR auth_end_at > NOW())"
      extra: "仅 status=PUBLISHED 的报告"
    x-sensitive-fields: [gradeCode, totalScore, dimensionScores]   # ← 新增：敏感字段口径
```

**配套契约门禁**（建议随 D-1 落地，作为"契约先行"的可验证落点）：

| 断言 | 口径 |
|---|---|
| 1 | 所有业务路径（除 `auth/*`、`system/health`、`reports/verify`）**必须**带 `x-required-permission` |
| 2 | 所有业务路径**必须**带 `x-data-scope`，且 `mode ∈ {self, org, relation, all}` |
| 3 | `x-required-permission` 的每个码**必须**存在于 `sys_permission.perm_code`（防止契约造出库里没有的权限码） |
| 4 | 命中 `sys_permission.need_second_verify=1` 的端点**必须**标注 `x-second-verify: true` |

> 断言 3 是**用户提示的第二个坑**（"权限码只认库里的 `sys_role_permission`"）在契约层的机械化拦截 —— 建议直接落地，成本极低。

---

## 七、待裁决项（5 项 · 附默认建议）

| # | 待裁决 | 选项 | **默认建议** | **裁决（2026-09-24）** |
|---|---|---|---|---|
| **1** | **契约路径基线** | A 存量向实现对齐（改契约）／B 实现向契约对齐（改后端 + 四端）／C 双轨保留 | **A** —— 四端 `real-api.ts` 已按实现路径调用且 **H5 验收通过**；选 B 需改后端 4 个控制器 + 四端数据层 + 重跑全部验收 | **✅ 采纳 A** —— 契约全量改写为 `/evaluation/**` 实现路径；`/eval/orders`、`/rule/versions`、`/reports/{reportId}` 等旧路径全部下线。**附带项亦定**：`orderId` = 写入/主链路轴、`reportNo` = 读取/分发轴，不互为别名（写进契约 description，杜绝"两套报告契约"） |
| **2** | **★ 数据域语义**（阻断面） | A 新增 `DataScope.FAMILY(5)`／B 服务层显式校验／C 重定义 scope=1 | **A** —— 零 DDL、与四闸一致、一处生效全表兜底 | **✅ 采纳 A** —— 已实现（见 §十一.3）；**附带发现一个数值序陷阱**：原第三闸 `if (scope >= DataScope.ALL.code()) return null;` 会把 5 判成"不限"→ 跨机构泄露，已改为逐枚举判定并加回归用例 |
| **3** | **家属端读接口权限码** | A 复用 `report:view` + 关系级数据域／B 新增 `family:report:read` 等专用码 | **A** —— 与 CR-M2-001 对 `elder:archive:read` 的裁决**同构**（码全授、范围由第三闸控制）；B 会让同一规则被两处表达 | **✅ 采纳 A** —— F6~F9 契约权限码均为 `report:view`，**零新增权限码**（`sys_permission` 仍 22 个） |
| **4** | **`getMessages()` 处置** | A 移出 D-4、标注"本期不含·V2"／B 补进 D-4 | **A** —— PRD §3 功能点 21/22 + §1.2 已裁决 | **✅ 采纳 A** —— 契约**不含** `/family/messages`；D-4 范围收敛为 F1~F9（9 个） |
| **5** | **老人「确认」环节口径** | A 沿用现有码（仅 `:reject`，放行靠机构审核）／B 新增 `account:family:bind:confirm` | **A** —— PRD §2.1 老人"可不注册"，主动确认在无账号时不可执行 | **✅ 采纳 A** —— 老人侧仅 `account:family:bind:reject`；放行由 `POST /family/bindings/{id}/approve`（机构审核）承载，"默认通过 + 可拒绝" |

---

## 八、对 D-2 ~ D-5 的范围修订建议

| 子卡 | 原范围（你的清单） | **盘点后的修订建议** |
|---|---|---|
| **D-2 档案域后端** | 家属端与报表都依赖它；敏感字段复用 `SensitiveFieldCodec` | **+ 数据域语义扩展**（待裁决 2）—— 这是 D-2 的**第一交付物**，否则 D-4 无解；`elder` / `elder_family_bind` / `elder_authorization` / `consent_record` 实体 + `B2`~`B5`（含 `data:reveal` 端点）；**零 DDL**（表已就绪） |
| **D-3 报表域后端** | 复用 CR-M2-002 数据域基建 | **+ R6 组织管理**（`account:org:manage` 反向缺口）；`B6`~`B10`（`eval/tasks`、`rule/mapping`、`reports/verify`、`supervise/exports`、`supervise/report`）；`export_task` / `supervise_report` 实体；**`retain_until` 写入口须与发布/上报链路绑定**（否则归档任务空转） |
| **D-4 家属端端点 + 端侧切真** | 闭环 CR-M2-005；复用 H5 手册 | 接口收敛为 **F1~F9（9 个，不是 10 个）**——F10 移出；**前置 = D-1 待裁决 2 已定**；契约先行（本卡 §6.2 表） |
| **D-5 ER-14 适配器** | 沿用 ADR-0004 调度范式 | 无修订。**注意**：ER-14 `THIRD_PARTY_PURGE` 已在 2026-09-23 随 P1-4 落地并实测留痕 → 本卡实为**适配器形态归一**而非新功能，工作量小 |
| **D-1 本卡** | 契约补登 + 评审 | **+ C 类 9 端点补登**（此前无人登记）+ `x-data-scope` / `x-sensitive-fields` 扩展键 + 契约门禁断言（4 条） |
| **★ CR-M2-006（新，建议插队）** | 不在原清单 | **建议在 D-2 之前闭环**：报告 PDF 下载越权修复（`read()` 补归属校验 + `download()` 补第二闸，约 10 行）+ 3 个裸奔端点补注解 + 「跨机构 reportNo 下载必须被拒」回归断言 |

---

## 九、本次盘点暴露的文档问题（顺带修）

| # | 问题 | 处置 |
|---|---|---|
| 1 | `B2-permission-api-matrix.md` 的「实现」列**已过期**：标 ⏳ 的 `/eval/orders` 等实际已实现（只是路径不同） | D-1 落地时同批回填 |
| 2 | `B2-permission-api-matrix.md` §4 反向缺口标为「保留项」，但 R1/R7 实际已闭合 | 同批更新状态 |
| 3 | `CR-M2-005` §四.3 要求端侧"保留 relationId 校验" —— 与 §6.2 的路径设计一致，无需改 | 无 |
| 4 | 契约 `info.version: 1.0.0` 未随内容演进 | 补登后升 `1.1.0` 并加 changelog 段 |

---

## 十、评审通过后的落地顺序（D-1 执行清单）

1. 按 §四 逐条改写 `openapi/yl-api.yaml`：A 类对齐 9 项、C 类补登 9 项、新增 `x-data-scope` / `x-sensitive-fields` 扩展键；
2. **C 类端点补登前先核验第二/三闸**（§5.3 的教训）—— 发现裸奔端点先立卡修复，**不带病补登**；
3. `info.version` → `1.1.0`，新增 `changelog` 段记录本次补登；
4. 落地 §6.3 的 4 条契约门禁断言（建议新增 `scripts/verify-openapi.sh`）；
5. 回填 `B2-permission-api-matrix.md`（实现列 + 反向缺口状态）与 `CR-M2-001` §2.4(b) 状态；
6. **（建议）先闭环 CR-M2-006 → 冻结契约 → 开工 D-2**。

**执行结果（2026-09-24）：6/6 全部完成**，且第 2 步确实拦下了一个 P0（CR-M2-006，见 §5.3）——**"补登前先核验鉴权链"这条纪律当场兑现了一次**。

---

## 十一、实施记录（2026-09-24）

### 11.1 契约（`openapi/yl-api.yaml` → v1.1.0）

| 项 | 结果 |
|---|---|
| 契约操作 | **43 个**（v1.0.0 为 22）：`implemented 22` + `planned 21` |
| A 类对齐 | 9 项全部改为实现路径（`/api/v1/evaluation/**`）；旧路径 `/eval/orders`、`/rule/versions`、`/reports/{reportId}` 下线 |
| C 类补登 | 9 项全部登记（含复核三端点、照护方案三端点、报告 PDF 两端点、题库、规则元信息） |
| B/D 类目标态 | `planned` 21 项：B1~B10（10）+ R6 组织管理（2）+ 家属端 F1~F9（9）；均标注归属子卡 |
| 新增扩展键 | `x-required-permission`（OR 用 ` | `）、`x-data-scope`（`mode` ∈ `public/self/org/relation/all`，可选 `by-role`/`via`/`condition`/`extra`）、`x-second-verify`、`x-sensitive-fields`、`x-status` |
| 轴口径 | `orderId` = 写入/主链路轴，`reportNo` = 读取/分发轴（写入 info.description，避免两套报告契约） |

### 11.2 门禁（两层，同源口径互为冗余）

| 载体 | 断言 | 结果 |
|---|---|---|
| `scripts/verify-openapi.sh`（新增，无 Maven 亦可跑） | 5 组 8 条：扩展键齐备 / `mode` 合法 / `public ⟹ none` / 敏感点 ⟺ `x-second-verify` / **契约 implemented 双向漂移 = 0** | ✅ **8/8**（控制器 22 = 契约 implemented 22） |
| `OpenApiContractTest`（新增，随 `mvn verify`） | 6 条：SnakeYAML **真解析** + 漂移 + 权限码必须存在于 `PermissionCode` 常量 + 敏感点标注 | ✅ |
| `scripts/verify-schema.sh`（+3 条，共 **81**） | 家属 `data_scope=5` 精确值 / 契约权限码条数下界 / **契约码必须存在于 `sys_permission`**（禁止私造码） | ✅ |

> 漂移断言是**本卡的核心交付**：它把「契约 = 实现镜像」这一此前**事实上已失效**的属性重新变成机器可校验的断言 ——
> 今后任何"实现了没登记"或"登记了没实现"都会让 `mvn verify` 与 `verify-openapi.sh` 同时变红。

### 11.3 数据域关系级（裁决 2 落地 = D-2 首交付物）

| # | 文件 | 改动 |
|---|---|---|
| 1 | `DataScope` | 新增 `FAMILY(5)`；类注释写明**数值序 ≠ 宽严序**（5 比 2 更窄），禁止数值比较 |
| 2 | `DataScopePermissionHandler` | ① **修正数值序陷阱**：`scope >= ALL` → 显式枚举 `ALL` / `READONLY_GLOBAL`，未知值落到最严格的"本人"分支；② 新增 `relationSegment`：直锚表（`elder.id` / `elder_id`）→ `锚列 IN (我有效绑定的老人)`；反查锚表（`report_dimension_score`/`eval_answer`/`eval_evidence`/`eval_review_log`/`care_task_log`）→ 先经父表收口；`elder_family_bind` 自身按 `family_user_id`（**不可**用老人锚列，否则会读到同一老人其他家属的绑定行） |
| 3 | `03_seed_rbac.sql` | `FAMILY` `data_scope` 1 → 5；补**存量库幂等 UPDATE**（`INSERT IGNORE` 不更新既有行） |
| 4 | `02_schema.sql` | `data_scope` 列注释补"5-关系级（窄于 2）" |
| 5 | `DataScopePermissionHandlerTest` | +8 例：含 **`familyScopeIsNeverTreatedAsUnlimited`**（数值序陷阱回归）、绑定表自身按 `family_user_id`、反查锚经父表收口、无锚表失败即拒、未知 scope 落到最严格分支 |

> **零 DDL**：仅枚举 + 种子值 + 处理器分支；表结构（`elder_family_bind` 等）v1.0.0 起已就绪。

### 11.4 CR-M2-006（插队闭环）

见 **CR-M2-006 §八 实施记录**：`read()` 先查库再读文件 + `download()` 补第二闸 + 3 个裸奔端点补注解 +
三层防回归（单测 / 控制器鉴权覆盖测试 / 冒烟 5 断言）。**跨机构 `reportNo` 下载实测落 `R.code=10004` 且未泄露 PDF 字节**（口径见 §11.6）。

### 11.5 对 D 系列范围的最终修订（承 §八）

| 子卡 | 范围（已按本卡修订） | 前置是否解除 |
|---|---|---|
| **D-2 档案域后端** | 数据域关系级（**已完成**）+ `elder`/`elder_family_bind`/`elder_authorization`/`consent_record` 实体 + B2~B5（含 `data:reveal` 端点）；零 DDL | ✅ 阻断面已解除 |
| **D-3 报表域后端** | + R6 组织管理；B6~B10；`export_task`/`supervise_report` 实体；`retain_until` 写入口须与发布/上报链路绑定 | ✅ |
| **D-4 家属端端点 + 端侧切真** | 接口收敛为 **F1~F9**（F10 移出）；契约已先行冻结（§6.2 表已进 v1.1.0），端侧按契约实现 | ✅ |
| **D-5 ER-14 适配器** | 无修订（形态归一小改） | ✅ |

### 11.6 验收期勘误与新增发现（2026-09-24，契约 → v1.1.1）

CR-M2-006 复验时冒烟报「34 通过 / 2 失败」。按 §十 的"先怀疑环境"习惯先排查了进程占用（旧 PID 26956 占 jar，
Windows 文件锁导致新包不生效），`taskkill` + 重建后**仍失败**，于是直读运行实例日志 + 直测端点，得到两个结论：

**（一）契约写错了错误语义 —— 已勘误（不改实现）**

| 项 | 误写 | 实际（实证） |
|---|---|---|
| 报告 PDF 端点业务拒绝 | `'404'` | **HTTP 200 + `R.code=10004`** |

根因：`GlobalExceptionHandler#handleBiz` **无 `@ResponseStatus`** → 业务异常一律 200，业务码在响应体；
全项目只有校验失败（400）与未预期异常（500）改状态码。日志实证
`uri=.../reports/RP-CR-M2-006-ORPHAN/pdf code=10004` 而 HTTP 为 200。

**双重后果**（比单纯写错更值得记）：
- **假红** —— 断言按 HTTP 404 写，拒绝也读成 200；
- **假绿** —— 「还原后可下载（HTTP 200）」成功/失败同为 200，**断言恒真、零区分力**，比假红更危险。

处置：① 契约 `responses` 改为 `200 / 401 / 403` 并写明"判 `code`、二进制端点另验魔术字节"；
② `info.description`「统一响应」一节**写死**该规则（业务失败=200）；③ 冒烟断言下沉到
`R.code` + 字节层（拒绝须 `code=10004` **且**响应体非 `%PDF`；放行须以 `%PDF` 开头）→ 复跑 **34 通过 / 0 失败 / 1 跳过**。

> **口径教训（已写进契约与脚本注释）**：本项目的"拒绝"证据是 `R.code != 0`，**不是** HTTP 状态码；
> 二进制端点必须再验魔数，否则"没返回文件"这件事没有任何断言在守。

**（二）新发现：未认证状态码分叉 —— 已另立 CR-M2-007（P2，未修）**

不携带 `Authorization` → **403**（Spring 默认 `Http403ForbiddenEntryPoint`，因 `SecurityConfig` 未配
`exceptionHandling` 入口点）；凭据无效/过期 → **401**（`JwtAuthFilter` 显式置位）。而 `SecurityConfig` 类注释
承诺「未认证统一返回 401 + 统一响应体」。**不属本批次范围**（动第一闸＝影响全站），按"契约过一道轻评审再实现"
的既定口径登记待裁决。契约 v1.1.1 已**如实**声明两种状态码，故契约与现实不再漂移。

---

## 会签栏

| 角色 | 意见 | 日期 |
|---|---|---|
| 产品负责人（裁决） | **✅ 同意**：5 项全部按默认建议（A/A/A/A/A）；CR-M2-006 插队先行 | 2026-09-24 |
| 研发（后端） | **✅ 已实施**：契约 v1.1.0 冻结、门禁 2 处落地、数据域关系级与 CR-M2-006 修复完成 | 2026-09-24 |
| 四端负责人 | **✅ 同意 A**：四端 `real-api.ts` 已按实现路径调用，契约对齐后**端侧零改动** | 2026-09-24 |
| 合规（DPO） | **✅ 附条件同意**：数据域方案 A 的越权兜底经实测充分（跨机构 404）；条件 —— D-4 上线前须补关系级越权用例（家属拼他人 `relationId`/`reportId` 必须被拒） | 2026-09-24 |
