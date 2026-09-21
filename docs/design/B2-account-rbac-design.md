# B2 账号、角色与权限（RBAC）· 实施设计

> 任务组：`rjixga`（M2-S1 平台底座）｜真源：PRD v1.1 §2.1 角色定义 / §2.2 权限矩阵
> 落库种子：`docker/mysql/init/03_seed_rbac.sql`｜门禁：`scripts/verify-schema.sh`（B2 断言 8 项）
> 关联红线：M1 合规产出《敏感个人信息单独同意设计》《脱敏规则》｜ER-03 / ER-07 / ER-08

---

## 1 设计目标

把 PRD §2.2 权限矩阵从「文档里的表格」变成「可执行、可断言、可回溯的鉴权契约」：

- **不增删角色、不简并权限点**——五角色、矩阵九行逐格映射；
- **矩阵的 ❌ 单元格变成 CI 红线**——角色越界即构建失败；
- **高风险动作（导出/作废/解绑/查看明文）强制二次验证 + 留痕**；
- **录入与复核互斥**在服务层强制（DDL 已落 `reviewer_id`，但无约束）。

## 2 权限矩阵落库（PRD §2.2 → 表）

### 2.1 角色（`sys_role`，5 行）

| role_code | 角色名 | data_scope | 依据 |
|---|---|---|---|
| ELDER | 老人/被评估人 | 1 本人 | 仅看本人已发布报告 |
| ASSESSOR | 评估员 | 2 本机构 | 新建/录入，不可自行复核 |
| FAMILY | 家属 | 1 本人 | 绑定范围内只读 |
| ORG_ADMIN | 机构管理员 | 2 本机构 | 本院全量 + 敏感操作双人复核 |
| SUPERVISOR | 监管 | 4 只读全局 | 只读 + 上报接收，不可编辑 |

> `data_scope` 取值域 1-本人 / 2-本机构 / 3-全量 / 4-只读全局；五角色**均不占用 3-全量**（该档留给平台级账号，不在本期五角色内）。

### 2.2 权限点（`sys_permission`，16 行）

矩阵九行展开为 14 个原子权限，另加 2 个敏感操作权限点（不常驻授予）：

| perm_code | 名称 | module | need_second_verify | 矩阵行 |
|---|---|---|---|---|
| `report:view` | 查看评估报告 | report | 0 | 行1 |
| `evaluation:order:create` | 新建评估 | evaluation | 0 | 行2 |
| `evaluation:order:create:apply` | 发起代办评估申请（受限） | evaluation | 0 | 行2🔶 |
| `evaluation:item:input` | 录入评估指标 | evaluation | 0 | 行3 |
| `evaluation:order:review` | 复核/发布报告 | evaluation | 0 | 行4 |
| `evaluation:order:review:read` | 查看复核结果（只读） | evaluation | 0 | 行4👁 |
| `care:plan:edit` | 编辑照护方案 | care | 0 | 行5 |
| `care:plan:edit:suggest` | 提交照护建议（受限） | care | 0 | 行5🔶 |
| `account:org:manage` | 机构/人员管理 | account | 0 | 行6 |
| `account:org:manage:read` | 查看机构/人员（只读） | account | 0 | 行6👁 |
| `supervise:report:submit` | 监管数据上报 | supervise | 0 | 行7 |
| `data:export` | 导出/批量操作 | report | **1** | 行8 |
| `account:family:bind` | 亲情绑定/解绑 | account | 0 | 行9 |
| `account:family:bind:reject` | 拒绝亲情绑定（老人端） | account | 0 | 行9🔶 |
| `evaluation:order:void` | 作废评估单 | evaluation | **1** | 附加 |
| `account:family:unbind` | 解绑亲情关系 | account | **1** | 附加 |

### 2.3 授权（`sys_role_permission`，23 行）

| 矩阵行 | ELDER | ASSESSOR | FAMILY | ORG_ADMIN | SUPERVISOR |
|---|:--:|:--:|:--:|:--:|:--:|
| 查看评估报告 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 新建评估 | ❌ | ✅ | 🔶apply | ✅ | ❌ |
| 录入评估指标 | ❌ | ✅ | ❌ | ✅ | ❌ |
| 复核/发布报告 | ❌ | ❌ | ❌ | ✅ | 👁read |
| 编辑照护方案 | ❌ | 🔶suggest | ❌ | ✅ | ❌ |
| 机构/人员管理 | ❌ | ❌ | ❌ | ✅ | 👁read |
| 监管数据上报 | ❌ | ❌ | ❌ | ✅ | ✅ |
| 导出/批量操作 | ❌ | ❌ | ❌ | ✅* | ✅* |
| 亲情绑定/解绑 | 🔶reject | ❌ | ✅ | ✅ | ❌ |

> `*` 需二次验证（`need_second_verify=1`）。`evaluation:order:void` / `account:family:unbind` 默认**不授予任何角色**，由机构管理员运行时经二次验证临时提权，避免权限常驻。

## 3 鉴权中间件（四道闸）

```
请求 → ① 认证(JWT 双令牌) → ② 授权(权限码) → ③ 数据域(data_scope) → ④ 二次验证 → 业务
```

| 闸 | 组件（yl-module-account / yl-common） | 职责 |
|---|---|---|
| ① 认证 | `JwtAuthenticationFilter` + `JwtTokenProvider`（B1-2 已交付） | 解析 access/refresh，注入 `LoginUser{userId, roles, perms, orgIds, dataScope}` |
| ② 授权 | `@RequiresPermission("<perm_code>")` + `PermissionAspect` | `perm_code ∈ LoginUser.perms`，否则 403；`need_second_verify=1` 的点转 ④ |
| ③ 数据域 | MyBatis-Plus `DataPermissionInterceptor` | 1→`owner/created_by = 我`；2→`org_id IN (我的机构)`；3→无限制；4→无限制且只读（写操作直接拒绝） |
| ④ 二次验证 | `SecondVerifyGuard` + Redis 一次性凭据 | 校验 `X-Second-Verify-Token`（5 min TTL，一次有效），未通过返回 403 并下发验证方式（SCAN/PHONE/FACE） |

## 4 录入 / 复核互斥（国标合规硬约束）

- **DDL**：`eval_order.reviewer_id`（`必须≠录入人`）——仅有注释，**无约束**。
- **服务层强制**（唯一正确落点）：
  `EvaluationReviewPolicy.assertNotSelfReview(order, reviewerId)` → `order.assessor_id == reviewerId` 即抛业务异常 `E409_SELF_REVIEW_FORBIDDEN`。
- **角色层前置阻断**：`ASSESSOR` 角色**不授予** `evaluation:order:review`（已由断言固化，见 §7）。
- **可选 DB 兜底**：MySQL 8 支持 `CHECK`；建议在为 `reviewer_id` 写入路径补 `CHECK (reviewer_id IS NULL OR reviewer_id <> assessor_id)`。本期先落服务层 + 断言，DB CHECK 留作加固项（避免与乐观锁/批量导入冲突）。

## 5 敏感操作二次验证与留痕（对应 M1 合规产出）

| 场景 | 权限点 | 留痕表 | 保留期 |
|---|---|---|---|
| 导出 / 批量 | `data:export` | `audit_log(sensitive=1, second_verify=1)` | 3 年（ER-03） |
| 作废评估单 | `evaluation:order:void` | `audit_log` + `eval_review_log` | 3 年 |
| 解绑亲情 | `account:family:unbind` | `audit_log` | 3 年 |
| 查看明文敏感字段 | 任意（拦截器级） | `audit_log(detail=脱敏上下文)` | 3 年 |

- `AuditLogAspect` 拦截 `@Audit(action=..., sensitive=true)`，写 `audit_log`，`retain_until = now + 3y`。
- **告知同意**：`consent_record`（ER-08 已落库）记录 `policy_code/version`、`consent_channel`、`scope_json` 快照、`revoked_at`，满足个保法举证。

## 6 多端登录适配（PRD §8 统一原则）

| 渠道 | login_type | device_type | 能力 |
|---|---|---|---|
| 微信小程序 | WECHAT | WXMP | code2session |
| 抖音小程序 | DOUYIN | DYMP | 平台授权 |
| iOS | PWD/FACE | IOS | 账密 + 短信 |
| Android | PWD | ANDROID | 账密 + 短信 |

统一入口 `POST /auth/login/{channel}` → 各渠道适配器换取平台身份 → **统一换发本服务 access+refresh 令牌**（Redis 存 refresh）→ 写 `login_log`。

## 7 门禁（`verify-schema.sh` · B2 段 · 8 项）

1. 五角色 `sys_role` = 5
2. 权限点 `sys_permission` = 16
3. 授权 `sys_role_permission` = 23
4. 敏感权限点 `need_second_verify=1` ≥ 2
5. 红线：`ASSESSOR` 不得持 `evaluation:order:review`
6. 红线：`ELDER` 不得持 `evaluation:item:input`
7. 红线：`SUPERVISOR` 不得持 `evaluation:order:review`
8. 红线：`FAMILY` 不得持 `evaluation:item:input`

## 8 落地清单（对应 B2 四个子任务）

| 子任务 | 落地物 |
|---|---|
| `rky1SW` 五角色账号体系 | `yl-module-account`：User/Role/Permission/OrgMember 聚合 + Mapper + 本设计 §2 种子 |
| `rwkav6` 权限鉴权中间件 | `@RequiresPermission` + `PermissionAspect` + `DataPermissionInterceptor` + `AuditLogAspect`（§3/§5） |
| `ri91pT` 敏感操作二次验证与留痕 | `SecondVerifyGuard` + Redis 凭据 + `audit_log`/`consent_record` 写入（§5，承接 ER-08） |
| `rpW1xZ` 多端登录适配 | `POST /auth/login/{channel}` 四渠道适配器 + 统一令牌换发（§6） |

## 9 DDL 缺口与处置

| 项 | 结论 |
|---|---|
| 建表缺口 | **0**（`sys_*` 8 表 + `login_log` + `audit_log` + `consent_record` 均已存在） |
| 种子缺口 | **已补齐**：`03_seed_rbac.sql`（本设计 §2） |
| 建议加固 | `eval_order` 互斥 `CHECK` 约束（可选，本期服务层兜底） |
| 权限常驻风险 | `void` / `unbind` 不授予常驻权限，运行时提权 |

## 10 剩余事项

- [ ] 二次验证方式与 M1《敏感个人信息单独同意设计》的**逐场景对齐复核**（转 DPO 会签，见 `docs/change/DPO-Signoff-ER-03-08-10.md`）。
- [ ] `ER-11 埋点事件名字典` 与 `r5HcnX` 埋点 SDK 一并落地。
- [ ] 权限点与 `openapi/yl-api.yaml` 中各接口的 `@RequiresPermission` 标注**逐接口回填**（随各模块实现推进）。
