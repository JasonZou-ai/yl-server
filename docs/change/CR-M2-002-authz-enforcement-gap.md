# 变更单 CR-M2-002 · 鉴权补位缺口（复核/发布靠请求体传 operatorId，RBAC 未校验）

| 项 | 内容 |
|---|---|
| 变更单编号 | **CR-M2-002** |
| 提出日期 | 2026-09-23 |
| 提出人 | 产品负责人（P0 收口清单 · 必登项） |
| 变更对象 | `AssessmentController` 复核/发布/驳回/作废/照护确认端点的鉴权口径；`JwtAuthFilter` 登录态装配；`openapi/yl-api.yaml` 对应操作 |
| 变更依据 | 2026-09-23 模拟器端到端联调实测（`_交付物/e2e_08_复核发布.png`、`e2e_09_审计链.png`）+ 代码核对 |
| 关联交付 | `yl-api/.../security/JwtAuthFilter.java`、`LoginUser.java`、`JwtTokenProvider.java`、`AssessmentController.java`、`openapi/yl-api.yaml`、`scripts/verify-schema.sh` |
| 变更类别 | **一般变更**（鉴权实现补位，**不新增业务功能、不改判定口径**；但**移除请求体 `operatorId`** 属契约破坏性变更，需同步四端） |
| 紧急度 | **高（安全项）**：权限可绕过，属"上线即事故"级别；但**不阻塞** P1/P2 开发排期 |
| 审批权限 | 按《需求基线冻结说明》§4.2 —— CCB 多数同意 + 产品经理签发（一般变更） |
| 状态 | **✅ 已实施并通过验收（2026-09-23）** —— 见 §八 实施记录；卡内 6 项验收全部实测通过 |
| 实施日期 | 2026-09-23 |
| 实施范围 | `yl-api`（安全层 + 健康探针）、`yl-module-account`（签发/装配/数据域）、`yl-module-evaluation`（端点鉴权 + 操作者改源）、`yl-bootstrap`（调度壳）、`docs/ADR`、`scripts/*` |

---

## 一、登记缘由（PM 原文）

> 「鉴权补位立卡（安全项，必须登记）——复核/发布目前靠调用方传 `operatorId`，RBAC 权限码 `evaluation:order:review` 未校验，登录链路就绪后必须补，否则权限可绕过。先立任务卡，登录一好就补，别让它淹没。」

**前置条件已满足**：B1-2 登录链路 + B2 权限种子（22 权限点 / 40 授权）均已落地，dev 联调账号已播种（`docker/mysql/init/04_seed_dev_accounts.sql`），本卡即可进入实施。

---

## 二、缺口清单（代码级定位）

### 2.1 缺口 A —— 方法级鉴权完全缺失

`AssessmentController` **无任何** `@PreAuthorize` / `PermissionAspect` / 权限码校验（已核对全文件）。

复核域端点的操作者来源是**请求体**，不是登录态：

| 端点 | 操作者来源 | 应有权限码（CR-M2-001 §2.3） |
|---|---|---|
| `POST /assessments/{id}/review/publish` | 请求体 `operatorId` | `evaluation:order:review` |
| `POST /assessments/{id}/review/reject` | 请求体 `operatorId` + `opinion` | `evaluation:order:review` |
| `POST /assessments/{id}/review/void` | 请求体 `operatorId` + `opinion` | `evaluation:order:void`（**敏感，第四闸**） |
| `POST /assessments/{id}/care-plan/confirm` | 请求体 `operatorId` | `care:plan:edit` |
| `GET /reports/{reportNo}/pdf` | 仅需登录 | `evaluation:report:read` |

**后果**：任何持有**合法 JWT**的账号（包括老人、家属等**无护理职责**角色）只要知道 `orderId`，即可发布/驳回/作废**他人**的评估单 —— 权限矩阵（`docs/design/B2-permission-point-catalog.md`）与实际判定**完全脱钩**。

> **实测证据（2026-09-23 端到端联调）**：设备端以 `assessor_dev`（ASSESSOR）令牌调用 `review/publish`，请求体传 `operatorId=900000000000000002`（`orgadmin_dev` / ORG_ADMIN）→ 请求**成功**且 `eval_order.reviewer_id` 记为该机构管理员、`eval_report.status` 转 1。即"复核人"由**请求体**决定，而非登录身份。

### 2.2 缺口 B —— 权限码即使加上也会"假性全拒"（D2，直接阻塞缺口 A 的修复）

`JwtAuthFilter`（第 85–94 行）解析令牌后构造登录主体：

```java
LoginUser loginUser = new LoginUser(userId, orgId, roles);   // ← 3 参兼容构造器
```

而 `LoginUser` 的三参构造器（`LoginUser.java`）为：

```java
public LoginUser(long userId, Long orgId, List<String> roles) {
    this(userId, orgId, roles, List.of(), List.of(), 1);   // perms 空 / orgIds 空 / dataScope=1
}
```

其 Javadoc 已自认：「**兼容 B1-2 现有调用（JwtAuthFilter）：仅注入角色，权限/机构域留待 `rwkav6` 鉴权层补全**」。本卡即 `rwkav6` 的收口动作。

同时 `JwtTokenProvider` 签发的 claim **只有 4 个**：`uid` / `roles` / `orgId` / `typ`
（`CLAIM_USER_ID` / `CLAIM_ROLES` / `CLAIM_ORG_ID` / `CLAIM_TYPE`），**根本没有 `perms` / `orgIds` / `dataScope`**。

**三点后果（必须一起看，否则会做出错误判断）**：

| # | 后果 | 表现 |
|---|---|---|
| 1 | 权限码集合**恒为空** | 一旦加 `@PreAuthorize("hasAuthority('evaluation:order:review')")` → **所有账号全量 403**。这是"假性收紧"，不是真收紧 |
| 2 | `dataScope` **恒为 1（仅本人）** | 第三闸 `DataScopePermissionHandler` 对每个账号都追加 `id = 当前用户`。评估员/机构管理员**看不到本机构的评估单**（功能不可用） |
| 3 | `orgIds` **恒为空** | 机构维度数据域无法判定，"本机构"语义失效 |

> **注意**：后果 1 与后果 2 互为掩护 —— 若不先补 B，只加注解会得到"看起来更安全"的 403，但**数据域仍是错的**；若只补数据域不动方法级鉴权，则**越权照旧**。二者必须同一批次修复。

### 2.3 缺口 C —— 契约侧待同步

`ReviewActionRequest` 的 `operatorId`（`@NotNull`）与 `opinion` 在缺口 A 修复后**必须移除 `operatorId`**，
否则会出现"请求体说 A 操作、登录态说 B"的双源冲突。

---

## 三、处置方案（三步，建议同批次）

| 步 | 动作 | 落点 | 要点 |
|---|---|---|---|
| **1** | **令牌载荷补齐** | `JwtTokenProvider` + `AuthService.login` | 新增 `perms` / `orgIds` / `dataScope`。**推荐方案：签 `permVer`（权限版本号）+ 服务端查表缓存**，而非把几十个权限码塞进 JWT —— 令牌体积可控、权限变更即时生效、不把授权面暴露在客户端可解码的令牌里 |
| **2** | **过滤器装配** | `JwtAuthFilter` 第 94 行 | 改用 6 参构造器（`userId, orgId, roles, perms, orgIds, dataScope`）；`LoginUser` 3 参兼容构造器标 `@Deprecated(forRemoval = true)` |
| **3** | **方法级鉴权 + 操作者改源** | `AssessmentController`（及照护/报告域） | 加权限码校验；**操作者一律取登录态**，请求体 `operatorId` 移除；`evaluation:order:void` 走第四闸（二次验证 + `audit_log.sensitive` 留痕） |

### 实施顺序与前后的强制约束

1. 先做 **1+2（不改任何端点行为）**，跑一遍全量回归 —— 此时**行为应与改造前完全一致**（因为还没有注解生效）。这是关键对照点。
2. 再做 **3**，但**按域分批启用**（先评估域复核，再照护域，再报告域），每批跑 `scripts/smoke-report-review-chain.sh`。
3. **禁止**"只加注解、不补载荷"的提交：会得到全量 403 的假性收紧。

---

## 四、验收标准（可自动化）

| # | 验收项 | 判定方式 |
|---|---|---|
| 1 | 无权限账号调用 `review/publish` | **403**，且 `audit_log` 有拒绝留痕 |
| 2 | 有权限账号调用 `review/publish` | **200**，且 `eval_order.reviewer_id` **= 登录用户 ID**（**不等于**请求体曾传入值 —— 用"请求体传他人 ID 仍记登录用户"反证缺口已闭合） |
| 3 | 请求体含 `operatorId` | 被**忽略**或按契约校验失败（取决于是否保留过渡期兼容） |
| 4 | `dataScope` 装配正确 | 评估员 `GET /assessments` 可见本机构评估单（**非空**）；老人仅见本人 |
| 5 | 单测 | `JwtAuthFilterTest` 增 3 例（perms 装配 / dataScope 装配 / **非会话入口仍建登录态**的既有断言保持）；RBAC 越权 4 例 |
| 6 | 契约 | `openapi/yl-api.yaml` 同步移除 `operatorId`，`x-required-permission` 补齐；`docs/design/B2-permission-api-matrix.md` 升版 |

---

## 五、影响面评估

| 维度 | 评估 |
|---|---|
| **需求影响** | 不新增功能；把 CR-M2-001 已批准的权限矩阵**从纸面变为强制**。 |
| **契约影响** | ⚠️ **破坏性**：`ReviewActionRequest.operatorId` 移除 → **四端需同步改动**（安卓已于 2026-09-23 完成复核链，接口调用点集中在 `AssessmentChainApi.publish/reject`，改造成本低）。 |
| **数据模型影响** | 无表结构变更（`reviewer_id` 本已存在，只是取值来源从请求体改登录态）。 |
| **合规影响** | **正向且关键**：补齐 PIPL/等保「访问控制 + 最小必要」落点；作废等敏感操作纳入第四闸留痕。 |
| **回归范围** | 评估域全链路（录入→分级→报告→复核）+ 照护方案 + 报告 PDF。既有 `scripts/smoke-report-review-chain.sh` **24 项**可直接作回归基线（2026-09-23 实测 24/24 全绿）。 |
| **不做的风险** | 权限可绕过；监管/等保审查直接失败；越权操作无留痕。 |

---

## 六、依赖与关联

- **前置（已满足）**：B1-2 登录链路（`/api/v1/auth/login/{channel}`）、dev 联调账号、B2 权限种子（CR-M2-001 已落地，22 权限点 / 40 授权）。
- **关联缺陷（已修复，本卡背景）**：**D1 —— 会话入口被数据域污染**。`JwtAuthFilter` 原先对 `/auth/login` 也沿用既有登录态，导致 `DataScopePermissionHandler` 给 `sys_user` 查询追加 `id = 旧用户` → "已登录状态无法登录其他账号（返回账号不存在）"。2026-09-23 已修复（`isSessionEntry` 短路 + `JwtAuthFilterTest` 3 例回归）。**D1 与 D2 同源**（都源于"登录态/数据域口径未收口"），本卡一并收口。
- **后续（不属本卡）**：CR-M2-001 反向缺口 6 类接口补登（随模块实现）。

---

## 七、闭环记录

| 日期 | 事项 | 结果 | 确认人 |
|---|---|---|---|
| 2026-09-23 | 端到端联调暴露"复核人由请求体决定" | ✅ 实测确认（设备端 + DB 双证） | 研发侧 |
| 2026-09-23 | 代码级定位缺口 A/B/C | ✅ 完成（`AssessmentController` 无注解；`JwtAuthFilter:94` 三参构造器；JWT 缺 3 个 claim） | 研发侧 |
| 2026-09-23 | **立卡登记** | ✅ 本文件 | 产品负责人 |
| 2026-09-23 | 实施（1+2 → 对照回归 → 3 分批启用） | ✅ 完成，见 §八 | 研发侧 |
| 2026-09-23 | 单测 + 冒烟双绿验收 | ✅ 单测 89/89、冒烟 `smoke-report-review-chain.sh` 32/32 | 研发侧 |

---

## 八、实施记录（2026-09-23）

> 按 §3「先 1+2（不改端点行为）→ 跑对照回归 → 再 3 分批启用」的强制顺序执行，**未出现"全量 403 假性收紧"**。

### 8.1 对原方案的两处偏离（已说明理由，非默认放行）

| 项 | 原方案（§3） | 实际实施 | 理由 |
|---|---|---|---|
| 载荷策略 | 推荐签 `permVer`（权限版本号）+ 服务端查表缓存 | **直接把 `perms`/`orgIds`/`dataScope` 签进 JWT** | 当前权限点 22 个、单账号授权 ≤ 40 条，令牌实测 < 1.5 KB，体积风险不成立；引入 `permVer` 需新增缓存层 + 失效广播，在"任务数 < 3、不引入分布式组件"（与 ADR-0004 同口径）的当下属过度设计。**遗留项**：权限变更后旧令牌在 TTL 内仍是旧权限（已登记为观察项，不阻塞本卡）。 |
| 数据域表登记 | 未展开 | `care_plan` **从机构过滤表清单中移除** | 核查发现 `care_plan` **无 `org_id` 列**，原登记会导致 `dataScope=2`（本机构）装配后查询直接 500。 |

### 8.2 落地清单

| # | 动作 | 文件 | 要点 |
|---|---|---|---|
| 1 | 权限码常量上移共享层 | `yl-api/.../security/PermissionCode.java`（新） | evaluation 域要引用权限码，不上移会退化成魔法字符串 |
| 2 | 登录态取用工具 | `yl-api/.../security/SecurityUtils.java`（新） | `currentUserOrNull()` / `requireUser()` / `requireUserId()`（未登录抛 `UNAUTHORIZED`） |
| 3 | 令牌载荷补齐 | `yl-api/.../security/JwtTokenProvider.java` | 新增 `CLAIM_PERMS` / `CLAIM_ORG_IDS` / `CLAIM_DATA_SCOPE`；access/refresh 双签发点同步 |
| 4 | 过滤器改完整装配 | `yl-api/.../security/JwtAuthFilter.java` | 6 参构造；**保留 D1 的会话入口短路**（`/auth/login`、`/auth/refresh`） |
| 5 | 兼容构造器废弃 | `yl-api/.../security/LoginUser.java` | 3 参构造器标 `@Deprecated`（保留以免同批次破坏既有调用） |
| 6 | 签发侧装配 | `yl-module-account/.../service/AuthService.java` | 登录 / 刷新两处均查权限、机构域、数据域并传入 |
| 7 | 数据域处理器修正 | `yl-module-account/.../security/DataScopePermissionHandler.java` | 修正 `care_plan` 错误映射；`scope=1` 由"未登记即放行"改为**对评估域表失败即拒** |
| 8 | 端点鉴权 + 操作者改源 | `yl-module-evaluation/.../controller/AssessmentController.java` | 每端点 `@RequiresPermission`；操作者一律 `SecurityUtils.requireUserId()` |
| 9 | 服务层身份显式化 | `yl-module-evaluation/.../service/AssessmentReviewService.java` | `publish/reject/voidOrder` 签名改为显式 `long operatorId`（**用编译器强制身份来源**） |
| 10 | 契约收敛 | `yl-module-evaluation/.../dto/ReviewActionRequest.java` | **删除 `operatorId`**（`@NotNull` 一并去除） |

### 8.3 作废端点为何用 OR 语义（关键裁决，勿回退）

权限矩阵中 `evaluation:order:void` **刻意不授予任何角色**（设计 §2.1：由机构管理员运行时经二次验证临时提权，避免权限常驻）。若照直写 `@RequiresPermission(EVAL_ORDER_VOID)`，作废端点会变成"**谁都进不去**"的死路。

```java
@RequiresPermission(
        value = {PermissionCode.EVAL_ORDER_REVIEW, PermissionCode.EVAL_ORDER_VOID},
        logical = Logical.OR)
public R<AssessmentResultResponse> voidOrder(...)
```

**正确性**：所需码表**含**敏感码 `order:void` → `PermissionAspect` 仍联动第四闸强制二次验证；同时 `order:review` 让 ORG_ADMIN 的常驻权限可过第二闸。与设计裁决"机构管理员经二次验证临时提权"效力一致，且**不自造策略代码**。

### 8.4 验收实测（§四 6 项逐条对账）

| 验收项 | 结果 | 证据 |
|---|---|---|
| 1 无权限账号调 `review/publish` 被拒 | ✅ | `smoke-report-review-chain.sh` 断言绿 |
| 2 有权限账号成功且 `reviewer_id` **= 登录用户** | ✅ | 实测 `reviewer_id = 900000000000000002`（机构管理员登录态），与请求体无关 |
| **越权反证**（本卡核心） | ✅ | **评估员令牌 + 请求体传机构管理员 ID → 发布被拒**（修复前实测为成功） |
| 3 请求体 `operatorId` 被忽略 | ✅ | DTO 已删字段；Jackson 默认忽略未知字段 → **线缆兼容，四端无需同批次发版** |
| 4 `dataScope` 装配正确 | ✅ | 登录态含 `orgIds`；评估员可见本机构评估单；`orgIds` 空会导致"机构数据域恒假"（已在 `04_seed_dev_accounts.sql` 补 `sys_org_member`） |
| 5 单测 | ✅ | **89/89**（yl-api 6 / account 32 / evaluation 51），含 RBAC 越权用例 |
| 6 契约同步 | ⚠️ 部分 | DTO 已改；`openapi/yl-api.yaml` 与 `B2-permission-api-matrix.md` 的 `x-required-permission` 补齐**随 ③ P1-5 端点核对一并做** |
| 第四闸（作废） | ✅ | 缺二次验证凭据被拒 → 换凭据成功 → **凭据一次性（重放被拒）** |

### 8.5 连带发现并修复：CR-M2-003（保留字列映射）

本卡验证过程中，保留期链路跑通后暴露出**独立缺陷**：审计留痕 `INSERT INTO audit_log (... sensitive ...)` 因 `SENSITIVE` 是 MySQL 8 保留字而**整条失败**，且 `audit_log` 长期为 0 行（**留痕从未成功落库**）。已单独立卡修复：`docs/change/CR-M2-003-reserved-word-column-mapping.md`。**与本卡关系**：本卡的作废/导出/解绑/`reveal` 留痕均依赖 `audit_log` 写入，故 CR-M2-003 的修复是**本卡合规效果的生效前提**。

---

### 8.6 后续修正（2026-09-24，D-1 契约冻结批次）

本卡 §8.2 第 7 行的"`scope=1` 对评估域表**失败即拒**"是**当时条件下的保守正确**：家属/老人属 `data_scope=1`，而
`elder` / `eval_report` / `elder_family_bind` 等表无"本人"列可表达，两害相权取"查不到"以避免越权读到他人数据。
代价写在当时的数据域处理器 Javadoc 里：**老人/家属"什么都看不到"**（家属端接口全部实现后仍查不到任何数据）。

D-1 裁决 2 已解除该阻断：新增 **`DataScope.FAMILY(5)` 关系级**，家属改按 `elder_family_bind` 生效绑定收口（零 DDL）。
同批修掉本卡遗留的一个**数值序陷阱**：原 `if (scope >= DataScope.ALL.code()) return null;` 在新增 5 档后会把
"关系级"当成"不加限"（跨机构泄露）—— 已改为按枚举逐值判定，未知值一律落到最严格的"本人"分支。

> 口径与实施见 `docs/change/D1-openapi-contract-delta.md` §11.3；回归用例
> `DataScopePermissionHandlerTest#familyScopeIsNeverTreatedAsUnlimited`。
> **本卡验收 6 项的"契约同步"（§8.4 第 6 行原为 ⚠️ 部分）随之闭环**：契约已升 v1.1.0 并加漂移门禁。

### 8.7 二次验证凭据的消费时点口径（R6 · 2026-09-26 裁决 · 显式写死）

**事实**：第四闸的一次性凭据（`SecondVerifyGuard` + `SecondVerifyStore`）在**业务校验之前**即被消费（先 `tryConsume` 扣减，再走业务判定）。因此当业务因**其它**原因失败（状态非法、数据域拒绝、参数不合法）时，凭据**已被消费且不可重放**。
**来源**：D-2 档案域后端 QA 独立验证观察项 R6（`docs/reviews/D2-qa-report.md`）。

**裁决**（产品负责人 2026-09-26）：**维持现状** —— 一次性凭据「**防重放优先于用户体验**」。

**显式口径（此后不再讨论，改则须走 CR）**：

1. 二次验证凭据是**安全凭据**，不是会话票据。**任何**消费尝试——无论业务成功或失败——都**不得**复用。
2. 业务失败后客户端须**重新申请**二次验证凭据；**禁止**同凭据重放。
3. 故二次验证端点的**限流**（`RateLimitInterceptor`）与签发额度是**唯一**防爆破屏障，**不得**以"改善体验"为由放宽。
4. 本行为**不是缺陷**，**不实现**退还 / 回滚 / 幂等复用逻辑。
5. 若未来确需"失败退还"，须单独立 CR 并做**安全评审**（退还即把「一次性」降解为「TTL 内可重试」，凭据可被反复用于试探业务边界 → 重放攻击面扩大）。

> 实现侧约束：`SecondVerifyGuard` 的消费顺序（先扣减后判定）**不得**调整；单测须含「业务失败后同凭据再次使用被拒」的钉死用例。

---

## 附：本卡与相邻卡片的关系

- **CR-M2-001**（权限矩阵缺口）：解决"**有没有**权限码"→ 已闭环（22 点已定义并入库）。
- **CR-M2-002**（本卡）：解决"权限码**是否真的被校验**"→ **已闭环**（2026-09-23 实施并验收）。
- **CR-M2-003**（保留字列映射）：本卡合规效果的**生效前提**（`audit_log` 写入曾长期失败）→ 已闭环。
- 三者是"定义规则 → 执行规则 → 执行留痕可用"的递进关系，**缺任一都不构成访问控制**。
