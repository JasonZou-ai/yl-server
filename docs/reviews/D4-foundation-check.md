# D-4 家属端 + 端侧切真 · 地基核查报告

| 项 | 内容 |
|---|---|
| 子卡 | **D-4a 后端（11 端点）** + **D-4b 端侧切真（uni-app 三端）** |
| 核查日期 | 2026-09-29 |
| 核查方式 | 逐文件实读 + SQL 片段级推演（**不依赖任何设计文档的转述**） |
| 唯一事实源 | `docker/mysql/init/02_schema.sql` / `03_seed_rbac.sql` / `04_seed_dev_accounts.sql`、`openapi/yl-api.yaml` v1.1.2、`DataScopePermissionHandler.java`、`PermissionAspect.java`、端侧 `utils/{request,real-api,family}.ts` |
| 前置裁决 | 核身通道（本文 §6.1，已拍板）；D-1 五项裁决（契约路径/权限码/数据域/消息中心/老人侧仅拒绝） |
| 关联 | CR-M2-005、R1（升格并入）、D3-R6（三角色真实 HTTP E2E）、CR-M2-002（鉴权四闸）、R5（consent 锚） |

---

## 0. 结论摘要（TL;DR）

1. **D-4 实现范围 = 11 项**：契约已冻结的 9 个家属端操作 + `authLogout`（契约注记「D-4 顺带」）+ **新增 1 个 `elder↔user` 绑定写入操作**（R1；契约**无承载点**，必须新增）。契约终态 **44 操作 = implemented 44 + planned 0**。
2. **★ 不需要任何 DDL**（与 D-3 相反）：发现的 2 处数据域缺陷 **全部用「反查锚」机制在处理器内闭合**，不加列、不改种子、`verify-schema.sh` 保持 **83**。理由见 §4.4（`elder_family_bind` 的机构归属是 `elder.org_id` 的**函数依赖**，补冗余列会引入机构迁移后的陈旧风险——D-3 的 `export_task` 是真属性，故那一次补列是对的，这一次补列是错的）。
3. **★ 揪出 3 处「权限已授 ≠ 数据可达」缺陷**（D-2/D-3 同类的**第 3、4、5 例**），其中 1 处会让刚冻结的老人端端点**功能死路**：

   | # | 表象 | 根因 | 落点 |
   |---|---|---|---|
   | ★F1 | `POST /family/bindings/{id}/reject` **老人拒绝死路** | `ELDER` 的 `data_scope=1`，而 `elder_family_bind` ∈ `SELF_DENY_TABLES` → 恒假 `(1=0)`；契约却写 `mode: relation` | 处理器新增**本人级反查锚** `SELF_VIA_PARENT` + 契约 `x-data-scope` 订正 |
   | ★F2 | `POST /family/bindings` **核验查询恒空** | 家属 scope=5 下查 `elder` → `elder.id IN (我绑定的老人)`，而此时**尚未绑定** → 目标老人永远查不到，姓名/身份证核验无法执行 | 服务层走**独立只读投影 Mapper** + 只返回布尔（防枚举），见 §4.3 |
   | ★F3 | `approve` / `GET /family/bindings`（ORG_ADMIN 视角）**跨机构可见** | `elder_family_bind` **无 `org_id`** → 未登记 `ORG_COLUMN` → 静默放行 | 处理器新增**机构级反查锚** `ORG_VIA_PARENT`（经 `elder.org_id` 收口） |

4. **★ 核身通道已拍板**：M2/dev = **机构线下核验 + 代操作**（零新基建）；短信通道**立独立卡 P2-8**（含真实外发通道 + 手机号采集 → 触发合规评审），并把**核身强度分级（线下 vs 短信）**写为该卡的生产前置。**否决**「复用二次验证语义」（语义不同构，见 §6.1）。
5. **★ 端侧口径修正一处**：「以 weapp 为基准改一份」**在实际工程不成立**——weapp 只有 7 个文件（无 `family.ts`/`real-api.ts`/`api.ts`），且其 `request.ts` 的 md5（`aeeb4069…`）与 uni-app 三端的（`eae7df44…`）**不同**。D-4b 的实际落点是 **weixin-app 改一份 → md5 同步 douyin-app / ios-app**（P1-5 已验证的路子）。
6. **★ dev 账号缺口**：`04_seed_dev_accounts.sql` 只播 `assessor_dev` / `orgadmin_dev`（ASSESSOR / ORG_ADMIN）。**没有 ELDER 账号、没有 FAMILY 账号** → D-4b 端侧主链与 D3-R6 三角色 E2E **今天跑不起来**，必须补 dev 种子（同「上线前移除」红线）。

---

## 1. 核查四问（用户指定）

| 问 | 结论 |
|---|---|
| **① 表与状态机对齐** | 表结构**全部就绪**（`elder` / `elder_family_bind` / `elder_authorization` / `consent_record` 均在 `02_schema.sql`）；`elder_family_bind` 五步状态与端侧 Mock 五态**逐值可对**（§2.2）；**但 `elder_family_bind` 无 `org_id`**（★F3），且 `family_user_id` 是**家属锚**、**不表达老人本人**（★F1）。 |
| **② 敏感链（unbind ④ 二次验证）** | `account:family:unbind` 在 `sys_permission` 中 `need_second_verify=1` ✅、**未授予任何角色** ✅（与 `evaluation:order:void` 同口径）→ 端点**必须 OR 语义**，且 `PermissionAspect` 会对注解内**任一**敏感码强制第四闸（§3.2）。「运行时临时提权」的**真实机制 = OR 语义 + 强制第四闸**，**不是**把权限码写进 JWT（§3.3）。 |
| **③ 「权限已授 ≠ 数据可达」** | 9 个操作**逐一推演到 SQL 片段级**（§4.2），命中 3 处缺陷（★F1/F2/F3），均在「读得到/读不到」层面而非语法层面。 |
| **④ consent 链** | 结论：**D-4 不写 `consent_record`**（理由见 §5），绑定留痕走 `audit_log` + 绑定行自身字段；`consent_record` 留给「隐私政策告知同意」（ER-08），残留不变。 |

---

## 2. 表与状态机对齐（逐列）

### 2.1 列核对（`elder_family_bind`，与 `02_schema.sql` 逐列）

| 列 | 类型 | D-4 用途 | 备注 |
|---|---|---|---|
| `id` | BIGINT NOT NULL | **relationId**（写入轴） | 雪花 id ⇒ **JS 精度截断风险**（§7.4） |
| `elder_id` | BIGINT NOT NULL | 直锚；审核时经 `elder` 反查机构 | — |
| `family_user_id` | BIGINT NOT NULL | scope=5 的**本表锚** | 契约明示：**不可**用老人锚列 |
| `relation` | VARCHAR(16) | CHILD/SPOUSE/KIN/GUARDIAN | 与契约 enum 一致 |
| `verify_method` | VARCHAR(16) | SCAN/PHONE/FACE | **NOT NULL**，无默认 → 创建必填 |
| `proof_cos_key` | VARCHAR(255) NULL | 监护证明**对象键** | ⚠️ **全仓无 COS 实现** → 只存键；落盘范式同 D-3（本地目录 + COS 同形键），见 §7.2 |
| `auth_start_at` / `auth_end_at` | DATETIME NULL | 授权起止 | `auth_end_at` 是第三闸子查询的生效判据 |
| `status` | TINYINT DEFAULT 0 | 五步状态机 | 见 §2.2 |
| `reject_reason` | VARCHAR(255) NULL | 拒绝原因 | 契约 `reject` 的 body |
| `deleted` | TINYINT DEFAULT 0 | 软删 | **实体不映射生成列**（`active_uk`）；`deleted` 本身正常映射 |
| `active_uk` | **生成列** | 唯一约束 `uk_elder_family(elder_id, family_user_id, active_uk)` | ★ 实体侧**不得映射** |
| `created_at` / `updated_at` | DATETIME | — | 无 `org_id`（★F3） |

> **`care_plan` / `export_task` 两次教训的复核结论**：本次 D-4 涉及的登记改动**不新增任何列名**（全部为映射表条目），撞保留字的可能性为 0；`org_id` / `elder_id` / `family_user_id` / `subject_id` 均为既有列，金丝雀仍须 = **1**。

### 2.2 五步状态机：库 ↔ 契约 ↔ 端侧 Mock 三方对齐

| 库 `status` | 库注释 | 契约 `FamilyBindingView.status` | 端侧 `BindStatus`（`family.ts`） | D-4 语义 |
|---|---|---|---|---|
| 0 | 待确认 | 0 | `PENDING_ELDER` | 家属已提交，等老人确认（老人可不注册 → 由机构审核兜底） |
| 1 | 待机构审核 | 1 | `PENDING_ORG` | 老人未拒绝 / 无法确认 |
| 2 | 生效 | 2 | `ACTIVE` | **唯一可见数据的状态**（第三闸子查询 `status=2`） |
| 3 | 已拒绝 | 3 | `REJECTED` | 老人拒绝或机构驳回，`reject_reason` |
| 4 | 已解绑/过期 | 4 | `EXPIRED` | 解绑，或 `auth_end_at` 到期 |

> **D-4b 承重项**：端侧切真后**必须用整数 ↔ 字符串的显式映射表**，不得靠字符串猜测；`EXPIRED` 在端侧由「本地按 `expiresOn` 判定」改为「读后端 `authEndAt`」，**本地判定逻辑删除**（避免端侧自造判定口径）。

---

## 3. 敏感链（第 ④ 闸）

### 3.1 敏感码的实际授予（`03_seed_rbac.sql` 逐行）

| 权限码 | id | `need_second_verify` | 授予角色 |
|---|---|---|---|
| `account:family:bind` | 2013 | 0 | **FAMILY(1003)、ORG_ADMIN(1004)** |
| `account:family:bind:reject` | 2014 | 0 | **ELDER(1001)** |
| `account:family:unbind` | 2016 | **1** | **无（刻意）** |
| `report:view` | 2001 | 0 | 五角色全授予 |

### 3.2 端点注解口径（**照先例，不要发明**）

- `approve` / `bindings` / `create`：`@RequiresPermission(PermissionCode.ACCOUNT_FAMILY_BIND)`（单码，FAMILY + ORG_ADMIN 均持有）。
- `reject`：`@RequiresPermission(PermissionCode.ACCOUNT_FAMILY_BIND_REJECT)`（单码，仅 ELDER）。
- **`unbind`**：`@RequiresPermission(value = {ACCOUNT_FAMILY_BIND, ACCOUNT_FAMILY_UNBIND}, logical = Logical.OR)`
  —— 与 `AssessmentController` L183-184 的 void 同款写法（**先例已存在，直接照抄**）。
  - **为什么必须 OR**：`account:family:unbind` **不授予任何角色** → 照直写单码会得到「**谁都进不去**」的死端点（D-1 已记录该陷阱）。
  - **`PermissionAspect` 的精确语义（实读 L48-58）**：先按 `logical` 判「是否有权进入」；随后 `Arrays.stream(needed).anyMatch(SensitivePermissions::isSensitive)` → **只要注解里含任一敏感码，无论实际是靠哪个码进去的，一律强制第四闸**。⇒ FAMILY 靠 `BIND` 进入后**仍会被要求 `X-Second-Verify-Token`**，这正是「解绑须二次验证」的落地机制。**这条语义必须写进单测**（否则后人把 `needed` 改成「仅已满足的码」就会静默失效）。

### 3.3 「运行时临时提权」的真实机制（**口径钉死**）

文档里「不授予角色 → 运行时经二次验证临时提权」的措辞**容易被误读为「二次验证会往权限集合里加码」**。实读 `PermissionAspect` + `SecondVerifyGuard`：

- 二次验证凭据**只**决定「第四闸是否通过」，**不会**修改 `LoginUser.perms`（perms 来自 JWT，签发后不变）；
- 因此 `unbind` / `void` 这类端点的可达性来自 **OR 语义里那个「有人持有的伴生码」**，而不是凭据本身。

⇒ **D-4 结论**：`unbind` 的实际可调用者是 **FAMILY 与 ORG_ADMIN**（经 `BIND` 进入）；ELDER **不可**解绑（除未来另行裁决）。契约 `extra: 仅可解绑本人参与的绑定关系` 的「本人」在实现上由**第三闸**（scope=5 → `family_user_id = 我`）+ 服务层双保险承载。

⚠️ **待裁决记录（默认已定）**：是否允许 ORG_ADMIN 代解绑？**默认允许**（与线下核验 + 代操作的整体口径一致，且机构侧本就需要处理"家属失联"场景），由第三闸的 `ORG_VIA_PARENT` 限制在**本机构老人**范围内。

---

## 4. 9 个操作 × SQL 片段级推演（「权限已授 ≠ 数据可达」检查法）

### 4.1 角色数据域（`03_seed_rbac.sql` 实读）

| 角色 | id | `data_scope` | 第三闸分支 |
|---|---|---|---|
| ELDER | 1001 | **1**（本人） | `SELF_COLUMN` → `SELF_DENY_TABLES` → 放行 |
| ASSESSOR | 1002 | 2（本机构） | `ORG_COLUMN` → 放行 |
| FAMILY | 1003 | **5**（关系级） | `relationSegment()` |
| ORG_ADMIN | 1004 | 2（本机构） | `ORG_COLUMN` → 放行 |
| SUPERVISOR | 1005 | 4（只读全局） | **不加限** |

### 4.2 逐操作推演

| # | 操作 | 调用角色 | 触及表 | 第三闸实际渲染 | 判定 |
|---|---|---|---|---|---|
| 1 | `GET /family/bindings` | FAMILY | `elder_family_bind` | `(elder_family_bind.family_user_id = me)` | **✅ 可达** |
| 1′ | 同上 | ORG_ADMIN(scope=2) | `elder_family_bind` | **`null`（放行）** | **❌ ★F3 跨机构可见** |
| 2 | `POST /family/bindings` | FAMILY | `elder`（核验） | `(elder.id IN (SELECT b.elder_id … status=2 …))` → **空集** | **❌ ★F2 核验恒空** |
| 3 | `POST …/{id}/approve` | ORG_ADMIN | `elder_family_bind` | **`null`（放行）** | **❌ ★F3 跨机构可审核** |
| 4 | `POST …/{id}/reject` | ELDER(scope=1) | `elder_family_bind` | **`(1 = 0)` 恒假** | **❌ ★F1 功能死路** |
| 5 | `POST …/{id}/unbind` | FAMILY / ORG_ADMIN | `elder_family_bind` | FAMILY: `family_user_id = me`；ORG_ADMIN: 放行 | **❌ ★F3（机构侧）+ ④ 闸** |
| 6 | `GET …/{relationId}/reports` | FAMILY | `eval_report` | `(eval_report.elder_id IN (SELECT b.elder_id …))` | **✅ 可达**（`PUBLISHED` 由服务层收口） |
| 7 | `GET …/{relationId}/reports/{reportId}` | FAMILY | `eval_report` + `report_dimension_score` | 直锚 `elder_id`；反查经父表 `eval_report` | **✅ 可达**（拼他人 `reportId` 在数据域被拒） |
| 8 | `GET …/{relationId}/trend` | FAMILY | `eval_report` | 同 6 | **✅ 可达** |
| 9 | `GET …/{relationId}/care-plan` | FAMILY | `care_plan` | `(care_plan.elder_id IN (…))` | **✅ 可达**（`status=1` 由服务层收口） |
| 10 | `POST /auth/logout` | 任意登录态 | —（Redis） | 不涉 SQL | **✅**（§7.1） |
| 11 | `POST /elders/{elderId}/user-binding`（新增） | ORG_ADMIN | `elder`（写） | `(elder.org_id IN (myOrgs))` | **✅ 可达** |

> **关键对照**：操作 6~9（家属读报告链路）**天然可达**——因为 D-1 已把 `eval_report` / `care_plan` / `report_dimension_score` 登记齐全。真正的问题全部集中在 `elder_family_bind` 这张表上（**F1/F2/F3 三处，同一张表**）。这就是「无归属列 ⇒ 静默放行/恒假」缺陷类在 D-4 的集中爆发点。

### 4.3 ★F2 的处置（核验查询不得经带数据域的 Mapper）

「验证这位老人是否就是我要绑的人」**在逻辑上必须先于绑定生效**，因此**天然无法**用关系级数据域表达。处置口径（三选一，取 **A**）：

- **A（采纳）**：`FamilyBindingVerifyMapper`（**模块自持只读投影 Mapper**，手写 `@Select`，逐列照 `02_schema.sql`）做**精确匹配计数**：
  - 入参 `elderId` + `elderName` + `idCardNo` → 服务端算 `name_hash` / `id_card_hash`（**需 `SensitiveFieldCodec` 已装配**，见 §7.3）→ `SELECT COUNT(*) FROM elder WHERE id = ? AND name_hash = ? AND id_card_hash = ? AND deleted = 0`；
  - **只返回布尔**，**绝不返回任何老人字段**（无 PII 泄漏面）；
  - 「不存在」与「不匹配」**返回同一结果**（防存在性枚举）；
  - 叠加 `RateLimitInterceptor` 现有匿名/登录限流 + **按 userId 的申请频次上限**（防批量枚举）。
  - ⚠️ **该 Mapper 是本轮唯一「绕过第三闸」的读**（红线：凡不经第三闸的直读必须手写归属/最小化校验）→ **必须列入 QA 的独立证伪项**，并在类 Javadoc 显式论证。
- B（否决）：把核验搬到"申请人自证"（不查库）→ 等于不核验。
- C（否决）：临时放宽 `elder` 的关系级 → 会让家属读到**未绑定**老人的档案，越权面直接上线。

### 4.4 ★F1 / ★F3 的处置（**零 DDL**，处理器新增两类反查锚）

在 `DataScopePermissionHandler` 内新增两张映射表（与既有 `RELATION_VIA_PARENT` **同构**，复用 `relationCondition` 的拼装方式）：

```
ORG_VIA_PARENT :  elder_family_bind → (elder_id, elder)   // scope=2：经 elder.org_id 收口
SELF_VIA_PARENT:  elder_family_bind → (elder_id, elder)   // scope=1：经 elder.user_id 收口
```

渲染结果（**SQL 片段级，机械可验收**）：

| scope | 表 | 渲染 |
|---|---|---|
| 2 | `elder_family_bind` | `(elder_family_bind.elder_id IN (SELECT p.id FROM elder p WHERE p.org_id IN (myOrgs)))` |
| 1 | `elder_family_bind` | `(elder_family_bind.elder_id IN (SELECT p.id FROM elder p WHERE p.user_id = me))` |
| 5 | `elder_family_bind` | `(elder_family_bind.family_user_id = me)`（**不变**） |
| 1 | `elder_authorization` / `consent_record` | `(1 = 0)`（**不变**，仍然失败即拒） |

**为什么不照抄 D-3 的「补锚列」方案（这是本报告最重要的取舍）**：

| 维度 | 补 `org_id` 列（D-3 对 `export_task` 的做法） | 反查锚（本报告采纳） |
|---|---|---|
| `org` 与行的关系 | `export_task` 的机构是**真属性**（任务归属机构，不可由他表导出） | `elder_family_bind` 的机构**是 `elder.org_id` 的函数依赖**（无独立语义） |
| 冗余列风险 | 无（本就要存） | **有**：老人转机构后绑定行的 `org_id` 会**陈旧**，形成两个真源 |
| DDL 成本 | +1 列 + 回填 + 索引 + schema 门禁 82→83 | **0**（schema 门禁保持 **83**） |
| 一致性 | — | 永远与 `elder.org_id` 一致（无同步逻辑） |

⇒ **结论：D-4 零 DDL**。同一判据（"该字段是不是本表的真属性"）在 D-3 得出"补列"、在 D-4 得出"反查"，**不是不一致，而是判据一致**。这一点须写进实施卡，防止后人"为对称而对称"地补列。

**契约订正（本体订正，同 `ElderCreate.required` 先例）**：
- `familyBindingReject` 的 `x-data-scope` 由 `mode: relation` 改为 **`mode: self, via: elder, condition: "elder_family_bind.elder_id IN (SELECT id FROM elder WHERE elder.user_id = 登录用户)"`**；
- `familyBindingApprove` 与 `familyBindingList` 的 `x-data-scope` 补 `condition: "elder_family_bind.elder_id IN (SELECT id FROM elder WHERE elder.org_id IN (登录机构))"`。

---

## 5. consent 链结论（问 ④）

| 选项 | 判定 |
|---|---|
| **D-4 写 `consent_record`** | **否决** |
| 理由 1 | `consent_record` 的语义是**「隐私政策告知同意」**（字段：`policy_code` / `policy_version` / `consent_channel` / `consent_at` / `revoked_at` / `ip`），举证对象是「**本人对自己数据处理的同意**」（ER-08，DPO 口径）。而「亲情绑定」是**民事监护/亲属关系的授权**，举证对象**不同**，塞进去会污染合规举证链。 |
| 理由 2 | `consent_record` 的锚是 `subject_type + subject_id`（R5），**没有 `elder_id`**（老人可能是 `subject_type='ELDER'`，但家属绑定的相对方无法表达），关系级下本来失败即拒 → 写了也读不出，属无效留痕。 |
| **真正需要的留痕** | 走 **`audit_log`**：`FAMILY_BIND_APPLY`（申请）/ `FAMILY_BIND_APPROVE`（审核通过）/ `FAMILY_BIND_REJECT`（拒绝）/ `FAMILY_BIND_UNBIND`（解绑，`sensitive=1`，与第四闸同源）。绑定行自身的 `status` / `verify_method` / `auth_start_at` / `reject_reason` 即是业务态留痕。 |
| 残留 | R5 保持不变（`consent_record` 锚非 `elder_id`）；**新增残留 D4-Rx：绑定关系未落 `consent_record`（有意）**，生产若要求"授权关系单独举证"再开卡。 |

---

## 6. 裁决记录

### 6.1 ★核身通道（用户拍板 · 最高优先级）

| 方案 | 判定 |
|---|---|
| **M2/dev：机构线下核验 + 代操作** | **✅ 采纳**。老人认领与家属绑定的「证明你是谁」由机构线下承载：绑定申请落库（`status=0/1`）→ `ORG_ADMIN` 经 `POST /family/bindings/{id}/approve` 放行（`account:family:bind` 已授予 ORG_ADMIN）。**零新基建、零外部依赖、E2E 当天可跑**。 |
| **短信核身通道** | **✅ 立独立卡 P2-8**（不造轮子、也不假装存在）：按 `15_短信验证码通道技术方案.md` 立卡，含**真实外发通道对接 + 手机号采集 + 合规评审**（个保法/短信签名报备）；**「核身强度分级（线下 vs 短信）」写为该卡的生产前置**。 |
| **复用二次验证语义（`X-Second-Verify-Token`）** | **❌ 否决**：它证明的是「**当前已登录会话的操作者是谁**」，证明不了「**这个 elder 就是这个人**」——**语义不同构**，复用会让核身形同虚设。 |

> **推论**：`elder↔user` 绑定在 M2/dev 的落地形态 = **机构代绑**（`POST /api/v1/elders/{elderId}/user-binding`，ORG_ADMIN 线下核验后执行）。**老人自主认领**（扫码/短信核身）**属 P2-8 范围**。

### 6.2 其余裁决（本报告默认建议，不另行请示）

| # | 议题 | 裁决 |
|---|---|---|
| 1 | `elder_family_bind` 机构归属 | **零 DDL**：处理器新增 `ORG_VIA_PARENT`（§4.4） |
| 2 | ELDER 拒绝端点可达性 | **零 DDL**：处理器新增 `SELF_VIA_PARENT` + 契约 `x-data-scope` 订正（§4.4） |
| 3 | 绑定核验查询 | 独立只读投影 Mapper + 只返回布尔 + 同码防枚举 + 列 QA 证伪项（§4.3） |
| 4 | `elder↔user` 绑定写入 | **新增 1 个契约操作** `POST /api/v1/elders/{elderId}/user-binding`；权限码**复用 `account:org:manage`**（零新码）；`x-data-scope: org`（`elder` 已在 `ORG_COLUMN`） |
| 5 | `proof_cos_key` | 只存键不落盘（无 COS 实现）；如需本地落盘，**复用 D-3 导出目录范式**（本地目录 + COS 同形键）→ 由设计定，但**必须与 P2-7 的文件清理口径一致**（D-4 内**不写任何删除逻辑**） |
| 6 | `authLogout` 黑名单 | 复用既有 `StringRedisTemplate`：access 令牌按 `sha256(token)` 记黑名单，TTL = 剩余 `accessTtl`（300s）；**同时** `RefreshTokenStore.revoke(refreshToken)`（键 `yl:rt:` 已存在）→ 双令牌同时失效。**不新增 `@Scheduled`** |
| 7 | 家属端模块归属 | **落 `yl-module-archive`**（D-2 已建 `elder` 实体 + `SensitiveFieldSupport` + 分页基建；`elder_family_bind` 是档案的关联关系）。跨域读（报告/趋势/照护）用**只读投影 Mapper**（D-3 判据） |
| 8 | `reject` 契约 `mode` | 由 `relation` 订正为 `self`（§4.4） |
| 9 | dev 联调账号 | **补 `elder_dev`（ELDER/1）+ `family_dev`（FAMILY/5）**，口令经环境变量 `DEV_LOGIN_PASSWORD` 注入（原为已泄露的固定口令，**2026-10-10 轮换**，见 `04_seed_dev_accounts.sql` 头部声明与 `P2-8-R12`）；ELDER 账号须经 `POST /elders/{elderId}/user-binding` 或种子回填绑定到某 `elder.user_id` |

---

## 7. 边界与残留（写进实施卡，防手滑）

### 7.1 不新增调度
`elder_family_bind.auth_end_at` 到期后的 `status` 4 流转**不引入新 `@Scheduled`**（全仓唯一调度仍应是 `RetentionChainScheduler`，ADR-0004）。**安全性不依赖该流转**：第三闸子查询已含 `(b.auth_end_at IS NULL OR b.auth_end_at > NOW())` ⇒ 到期即**数据不可达**（fail-closed）。status 字段的陈旧仅影响展示 → 登记残留 **D4-R1**。

### 7.2 不碰文件删除
D-4 内**不写任何文件删除逻辑**；`proof_cos_key` 与（若落盘）证明文件的生命周期清理**排给 P2-7**（与 `export_task.expire_at` 同卡）。

### 7.3 `SensitiveFieldCodec` 的条件装配（继承 D-2 红线）
dev 环境两密钥为空 → **Bean 不存在**。影响：§4.3 的 `name_hash` / `id_card_hash` **算不出来** → 核验降级。口径（照 D-2）：
- 消费方**必须处理「未装配」分支**：**禁明文落库**，也不许启动失败；
- 未装配时**绑定申请核验降级为「不校验姓名/身份证」并留下显式标记**（如 `verify_method` 保留原值 + 审计写明 `VERIFY_DEGRADED`），**或将申请直接拒绝并返回 `40001`**——**由设计二选一并给出可验收断言**（默认取后者：**宁可拒绝，不可假核验**）。
- **★C4 补充（2026-09-29，已裁于设计 §1.7）**：以上口径只覆盖**写侧**。**读侧**（`FamilyBindingView.elderName` = `maskName(decrypt(nameEnc))`）**不加 `isAvailable()` 守卫**，未装配 → `40001`（**与 `ElderArchiveService#toView` 同口径**；静默降级为空串会让调用方**无法区分「待确认」与「配置缺失」**）。⇒ 冒烟 / H5 E2E 的**起服务命令必须导出** `SENSITIVE_FIELD_KEY` / `SENSITIVE_FIELD_HASH_KEY`（`smoke-report-review-chain.sh` **自身不自带**）；残留 **D4-R8**。

### 7.4 雪花 id 与端侧（承重）
`relationId` = `elder_family_bind.id`（19 位雪花）。端侧 `requestWithText()` 专为「防 `JSON.parse` 精度截断」而存在 → **家属端所有以 `relationId` / `reportId` 为 path 参数的调用必须走原文字符串提取**，否则会出现「末位被抹零 → 拼出他人 id」的越权用例误判（**这条要写成端侧单测/验收断言**）。

### 7.5 ★ 端侧口径修正（weapp 不是基准）
| 项 | 实测 |
|---|---|
| `weapp/` 文件数 | **7 个**（`App.vue` / `pages.json` / `stores/auth*.ts` / `styles/*.scss` / `utils/request.ts`）——**无 `family.ts` / `real-api.ts` / `api.ts` / 任何页面** |
| `weapp/src/utils/request.ts` md5 | `aeeb40693724973057766cb455e67b86` |
| uni-app 三端 `request.ts` md5 | `eae7df44d986f83a6f39edc7902bc031`（**≠ weapp**） |
| uni-app 三端 `real-api.ts` / `family.ts` / `api.ts` md5 | `f41fd233…` / `6813264c…` / `2a06d09a…`（**三端一致**） |
| ⇒ **D-4b 落点** | **weixin-app 改一份 → 同步 douyin-app / ios-app → md5 三文件逐一比对**（P1-5 路子）；`weapp` **不在改列** |

### 7.6 ★ 端侧入参与契约的落差（须设计裁决）
端侧 Mock 的申请入参是 `elderKey`（扫码内容 / 老人手机号）+ `maskedName`，而契约是 `elderId`（int64）+ 可选 `elderName` / `idCardNo`。**缺「标识 → elderId」的解析通道**（9 个已冻结操作里没有）。
- **默认口径**：D-4b **申请入参按契约收口为 `elderId`**，端侧扫码内容改为承载平台约定标识（**含 `elderNo` 或 `elderId`**，`elderNo` 对外可见且不含隐私，符合 `elder` 表设计）；**「手机号搜索老人」本期降级为不支持**（端侧隐藏该入口），登记残留 **D4-R2**（解析端点另开卡）。
- 理由：不新增端点 = 不破坏「9 操作已冻结」的口径；而手机号搜索本质是**存在性探测**（枚举风险），本就不该在 M2 开放。

### 7.7 端侧响应字段落差（须适配层显式降级）
| 端侧 Mock 字段（`BoundElder`） | 契约 `FamilyBindingView` | 处置 |
|---|---|---|
| `relationId`(string) | `id`(int64) | 映射，**按字符串处理**（§7.4） |
| `maskedName` | `elderName`（脱敏） | 直映 |
| `expiresOn`('LONG_TERM' \| 'YYYY-MM-DD') | `authEndAt`(nullable) | `null → 'LONG_TERM'` |
| `boundAt` / `note` / `gender` / `age` | **无** | 端侧**显式降级**（`boundAt` 用 `authStartAt`；`gender`/`age`/`note` 显示为空态），**不得造数** |
| 数组 | `PageResult{list,total}` | 适配层取 `list` |

---

## 8. 契约与门禁计划（数字只许向上）

### 8.1 契约终态

| 项 | 现状（D-3 收口） | D-4 目标 |
|---|---|---|
| 版本 | v1.1.2 | **v1.1.3** |
| 操作总数 | 43 | **44**（+`POST /elders/{elderId}/user-binding`） |
| `implemented` | 33 | **44** |
| `planned` | 10 | **0** |
| 双向漂移 | 0 | **0**（新增控制器须登记两处 `CONTROLLERS` 硬编码清单） |
| 覆盖守卫白名单 | 4 条 | **4 条不变**（D-4 无 public 端点；`authLogout` 属会话入口，已在 `JwtAuthFilter` 短路清单内，白名单口径需设计确认） |

> ⚠️ **与用户口径的差异（须知情）**：用户算「33/10 → 42 或 43」。差异来自 `elder↔user` 绑定**在契约里无承载点**（9 个家属操作 + `authLogout` 是全部 10 个 planned），故 D-4 终态为 **44/0** 而非 43/0。若倾向不加操作，则 R1 必须降级为「机构直接改库」——**不建议**（无审计、无鉴权）。

### 8.2 门禁期望值

| 门禁 | D-3 收口（基线） | D-4 目标 |
|---|---|---|
| `mvn -o verify` 单测 | 185 / 0 | **≥ 185**（预计 +35~45：11 端点单测 + 3 条数据域锚单测 + 核验 Mapper 单测 + 限流/黑名单单测） |
| `verify-schema.sh` | 83 | **83**（**零 DDL**；金丝雀仍 = 1） |
| `verify-openapi.sh` | 8/8 · 33/10 | **8/8 · implemented 44 / planned 0 · 漂移 0** |
| 冒烟 | 79 / 0 | **≥ 79**（新增 `[9/9]` 家属域段：申请→审核→报告→趋势→照护 + 越权用例 + `unbind` ④ 闸 10008 + **无令牌 401 + `code=10002`**） |
| Checkstyle / SpotBugs | 0 / 0 | **0 / 0** |

### 8.3 QA 突变清单（在 D-3 的 5 条基础上**换靶**，共 6 条）

| # | 突变 | 期望变红 |
|---|---|---|
| M1 | 摘 `ORG_VIA_PARENT` 的 `elder_family_bind` 登记 | `DataScopePermissionHandlerTest` 新增的「scope=2 经 elder.org_id 收口」用例 |
| M2 | 摘 `SELF_VIA_PARENT` 的 `elder_family_bind` 登记 | 同上文件的「scope=1 经 elder.user_id 收口」用例（★F1 回归） |
| M3 | 摘 `SuperviseExportController` 之外——本轮换为**摘 `familyBindingApprove` 的 `@RequiresPermission`** | `ControllerAuthorizationCoverageTest`（报「裸奔」） |
| M4 | 摘 `OpenApiContractTest.CONTROLLERS` 里的家属控制器 | 双向漂移断言（契约多登） |
| M5 | 把 `unbind` 的 `logical = OR` 改回默认 `ALL` | 解绑端点**必红**（FAMILY 无 `UNBIND` 码 → 谁都进不去；用真实 HTTP 断言 `R.code=20003`） |
| M6 | 无令牌访问家属端点 | 必须 **HTTP 401 + `code=10002`**；反向证伪（FAMILY 打 `apply` 之外的机构端点）→ **200 + `20003`** |

> 沿用 D-3 两条**抗中断纪律**：**增量落盘**（报告随做随写）+ **突变即时还原**。

---

## 9. 待明确事项（交设计裁决）

1. **`reject` 契约 `mode` 订正**：`relation` → `self`（§6.2 #8）——**改契约的哪一层措辞**由设计给出精确 diff。
2. **§7.3 的降级二选一**：未装配编解码器时，核验「拒绝申请」还是「标记降级放行」——**默认拒绝**，设计须给出可验收断言。
3. **ORG_ADMIN 是否可代解绑**：默认允许（§3.3）；若设计认为应收窄为「仅 FAMILY」，须给出第三闸/服务层的可机械验收方案。
4. **`proof_cos_key` 是否本地落盘**：默认只存键；若落盘须与 P2-7 口径对齐（§7.2）。
5. **`authLogout` 是否入 `ControllerAuthorizationCoverageTest` 白名单**：它 `x-required-permission: none`，与既有 4 条白名单（会话入口/健康探针/公开验真）的关系需设计确认（**禁冗余**）。
6. **端侧「手机号搜索老人」入口**：默认隐藏（§7.6），确认是否需要在 UI 层保留"敬请期待"提示。

---

## 10. 与既有事实/文档的一致性说明

| 文档 | 本次核查的修订 |
|---|---|
| `BACKLOG` D-4 行 | 前置待裁决（核身通道）→ **已裁决**；范围补 `authLogout` + `elder↔user` 绑定（11 项） |
| `CR-M2-005` | 补 §六 核身通道裁决；补「契约无 `elder↔user` 承载点 → 新增 1 操作」 |
| `DataScopePermissionHandler` 类 Javadoc | 待实现时补 D-4 段（`ORG_VIA_PARENT` / `SELF_VIA_PARENT`） |
| `MEMORY.md` §7 | 「`elder_family_bind`/`elder_authorization`/`consent_record` 在 scope=1 下失败即拒」**不再全对**：`elder_family_bind` 将改为经 `SELF_VIA_PARENT` 收口；`elder_authorization`/`consent_record` 维持失败即拒 |
| 用户口径「以 weapp 为基准」 | **修正**为 uni-app 三端（weixin-app 基准，md5 同步）——见 §7.5 |
