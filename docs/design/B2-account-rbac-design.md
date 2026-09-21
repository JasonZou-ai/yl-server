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

### 2.2 权限点（`sys_permission`，22 行）

矩阵九行展开为 14 个原子权限，另加 2 个敏感操作权限点（不常驻授予）；**CR-M2-001（2026-09-21 CCB 会签 + PM 签发，2026-09-22 落地）
再补 6 个权限点**，共 22 个：

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
| `elder:archive:create` | 老人建档 | elder | 0 | 行10（CR-M2-001） |
| `elder:archive:create:apply` | 发起代办建档申请（受限） | elder | 0 | 行10🔶（CR-M2-001） |
| `elder:archive:read` | 查看老人档案 | elder | 0 | 行11（CR-M2-001） |
| `evaluation:task:read` | 评估任务列表查看 | evaluation | 0 | 行12（CR-M2-001） |
| `data:reveal` | 查看敏感字段明文 | security | **1** | 行13（CR-M2-001） |
| `rule:view` | 国标规则只读（条款回溯） | rule | 0 | 行14（CR-M2-001） |

> 完整清单（含逐点契约归属与角色矩阵）见 `docs/design/B2-permission-point-catalog.md`。
> 代码孪生：`PermissionCode`（22 常量）+ `SensitivePermissions`（4 个敏感点），**与种子逐格对齐，禁止私自增删**。
> ⚠ `elder:archive:read` 五角色全授予，可见范围由 §3 第三闸 `data_scope` 控制；正因其不再具备限速含义，
> 另加行为异常检测兜底（见 §5.1）。

### 2.3 授权（`sys_role_permission`，40 行）

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
| 老人建档 | ❌ | ✅ | 🔶apply | ✅ | ❌ |
| 查看老人档案 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 评估任务列表 | ❌ | ✅ | ❌ | ✅ | 👁read |
| 查看敏感字段明文 | ❌ | ❌ | ❌ | ✅* | ❌ |
| 国标规则只读 | ✅ | ✅ | ✅ | ✅ | ✅ |

> 行 10–14 为 CR-M2-001 新增（2026-09-22 落地）；授权记录 23 → **40 条**（新增 id 3024–3040）。
> `*` 需二次验证（`need_second_verify=1`）。`evaluation:order:void` / `account:family:unbind` 默认**不授予任何角色**，由机构管理员运行时经二次验证临时提权，避免权限常驻。
> 角色持有数：ELDER 4 / ASSESSOR 8 / FAMILY 6 / ORG_ADMIN 14 / SUPERVISOR 8，合计 **40**，与种子逐条对应。

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
| 查看明文敏感字段 | `data:reveal`（CR-M2-001 新增，拦截器级） | `audit_log(sensitive=1, second_verify=1)` | 3 年 |

- 敏感权限点共 **4 个**，代码登记在 `SensitivePermissions`；`PermissionAspect` 命中即转第四闸（`X-Second-Verify-Token`，Redis `GETDEL`，TTL 300s）。
- `AuditLogAspect` 拦截 `@Audit(action=..., sensitive=true)`，写 `audit_log`，`retain_until = now + 3y`。
- **告知同意**：`consent_record`（ER-08 已落库）记录 `policy_code/version`、`consent_channel`、`scope_json` 快照、`revoked_at`，满足个保法举证。

### 5.1 行为异常检测：档案异常访问告警（CR-M2-001 §2.5 · PM 补充意见）

`elder:archive:read` 授予全部 5 个角色后，**权限点层面的限速含义消失**，须补一层行为检测兜住批量拉档风险：

| 项 | 口径 | 落点 |
|---|---|---|
| 监控对象 | 非护理角色 **ELDER / FAMILY / SUPERVISOR**（ASSESSOR / ORG_ADMIN 为护理相关角色，默认不纳入） | `ArchiveAccessRateGuard` + `yl.security.archive-access-guard.monitored-roles` |
| 触发条件 | 同一账号对档案类接口（`elder:archive:read`）**滑动 1 小时**内调用 **> N**（默认 **50**） | `threshold` / `window-minutes`（外置可配） |
| 统计实现 | Redis ZSET + Lua 原子「修剪—写入—计数」；**滑动窗口，非自然小时** | `SlidingWindowCounter`（端口）/ `RedisSlidingWindowCounter`（实现） |
| 处置动作 | 写 `audit_log(action=ARCHIVE_ACCESS_ALERT)` → 通知监管角色（同窗口内去重） | `ArchiveAccessRateGuard` + `ArchiveAccessAlertNotifier` |
| 触发时机 | 第二闸授权通过后由 `PermissionAspect` 一行委派（守卫**只告警、不拦截、失败开放**） | `PermissionAspect` |

- **不拦截的理由**：CR 把「降级为单次二次验证」列为**可选**。档案读取是非敏感权限点，强制二次验证会使客户端在无 `X-Second-Verify-Token` 时直接失败，
  属契约破坏性变更，超出本 CR 授权范围，故本期只告警；生产如需启用须在 C/D 模块落地时一并变更契约。
- **失败开放的理由**：告警是旁路监控能力，计数 / 去重 / 通知任一环节异常只记警告，不得因监控设施抖动阻断档案读取这一业务主链路。
- **不新增 `verify-schema.sh` 断言**：属运行时行为，非结构约束（CR 落地清单 #7 已明示）。

## 6 多端登录适配（PRD §8 统一原则）

统一入口 `POST /auth/login/{channel}`，`channel ∈ {wechat, douyin, ios, android, face}`（大小写不敏感）。

### 6.1 渠道适配器（策略层，已实现）

| 渠道 | 适配器 | 换证方式 | 绑定解析 | 本期状态 |
|---|---|---|---|---|
| IOS / ANDROID | `PasswordChannelAdapter` | 账密（BCrypt 比对） | 直接为登录名 | ✅ 可用 |
| WECHAT | `WechatLoginAdapter` → `WechatThirdPartyAuthenticator` | `sns/jscode2session` 换 openid；`wxa/business/getuserphonenumber` 换手机号 | 手机号 HMAC 摘要 → `sys_user.phone_hash` | ✅ 代码就绪，待 app-id/secret |
| DOUYIN | `DouyinLoginAdapter` → `DouyinThirdPartyAuthenticator` | `api/apps/v2/jscode2session` 换 openid | 同上 | ⚠ 换证就绪；**手机号接口需平台单独授权**，未配置则明确拒绝 |
| FACE | `FaceChannelAdapter` | — | — | ⛔ 本期不接入（生物识别信息需单独同意 + PIA 增补，`configured()` 恒 false） |

- 分发由 `LoginChannelRegistry` 完成：**未注册或未完成平台配置的渠道一律明确拒绝**，不静默降级为其它认证方式；同渠道重复注册在启动期即失败。
- 登录名解析后统一走：账号状态校验 → 换发本服务 access+refresh（refresh 存 Redis 可吊销）→ 写 `login_log`（成功/失败均留痕）。
- 平台侧标识（openId）**不落库、不入日志**，只在内存用于本次绑定解析（对齐 ER-11 S2 禁采）。

以 `login_log` 记录口径：`WECHAT/WXMP`、`DOUYIN/DYMP`、`PWD/IOS`、`PWD/ANDROID`、`FACE/IOS`。

### 6.2 ⚠ 第三方绑定的 DDL 缺口（ER-14 候选）

现状：`sys_user` **无** `openid/unionid` 列，全库亦无第三方账号绑定表 → 平台登录**无法持久绑定本服务账号**。

| 方案 | 说明 | 代价 |
|---|---|---|
| **A. 手机号绑定（本期已实现）** | 平台回传手机号 → HMAC 摘要命中 `phone_hash`；明文不落库 | 每次登录都依赖平台手机号授权；用户未授权手机号即无法登录 |
| B. 新增绑定表（建议评估） | `sys_user_third_party(user_id, platform, open_id, union_id)`，openId 以密文 + 摘要列存储 | DDL 变更，须走 ER 评审 + `verify-schema.sh` 断言补充；openId 属敏感外部标识，需纳入脱敏口径 |

> 建议：若产品要求「首次授权后免手机号登录」，须采纳方案 B 并立 ER-14；本期先以方案 A 交付，**不擅自加表**。

## 7 门禁（`verify-schema.sh` · B2 段 · 8 项）

1. 五角色 `sys_role` = 5
2. 权限点 `sys_permission` = **22**（CR-M2-001 补齐后）
3. 授权 `sys_role_permission` = **40**
4. 敏感权限点 `need_second_verify=1` **= 4**（精确值，含 `data:reveal`）
5. 红线：`ASSESSOR` 不得持 `evaluation:order:review`
6. 红线：`ELDER` 不得持 `evaluation:item:input`
7. 红线：`SUPERVISOR` 不得持 `evaluation:order:review`
8. 红线：`FAMILY` 不得持 `evaluation:item:input`

> CR-M2-001 落地后，原「≥ 下界」口径已改为**精确值**（R2 修复），并新增 4 条矩阵红线（`ELDER`/`FAMILY` 不得持
> `elder:archive:create`、`ASSESSOR`/`SUPERVISOR` 不得持 `data:reveal` 等）。

脚本断言总数 **63 项**（R1–R5 闭合后；口径唯一来源见 `docs/quality/verify-schema-assertion-reconciliation.md`），本地 MySQL 8.0.37 实测 **全绿 0 失败**（2026-09-22；B2 关账时为 43 项）。
<br>**行为类告警（`ArchiveAccessRateGuard`）不纳入本脚本断言**——属运行时行为而非结构约束（§5.1）。

### 7.1 单元测试

`ArchiveAccessRateGuardTest`（7 例）：超阈值告警 / 恰好等于阈值不告警 / 护理角色不纳入 / 非档案权限点不计数 /
同窗口去重 / 开关关闭与阈值非法时惰性 / 计数设施异常时失败开放。

## 8 落地清单（对应 B2 四个子任务）

| 子任务 | 落地物 |
|---|---|
| `rky1SW` 五角色账号体系 | `yl-module-account`：User/Role/Permission/OrgMember 聚合 + Mapper + 本设计 §2 种子 |
| `rwkav6` 权限鉴权中间件 | `@RequiresPermission` + `PermissionAspect` + `DataPermissionInterceptor` + `AuditLogAspect`（§3/§5） |
| `ri91pT` 敏感操作二次验证与留痕 | `SecondVerifyGuard` + Redis 凭据 + `audit_log`/`consent_record` 写入（§5，承接 ER-08） |
| `rpW1xZ` 多端登录适配 | 渠道适配器策略层（§6.1）+ `POST /auth/login/{channel}`、`/auth/refresh`、`/auth/second-verify` |


## 9 DDL 缺口与处置

| 项 | 结论 |
|---|---|
| 建表缺口 | **0**（`sys_*` 8 表 + `login_log` + `audit_log` + `consent_record` 均已存在） |
| 种子缺口 | **已补齐**：`03_seed_rbac.sql`（本设计 §2） |
| 建议加固 | `eval_order` 互斥 `CHECK` 约束（可选，本期服务层兜底） |
| 权限常驻风险 | `void` / `unbind` 不授予常驻权限，运行时提权 |
| **第三方账号绑定表** | **已落地**：ER-14 经三方裁决通过并建表 `sys_user_third_party`（仅存 HMAC 摘要 + 双 `active_uk` + `retain_until`），见 `docs/reviews/ER-14-third-party-binding-proposal.md` |
| 敏感字段编解码装配 | 原为**零装配**（`SensitiveFieldCodec` 无 Bean）；本轮补 `SensitiveFieldConfig`，**条件装配**（密钥非空才建，避免 dev 起不来） |

## 10 剩余事项

- [x] 权限点与 `openapi/yl-api.yaml` 各接口的 `@RequiresPermission` 标注**逐接口回填** → **已完成**（2026-09-22）：22 个操作 100% 回填 `x-required-permission`，**0 处 `pending-cr`**；对照矩阵见 `docs/design/B2-permission-api-matrix.md`（v2）；完整权限点清单见 `docs/design/B2-permission-point-catalog.md`。
- [x] 多端登录渠道适配器（`rpW1xZ`）→ **已完成**（2026-09-21）：见 §6.1；FACE 明确不接入。
- [x] **ER-14 评估**：第三方账号绑定表（方案 B，§6.2）→ **已通过并落地**（2026-09-21）。
- [x] CR-M2-001 落地清单 #2 / #4 / #7 → **已完成**（2026-09-22）：代码常量同步（22 常量 + 4 敏感点）、契约回填（7 → 0 处 `pending-cr`）、审计配套（§5.1 `ArchiveAccessRateGuard`）。
- [x] CR-M2-001 落地清单 #6（文档同步）→ **已完成**（2026-09-22）：`B2-permission-api-matrix.md` 升 v2（缺口清零）、本设计 §2/§5/§7/§9 同步、新增 `B2-permission-point-catalog.md`。
- [ ] CR-M2-001 落地清单 #5（回写 PRD §2.2 增 5 行矩阵 + 修订记录加「v1.1 同版回写 CR-M2-001」）——须 CCB 追认，待办。
- [x] 告警参数定案（随 CCB 会签，2026-09-21）：**N = 50**、仅监控非护理角色 ELDER / FAMILY / SUPERVISOR（`ASSESSOR` / `ORG_ADMIN` 不纳入）；参数外置可配，调整无需改码。
- [ ] 反向缺口 6 类「权限点无归属接口」随 C/D 模块补登契约（§4 of `B2-permission-api-matrix.md`）。
- [ ] 抖音手机号接口：待平台授权文档确认后配置 `yl.security.third-party.douyin.phone-url`。
- [ ] 二次验证方式与 M1《敏感个人信息单独设计》的**逐场景对齐复核**（DPO 已会签 ER-03/08/10，人脸场景仍待单独同意文本）。
- [ ] `ER-11 埋点事件名字典` 与 `r5HcnX` 埋点 SDK 一并落地。
- [ ] 各业务模块 Controller 实现时按 §5/§2 逐接口标注 `@RequiresPermission`（矩阵文档为唯一基准）。

