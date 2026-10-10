# D-4 家属端 + 端侧切真 · 增量设计 + 任务分解

> 角色：架构师（高见远）｜产出类型：**设计 + 裁决 + 任务分解**（不含业务代码；mermaid 属设计产物）
> 范围：`yl-server` **11 项** —— 家属端 9 操作翻旗 + `authLogout` + **新增 1 个** `POST /api/v1/elders/{elderId}/user-binding`（R1 承载点）；**零 DDL**
> 真源：`docs/reviews/D4-foundation-check.md`（地基核查，已实测）｜`openapi/yl-api.yaml` v1.1.2｜`docker/mysql/init/0*.sql`｜`yl-api`/`yl-module-account`/`yl-module-archive` 既有四闸基建
> 节拍：**严格照 `docs/design/D3-report-supervise-domain-design.md` 的结构与粒度打，不重新发明范式**
> 阅读顺序：§1 裁决（可独立抄入 CR 卡/任务卡）→ §2 文件清单与类签名 → §4 端点契约表 → §6 数据域 diff（零 DDL 论证）→ §7 契约变更 → §8 门禁影响 → §9 任务列表
> 端侧专章另见：`docs/design/D4-frontend-migration.md`

---

## 0. 事实核对（本设计采信的现状，均已逐文件实读确认）

> 本节结论以**实读**为准；凡与 `D4-foundation-check.md` 转述冲突处，均已在本节显式标注。

| 事实 | 位置 | 对本设计的影响 |
|---|---|---|
| D-4 真实范围 = **11 项**：家属端 9 操作 + `authLogout` + 新增 `POST /elders/{elderId}/user-binding` | `openapi/yl-api.yaml` L389-399 / L990-1197（**全部 `x-status: planned`**）；`D4-foundation-check.md` §0/§6.2#4 | 契约终态 **44 操作 = implemented 44 / planned 0**；版本 v1.1.2 → **v1.1.3** |
| `yl-module-archive` **已落地 D-2**：`Elder`/`ElderMapper`/`SensitiveFieldSupport`/`ElderArchiveService`/`ElderController`/`ElderNoGenerator` + 3 测试 | `find yl-modules/yl-module-archive/src`（**15 个文件**） | 家属域**落此模块**（§6.2#7），新代码与之同形；`SensitiveFieldSupport` 直接复用 |
| `elder` 表**已有 `user_id BIGINT NULL` + `uk_elder_user`**（D-2 落） | `02_schema.sql` L205 / L215 | `POST /elders/{elderId}/user-binding` **零 DDL**，仅写该既有列 |
| `elder_family_bind` **无 `org_id`**；`family_user_id` 是**家属锚**（非老人本人） | `02_schema.sql` L221-241 | ★F3（跨机构可见）/ ★F1（老人拒绝死路）的根因；处置见 §6 |
| `elder_family_bind` **有 `deleted` 列** + 生成列 `active_uk`（`IF(deleted=0,1,NULL)`） | `02_schema.sql` L232 / L234 | 实体**映射 `deleted`**（正常列）、**不映射 `active_uk`**（生成列，撞 `care_plan`/`export_task` 同类坑）；查询显式 `deleted=0` |
| `eval_report` / `report_dimension_score` / `care_plan` **均在第三闸登记齐全**（直锚/反查锚） | `DataScopePermissionHandler` L102/123/167/179/184；L472-548 schema | 家属读报告/趋势/照护链路（操作 6~9）**天然可达**，无需改数据域 |
| `elder_family_bind` ∈ `SELF_DENY_TABLES`（scope=1 恒假） | `DataScopePermissionHandler` L147 | ★F1 根因；处理见 §6（迁出 deny → 入 `SELF_VIA_PARENT`） |
| `account:family:unbind`(2016) `need_second_verify=1`、**未授予任何角色**；`account:family:bind`(2013)→FAMILY/ORG_ADMIN；`account:family:bind:reject`(2014)→仅 ELDER | `03_seed_rbac.sql` L61-65 / L86 | `unbind` **必须 OR 语义**；「运行时临时提权」= OR + 强制第四闸（§1.3） |
| `PermissionAspect` **L48-58**：`logical` 判「能否进入」后，`Arrays.stream(needed).anyMatch(SensitivePermissions::isSensitive)` → 含任一敏感码**一律强制第四闸** | `PermissionAspect.java` L48-58 | `unbind` 写 `{BIND, UNBIND} + OR`（先例：`AssessmentController` L183-184 `void`） |
| `SensitivePermissions` 4 码 = `data:export`/`evaluation:order:void`/`account:family:unbind`/`data:reveal` | `SensitivePermissions.java` L18-23 | **本卡零新增敏感点**；`unbind` 命中既有码 |
| `sys_permission` **22 个**；`account:org:manage`(2009)→**ORG_ADMIN 独占**（授权仅 `(3015, 1004, 2009)`；SUPERVISOR 只得 2010 `:read`） | `03_seed_rbac.sql` L54 / L80 / L122-125 | `user-binding` **与 `familyBindingApprove`** 复用 `account:org:manage`（零新码），`sys_permission` 仍 22 |
| **`elder_family_bind` 在 scope=5 下经 `family_user_id = me` 收口**（`relationSegment()` L233-235），即 **FAMILY 调用者完全可达本表**（只是范围收窄到「本人发起的绑定行」） | `DataScopePermissionHandler.java` L233-235 | ★C1 根因：`account:family:bind` 被 FAMILY/ORG_ADMIN **共享** → FAMILY 过第二闸 + 数据域可达本人待审行 ⇒ **家属可自我放行**；处置见 §1.2a / §4（`approve` 权限码本体订正为 `account:org:manage`） |
| 业务失败一律 **HTTP 200 + `R.code`**；未认证 **401 + `code=10002`**；无权 **200+20003**；缺二次验证 **200+10008** | `ErrorCode.java`；`GlobalExceptionHandler`；`SecurityConfig` L96-99 | 所有"被拒"断言下沉 `R.code`；`authLogout` 后旧令牌 → **401 + 10002** |
| `JwtAuthFilter`：`SESSION_ENTRY_PATHS = [/auth/login, /auth/refresh]` 短路；`parse` 成功 → `assemble` → 置 SecurityContext | `JwtAuthFilter.java` L73-114 | `authLogout` **新增黑名单查询**落点见 §1.3 |
| `RedisRefreshTokenStore`：`KEY_PREFIX = "yl:rt:"`；`revoke(token)` = `delete` | `RedisRefreshTokenStore.java` L25/53-57 | refresh 失效走既有 `revoke`；access 黑名单另建 `yl:bl:at:` |
| `RefreshTokenStore` 接口在 `cn.yl.common.security`；实现 `RedisRefreshTokenStore` 在 `yl-infrastructure` | `RefreshTokenStore.java` / `RedisRefreshTokenStore.java` | access 黑名单**同范式**：接口入 `yl-common`、实现入 `yl-infrastructure` |
| `JwtTokenProvider`：access `exp` = `now + accessTtl`（默认 2h，**dev/契约注记 300s**）；`parse(token)` 可读 `Claims.getExpiration()` | `JwtTokenProvider.java` L89-111/139-141 | 黑名单 TTL 取**令牌剩余有效期**（§1.3） |
| 两个门禁测试 `CONTROLLERS` **均为硬编码 list**（各 14 名）；覆盖白名单 **= 5 条** | `OpenApiContractTest.java` L61-77；`ControllerAuthorizationCoverageTest.java` L53-78 | 新控制器**须登记**；`authLogout` 白名单**+第 6 条**（§1.3/§7） |
| `verify-openapi.sh` 漂移抽查用 `find . -name '*Controller.java'` **自动发现**（非硬编码） | `verify-openapi.sh` L153-165 | 新控制器**无需**登记进脚本；仅 `@RequestMapping` 前缀 + 方法映射被扫 |
| `verify-schema.sh`：`EXPECTED_TOTAL_ASSERTIONS=83`；保留字金丝雀 = **1** | `verify-schema.sh` L483 / L269 | **零 DDL** → **83 不变**、金丝雀 **1 不变** |
| `04_seed_dev_accounts.sql` 只播 `assessor_dev`(ASSESSOR) / `orgadmin_dev`(ORG_ADMIN) | `04_seed_dev_accounts.sql` | **无 ELDER/FAMILY 账号** → D-4a 必补（§1.4 张力·种子） |
| 端侧改基准 = **uni-app 三端**（weixin/douyin/ios）；`weapp` **不是**基准（7 文件、无 `family.ts`/`real-api.ts`/`api.ts`/页面） | 实读 `<APP>/weixin-app/src/utils/`；`ss` 三端 md5 | D-4b 落点 = **weixin-app 改一份 → md5 同步 douyin-app/ios-app** |
| 三端 md5（实读）：`family.ts 6813264c…` / `real-api.ts f41fd233…` / `api.ts 2a06d09a…` / `request.ts eae7df44…` | `md5sum` 实测 | md5 同步**验收命令**见 §9 T09 |
| 家属页实际 **8 个**：`family-{home,bind,binds,reports,trend,care,privacy,messages}` | `ls weixin-app/src/pages/` | 与口径"7 个"的**差异已登记**（实读 8 个）；改动与否见 `D4-frontend-migration.md` |

---

## 1. 裁决记录

> 5 个真实张力逐条裁定。**结论在前、理由与否决项在后**，可直接抄入 CR 卡。

### 1.1 张力一裁决：★F2 核验 Mapper 的边界 —— **模块自持只读投影 + 明文入参经 hash 精确匹配 + 未装配直拒 + 按 userId 频控**

| 子项 | 裁定 | 依据 |
|---|---|---|
| **(a) 模块归属** | **`yl-module-archive`** —— `FamilyBindingVerifyMapper`（`@Mapper`，手写 `@Select`） | `D4-foundation-check.md` §6.2#7：家属域整体落 archive（与 `elder` 同模块）；D-3 判据"跨域只读用模块自持投影 Mapper" |
| **(b) 匹配字段** | **明文入参 `elderName` + `idCardNo`**（契约 `FamilyBindingCreate` 既有字段），服务端经 `SensitiveFieldSupport.hashForSearch()` 算 `name_hash` / `id_card_hash` **精确匹配**（`elder.id = ? AND name_hash = ? AND id_card_hash = ? AND deleted = 0`） | **库内无明文姓名/身份证列**（只有 `*_enc`/`*_hash`/`*_mask`，`02_schema.sql` L188-192）→ 只能用 hash；GCM 密文 IV 随机**不可比对** |
| **(c) 编解码器未装配（dev 无密钥）口径** | **直接拒绝申请** → `BizException(ARCHIVE_SENSITIVE_ENCRYPT_FAILED = 40001)`，**不落任何 `elder_family_bind` 行** | 采 `D4-foundation-check.md` §7.3 默认：**宁可拒绝，不可假核验**（假核验 = 绑定了非目标老人，比拒绝更危险） |
| **(d) 频次上限** | **按 `userId`，窗口 60s，上限 10 次**；超限 → `BizException(RATE_LIMITED = 10429)`。Redis 键 `yl:fam:verify:<userId>`（`INCR` + `EXPIRE 60`），复用既有 `StringRedisTemplate` | 防**批量枚举 `elderId`**（存在性探测）；叠加既有 `RateLimitInterceptor`（匿名 30/60s per IP） |
| **(e) 防枚举** | 「不存在」与「不匹配」**返回同一结果**（`count == 0` → 同一 `BizException`）；**只返回布尔**（`int count`，服务层仅 `> 0` 判定） | 无 PII 泄漏面；不泄露老人是否存在 |
| **(f) 老人账号态探测（供 §3.3 状态分派）** | `FamilyBindingVerifyMapper` **新增第二个方法** `hasLoginAccount(Long elderId): boolean`（`SELECT EXISTS(SELECT 1 FROM elder WHERE id = #{elderId} AND user_id IS NOT NULL)`）；**同属「绕过第三闸的读」**，同样**只返标量（boolean）**、不返任何老人字段；与 (b) 的 `countExactMatch` 共用同一频控 + 防枚举纪律 | ★C2：申请时老人尚未绑定 → `elder` 在 scope=5 下恒空、无法经第三闸读；`create` 须据「老人是否已有登录账号」决定落 `status=0` 还是 `1`（§3.3）。只泄漏 1 bit「该老人是否已有账号」，而申请人已用 `name_hash`+`id_card_hash` 精确自证身份 → 无新增枚举面 |

**可机械验收断言（写入 T03 单测 + T08 冒烟）**

1. **单测 `FamilyBindingVerifyMapperTest`**：断言该 Mapper 的**全部方法**（`countExactMatch(int)` + `hasLoginAccount(boolean)`）**返回类型 ⊆ `{int, boolean, long}`**（反射扫描），且**无任何 `Elder`/`FamilyBindingView`/`String`（明文姓名/证件）返回**——钉死「只返回标量、不返任何老人字段/PII」（QA 独立证伪项 §8.3 M1'）。
2. **单测 `FamilyBindingServiceTest#applyRejectsWhenCodecUnavailable`**：`SensitiveFieldSupport.isAvailable()==false` → `apply(...)` 抛 `BizException(40001)`；且 mock 的 `ElderFamilyBindMapper.insert` **零调用**。
3. **断言（服务层）**：`count==0` 时抛 `BizException(NOT_FOUND=10004)`，**与"老人不存在"同码**。
4. **频控单测**：第 11 次 `apply`（同 userId，60s 内）→ `BizException(10429)`。
5. **类 Javadoc 显式论证**：该 Mapper 是**本卡唯一绕过第三闸的读**（`elder` 在 scope=5 下经 `MY_ELDER_SUBQUERY` 收口 → 申请前目标老人**尚未绑定 → 恒空**，故法律上无法走第三闸），必须以**最小化（只返布尔）+ 防枚举（同码）+ 频控**三重补偿。

**被否决方案**
- **B：把核验搬到"申请人自证"（不查库）** —— **否决**。等于不核验，`elderName`/`idCardNo` 形同虚设。
- **C：临时放宽 `elder` 的关系级数据域** —— **否决**。会让家属读到**未绑定**老人的档案，越权面直接上线。
- **D：复用第三闸（`elder` 在 scope=5 的 `MY_ELDER_SUBQUERY`）做核验** —— **否决**。申请时目标老人**尚未生效绑定** → 子查询恒空 → 核验恒空（这正是 ★F2 的表象）。

---

### 1.2 张力二裁决：`POST /elders/{elderId}/user-binding` 契约形态 —— **复用 `account:org:manage` + `org` 数据域 + 幂等键；本期只做 `elder.user_id`，不碰 `elder_authorization`**

| 项 | 裁定 |
|---|---|
| Method / Path | **`POST /api/v1/elders/{elderId}/user-binding`**（子资源 `user-binding`，RESTful 语义：把某账号绑为该 `elder` 的本人） |
| 请求体 `UserBindingRequest` | `userId`（`@NotNull Long`，目标 ELDER 账号 `sys_user.id`）。**不含 `elderId`**（在 path）。**★R-1（2026-09-29 订正）：已删除可选 `authEndAt`** —— `elder` 表无 `auth_end_at` 列，零 DDL ⇒ 无承载；「有效期」语义属 `elder_authorization`（ER-07，本卡不接，D4-R3）。**不接受不落库**（见 §1.8 R-1） |
| `x-required-permission` | **`account:org:manage`**（2009 → ORG_ADMIN；**零新码**） |
| `x-data-scope` | **`{ mode: org, by-role: { ORG_ADMIN: org } }`**（`elder` 已在 `ORG_COLUMN` L99 → 第三闸自动 `elder.org_id IN (myOrgs)`） |
| 幂等 | **`Idempotency-Key` 必填**（`@Idempotent(verifyBody=true)`，缺 → `10007`） |
| 返回体 | `R<UserBindingView>` → `{elderId, userId, boundAt}`（`boundAt = elder.updated_at`） |
| 唯一键冲突（`uk_elder_user`） | **`CONFLICT = 10005`**（**绝不 500**）：目标 `elder.user_id` 已被占用 → 10005「该老人已绑定其他账号」；`DuplicateKeyException` 捕获转换（同 `ElderArchiveService.insertWithUniqueElderNo` L185-197 范式） |
| 机构校验（**三道显式**） | ① 目标 `sys_user` ∈ 本机构（`sys_org_member` 存在且 `status=1`）否则 `20003`；② 该 `elder` 未被他人占用（`user_id` 为空或等于请求 `userId`）否则 `10005`；③ `elder.org_id ∈ 我的机构` 由**第三闸自动**收口（查不到 → `10004`） |
| **与 `elder_authorization` 的关系** | **本期只写 `elder.user_id`，完全不碰 `elder_authorization`**。理由见下 |

**为什么不碰 `elder_authorization`（避免两套"授权"并存造成口径分裂）**

| 维度 | `elder.user_id`（本卡采纳） | `elder_authorization`（本卡**不碰**） |
|---|---|---|
| 语义 | **老人本人**的登录账号（"这张档案属于哪个账号"） | **被授权账号**对某老人的**范围化授权**（`scope` = `report/archive/care` 逗号分隔） |
| 基数 | `1 : 1`（`uk_elder_user` 唯一） | `1 : N`（`grantee_user_id` 可多条，按 `scope` 细分） |
| 消费方 | 第三闸 scope=1：`elder.user_id = 我`（D-2 已登记 L128） | 第三闸**未登记**（`elder_authorization` 在 `SELF_DENY_TABLES`，scope=1 恒假） |
| D-4 需求 | 老人端"看自己的档案"（`elder:archive:read` + scope=1） | 无（家属走 `elder_family_bind` 关系级，非 `elder_authorization`） |

⇒ **两套机制语义不同构**（一个是"归属"，一个是"授权范围"）；本期若同时写，会造成"同一老人两条授权真源、口径分裂"，且 `elder_authorization` 的 `scope` 字段无消费方。**登记残留 D4-R3**（`elder_authorization` 的"第三方范围化授权"另开卡）。

**被否决方案**
- **把 `userId` 也放进 `elderUpdate`（`PUT /elders/{elderId}`）** —— **否决**。`elder` 更新端点本期不存在；且"绑定本人账号"是**独立的鉴权归属变更**（需独立审计 `@Audit(action="ELDER_USER_BIND")`），不应混入档案编辑。
- **改库直贴（无端点）** —— **否决**（`D4-foundation-check.md` §8.1）：无审计、无鉴权、无幂等。

---

### 1.2a ★C1 越权修复：`familyBindingApprove` 的权限码本体订正 —— **`account:family:bind` → `account:org:manage`**

> 本节为**独立裁决**，可单独抄入 CR 卡。修的是**权限码错配**（非数据域），**零新增权限码**。

**问题（实测）**：`FamilyBindingController#approve` 原挂 `account:family:bind`（2013 → **FAMILY + ORG_ADMIN 均持码**）。而 `relationSegment()` L233-235 对 `elder_family_bind` 渲染 `(elder_family_bind.family_user_id = me)` —— `family_user_id` 正是 FAMILY 自己的列 ⇒ **FAMILY 完全可达本人发起的待审绑定行**。两闸叠加 ⇒ **FAMILY 自己把自己的绑定批成 `status=2`**（`0/1 → 2`），**绕过 PRD §4.2 第④步「机构审核」= 自我授权越权**，并连带让 `reports`/`trend`/`care-plan`（第三闸子查询判据即 `status=2`）**立刻全可达**。这正是「**权限已授 ≠ 数据意图**」——**权限码被两个角色共享，语义却只属其中一个；数据域修不掉它**。

| 项 | 裁定 |
|---|---|
| `approve` 的 `x-required-permission` | **`account:org:manage`**（2009；授权仅 `(3015, 1004, 2009)` ⇒ **ORG_ADMIN 独占**，SUPERVISOR 只有 2010 `:read`）⇒ **第二闸即挡 FAMILY**，与数据域是否可达无关 |
| 契约形态 | **本体订正**（同 `ElderCreate.required` 先例）：把 `openapi/yl-api.yaml` L1057 的 `x-required-permission` 由 `account:family:bind` 改为 `account:org:manage`（**非新增字段、非语义扩张**） |
| `x-data-scope` | **保持 `mode: org`** + 补 `condition`（原 §7 订正②不变）—— 权限码与数据域**各修各的闸** |
| 新增权限码 | **零**（`sys_permission` 仍 22、`sys_role_permission` 仍 40；`03_seed_rbac.sql` 零改动） |
| 语义自洽性 | `account:org:manage` = "机构/人员管理"，同时承载 `user-binding`（机构侧人员/归属管理）⇒ **一码两用、语义不分裂** |
| 控制器落笔 | `@RequiresPermission(PermissionCode.ACCOUNT_ORG_MANAGE)`（**不是** `ACCOUNT_FAMILY_BIND`） |

**被否决方案**
- **B（服务层按 `LoginUser.roles` 判角色）** —— **否决**。把「鉴权语义」塞进服务层，与「第二闸管授权、第三闸管数据域」的四闸分层冲突；且 FAMILY 若同时持 `account:org:manage`（不成立，但口径脆）即失效。
- **C（新增 `account:family:bind:approve`）** —— **否决**。违反「零新增权限码」裁决（`sys_permission` 须仍 22）。

**可机械验收断言（写入 T04 单测 + T08 冒烟）**
1. **单测 `FamilyBindingControllerTest#approveRequiresOrgManage`**：反射读 `approve` 的 `@RequiresPermission.value()` == `{PermissionCode.ACCOUNT_ORG_MANAGE}`（且**不含** `ACCOUNT_FAMILY_BIND`）。
2. **真实 HTTP（T08）**：`FAMILY` 打 `POST /family/bindings/{id}/approve` → **HTTP 200 + `R.code=20003`**（`ROLE_NOT_PERMITTED`）—— **直接钉住本修复**（若有人把权限码改回 `account:family:bind`，该断言立刻变红）。

---

### 1.3 张力三裁决：`authLogout` 黑名单键与失效路径 —— **`yl:bl:at:<sha256>` + 剩余有效期 TTL + `JwtAuthFilter` 早短路；双令牌同时失效**

| 子项 | 裁定 | 依据 |
|---|---|---|
| **access 黑名单键** | **`yl:bl:at:<sha256Hex(token)>`**（值 = `userId`） | 不存原令牌（防 Redis 泄漏即令牌泄漏）；`sha256` 定长、无碰撞面 |
| **TTL** | **令牌剩余有效期** = `claims.getExpiration() - now`（秒）；`<=0` 已过期无需登记；`exp` 不可解析时退化为 `accessTtl`（当前 300s） | 固定 300s 会：短于剩余有效期 → 留窗口；长于 → 无谓占用 |
| **失效机制** | 端侧 `logout()` 携带 Bearer access + body `{refreshToken}`：① access 按 `sha256` 记黑名单（TTL=剩余）；② **同时** `RefreshTokenStore.revoke(refreshToken)`（键 `yl:rt:` → 实现前缀 `yl:rt:`，**以实读为准**） | 双令牌**同时**失效（`D4-foundation-check.md` §6.2#6） |
| **`JwtAuthFilter` 落点** | 在 `tokenProvider.parse(token)` **成功后、`assemble(claims)` 之前**插一句 `if (blacklistStore.isBlacklisted(token)) { writeUnauthorized(response); return; }` | 早短路：命中即**不装配身份**（等同未认证）；复用既有 `writeUnauthorized`（401 + `R(10002)`） |
| **`x-required-permission`** | **`none`**（会话出口，安全边界 = 登录态本身，非权限矩阵某一格） | 与 `/auth/second-verify` 同口径（契约 L395） |
| **覆盖守卫白名单** | **新增第 6 条 `AuthController#logout`**；类注释理由由"三类"扩为"**会话入口/出口**、健康探针、公开验真" | `logout` handler **不得**挂 `@RequiresPermission`（否则 `whitelistHasNoRedundantEntry` 报"冗余豁免"） |
| **`OpenApiContractTest.CONTROLLERS`** | `AuthController` **已登记**（L63），`logout` 为同控制器新增 handler → **无需新增条目** | 漂移断言比较的是"端点集合"，`AuthController#logout` 端点会自动进入 `controllerEndpoints()` |

**爆炸半径评估**

| 影响面 | 评估 | 动作 |
|---|---|---|
| `JwtAuthFilterTest`（若存在） | 新增**一个可选依赖**（`TokenBlacklistStore`）+ **一行命中即短路**；未命中路径**行为完全不变** | 测试注入 mock `TokenBlacklistStore`（默认返回 `false`）→ **既有断言不红** |
| 既有冒烟 | 无"旧 access 令牌可继续用"断言（逐脚本检索确认） | **不红** |
| `RedisRefreshTokenStoreTest`（若存在） | `revoke` 行为**未改** | 不红 |
| `authLogout` 自身 | 新端点；翻旗后 `OpenApiContractTest` 需 `AuthController` 已登记（**已登记**） | 见 §7 |

**可验收断言（真实 HTTP，写入 T08 冒烟 `[9/9]` 段）**

```
① login(family_dev) → 得 A(access) + R(refresh)
② GET  /elders?pageNum=1  with Bearer A        → HTTP 200 + R.code=0        （A 有效）
③ POST /auth/logout       with Bearer A + {refreshToken:R} → HTTP 200 + R.code=0
④ GET  /elders?pageNum=1  with Bearer A        → HTTP 401 + R.code=10002    （A 已入黑名单）
⑤ POST /auth/refresh      with {refreshToken:R} → HTTP 200 + R.code=10002   （R 已 revoke；refresh 端点 permitAll → HTTP 200）
⑥ 无 Authorization 头访问 /elders               → HTTP 401 + R.code=10002   （CR-M2-007 回归）
```

**被否决方案**
- **黑名单存原令牌** —— **否决**（Redis 泄漏 = 令牌泄漏）。
- **在 `RestAuthenticationEntryPoint` 做黑名单** —— **否决**。入口点只处理**过滤器链内**的 `AuthenticationException`；黑名单命中时 filter 已成功解析（无异常），无法触发入口点。
- **`authLogout` 挂 `@RequiresPermission`** —— **否决**。契约 `x-required-permission: none`；且退出登录**不应**依赖某一权限格（否则"无该码"者无法登出）。

---

### 1.4 张力四裁决：端侧适配层的形态 —— **映射下沉 `real-api.ts`；缺字段显式降级（绝不造数）；三端 md5 同步**

> 端侧逐文件 diff 要点与验收命令见 `docs/design/D4-frontend-migration.md`；此处仅给**裁决口径**。

| 子项 | 裁定 | 理由 |
|---|---|---|
| **(a) 状态码 ↔ `BindStatus` 映射位置** | **`real-api.ts`**（新增 `bindStatusOf(status: number): family.BindStatus`）；`BindStatus` **类型**仍定义在 `family.ts` | `family.ts` = Mock 领域层（可离线）；`real-api.ts` = "真实↔Mock **签名对齐**"的转换层 → 所有"后端形态 → 端侧形态"的翻译**集中在此**，页面零改动（`api.ts` 单行切换） |
| **(b) `getMyBindings()` 返回形态** | 适配层取契约 `PageResult{list}` 的 `list`，逐行 `FamilyBindingView` → `BoundElder`；**字段不齐处显式降级**（见下表），**绝不造数** | 契约 `FamilyBindingView` 无 `boundAt`/`note`/`gender`/`age`/全名 `name` |
| **(c) `expiresOn` ↔ `authEndAt`** | `authEndAt == null` → `'LONG_TERM'`；非空 → `authEndAt.slice(0,10)`（`YYYY-MM-DD`） | **本地到期判定逻辑删除**（`family.ts#listBindings` 的 `expiresOn < today → EXPIRED` 段删除）→ 到期由**后端 `status`** 决定（避免端侧自造判定口径） |
| **(d) 页面改动** | 见 `D4-frontend-migration.md` §3；**`family-bind.vue` / `family-binds.vue` / `family-home.vue` / `family-reports.vue` / `family-trend.vue` / `family-care.vue` 必改**；`family-privacy.vue` / `family-messages.vue` **不改**（无对应后端端点）。**「返回前经 `relationId` 校验」必须保留**（在 `real-api.ts` 适配层实现） | 防"传空即全量"；`relationId` 是权限键（姓名可重名） |
| **(e) 三端 md5 同步** | 改 `weixin-app` → `cp` 到 `douyin-app`/`ios-app` → **逐一 `md5sum` 比对相等**（命令见 T09） | P1-5 已验证的路子；`weapp` **不在改列** |

**端侧字段显式降级清单（★ 绝不造数）**

| 端侧 `BoundElder` 字段 | 契约 `FamilyBindingView` | 适配层处置 |
|---|---|---|
| `relationId`(string) | `id`(int64) | `String(view.id)` —— **须用 `requestWithText()` 取原文**（§1.5 雪花精度） |
| `elderId`(string) | `elderId`(int64) | `String(view.elderId)`（同样走 `requestWithText`） |
| `name`(全名) | **无**（仅 `elderName` 脱敏） | 恒空串 `''`（**不造数**）；`shownNameOf` 已有"非 ACTIVE 不给全名"逻辑，页面自然走脱敏名 |
| `maskedName` | `elderName`（脱敏；非 ACTIVE 时后端不下发） | `view.elderName ?? ''` |
| `relation` | `relation`(枚举) | `RELATION_LABEL[view.relation]`（`CHILD→子女` …）；未知 → `'亲属'`（`Relation` 联合类型需含该兜底） |
| `gender` | **无** | `''`（页面显式空态；**不造数**） |
| `age` | **无** | `0`（页面显式空态） |
| `status` | `status`(0-4) | `bindStatusOf(view.status)`（整数→字符串显式映射表） |
| `expiresOn` | `authEndAt`(nullable) | `null → 'LONG_TERM'`；否则 `YYYY-MM-DD` |
| `boundAt` | `authStartAt`(nullable) | `view.authStartAt ?? ''`（**不造数**；页面 "申请时间" 走空态） |
| `note` | `rejectReason`(nullable) | `view.rejectReason ?? ''` |

---

### 1.5 张力五裁决：D-4b 的 `elderKey` 收口 —— **按契约收口为 `elderId`；扫码承载 elderId；手机号搜索降级不支持；不新增解析端点**

| 子项 | 裁定 | 理由 |
|---|---|---|
| **申请入参收口** | 端侧申请提交 `FamilyBindingCreate`：`elderId`（字符串，来自扫码原文）+ `relation` + `verifyMethod` + `elderName` + `idCardNo`（核验，见 §1.1） | 契约权威；不新增端点 = 不破坏"9 操作已冻结"口径 |
| **扫码内容承载什么** | **平台约定的、含 `elderId` 的串**（格式 `YL-FAMILY:<elderId>`，`elderId` 为 19 位雪花数字串）；端侧**正则提取数字串**（`requestWithText` 同源的精度保护思路），**不解析 `elderNo` → `elderId`** | 无"标识→elderId"解析通道；`elderNo`→`elderId` 映射需新增端点（否决，见下） |
| **`family-bind.vue` 入口收敛** | **移除"手机号搜索" tab**（`via` 恒 `'qrcode'`）；仅保留扫码 + 关系/有效期 + 核验 + 提交。手机号入口以"敬请期待"占位提示（可配置） | §7.6 默认：手机号搜索是**存在性探测**（枚举风险），M2 不应开放 |
| **`maskedName` 与 `elderName` 关系** | 端侧 `maskedName` **不再本地由 `name[0]+'**'` 计算**（Mock 行为删除），改为**读后端 `FamilyBindingView.elderName`**（已脱敏）；申请阶段（未生效）后端**不下发** `elderName`（防枚举）→ `maskedName = ''` → 页面走"待确认老人"占位 | 端侧不自造脱敏口径；与"申请中不给任何老人数据"的既有越权防护一致 |
| **不新增解析端点的理由** | ① 不破坏"9 操作已冻结"；② 手机号搜索本质是**存在性探测**（枚举风险），M2 本就不应开放；③ `elderId` 由 QR 直接承载即可（含 `elderId` 的亲情码由**老人端/机构**生成，属 P2-8 范围） | 登记残留 **D4-R2**（"标识→elderId 解析端点"+ 手机号搜索，另开卡） |

---

### 1.6 其余裁决（本卡默认，不另行请示）

| # | 议题 | 裁决 |
|---|---|---|
| 1 | `consent_record` | **不写**（语义为"隐私政策告知同意" ER-08，锚 `subject_type+subject_id`，与亲属/监护授权**不同构**）；绑定留痕走 **`audit_log`**（4 动作，见 §10） |
| 2 | `proof_cos_key` | **只存键不落盘**（全仓无 COS 实现）；**D-4 内不写任何文件删除逻辑**（生命周期归 P2-7）；本地落盘若需要，复用 D-3 导出目录范式（由实现期定，须与 P2-7 口径一致） |
| 3 | 不新增 `@Scheduled` | `auth_end_at` 到期后 `status` **不流转**（ADR-0004：全仓唯一调度仍在 `RetentionChainScheduler`）；**安全性不依赖**（第三闸 `MY_ELDER_SUBQUERY` 已含 `auth_end_at IS NULL OR > NOW()` → fail-closed）；登记残留 **D4-R1** |
| 4 | ORG_ADMIN 是否可代解绑 | **默认允许**（线下核验 + 代操作口径；机构需处理"家属失联"场景）；由 `ORG_VIA_PARENT` 限制在**本机构老人**范围内 |
| 5 | dev 种子账号 | **补 `elder_dev`(ELDER) + `family_dev`(FAMILY)**，口令经环境变量 `DEV_LOGIN_PASSWORD` 注入（**2026-10-10 轮换**，原固定口令已泄露，见 `P2-8-R12`）；`elder_dev` 经 `POST /elders/{elderId}/user-binding` 或种子回填绑定到某 `elder.user_id` |
| 6 | 家属域模块归属 | **`yl-module-archive`**（D-2 已建 `elder` + `SensitiveFieldSupport` + 分页基建；`elder_family_bind` 是档案的关联关系） |
| 7 | `ACTIVE` 时全名下发 | 契约 `FamilyBindingView.elderName` **脱敏**（`x-sensitive-fields` 含 `elderName`）；本卡**不新增"生效后下发全名"通道**（端侧 `shownNameOf` 的"全名"退化为脱敏名 → 页面展示脱敏名，界面文案须同步，登记于端侧专章） |

### 1.7 ★C4 读路径脱敏依赖与**启动前置**（`elderName` 组装）

**事实**：`FamilyBindingView.elderName` = `SensitiveFieldSupport.maskName(support.decrypt(elder.nameEnc))`（§3.2），而 `SensitiveFieldSupport#decrypt` 在编解码器**未装配**时抛 `BizException(ARCHIVE_SENSITIVE_ENCRYPT_FAILED = 40001)`（`SensitiveFieldConfig` 为条件装配：`SENSITIVE_FIELD_KEY`/`SENSITIVE_FIELD_HASH_KEY` 双非空才注册）。

| 子项 | 裁定 | 依据 |
|---|---|---|
| **(a) 读路径不加 `isAvailable()` 守卫** | 沿用 `maskName(decrypt(...))`，未装配 → **`40001`**；**与 `ElderArchiveService#toView` 同口径**（该处亦无守卫；`isAvailable()` 仅用于 `getElders` 的**关键词搜索降级**） | **禁止静默降级为空串**：`elderName` 的 `null` 已被"未生效阶段不下发"占用（§3.2）——再让未装配也返 `null`，**调用方无法区分"待确认"与"配置缺失"**，等于让密钥事故静默通过。**宁可失败，不可假数据**（与 §1.1(c) 同源） |
| **(b) 一致性推论：这不是缺陷** | 无密钥时 `apply` 已直拒（§1.1c）、`POST /elders` 亦无法建档（D-2 口径）⇒ 库内**不可能**存在本卡产生的 `elder_family_bind` 行 | 与 D-2 同构；**读路径 `40001` 只会在"密钥被撤/轮换后"出现** |
| **(c) 启动前置（★ 必须写进 T08）** | 冒烟与 H5 E2E 的**起服务命令必须显式导出** `SENSITIVE_FIELD_KEY` / `SENSITIVE_FIELD_HASH_KEY`；**`scripts/smoke-report-review-chain.sh` 自身不导出** → 未带密钥启动时，凡触及 `elder`/`family/bindings` 的用例一律返 `40001`（**环境问题，非代码缺陷**） | D2/D3 的 e2e-checklist 与 QA 报告均为同一前置（先例）；`D4-frontend-migration.md` §5 已列该前置 |
| **(d) 残留** | 登记 **D4-R8**：生产密钥轮换 → 旧密文不可解 → 读路径 `40001`；须与 ADR-011 的 KMS 轮换周期 / 双密钥并行期同批设计 | 本卡不实现轮换（无 KMS 接入），只登记 |

**给 T08 的硬要求**：`D4-e2e-checklist.md` 的"启动前置"段必须**逐字给出**带两个密钥的完整起服务命令（含 `unset SERVER__PORT` / `REDIS_PASSWORD=''`），并写明「缺该前置 → `elder*` / `family*` 用例返 `40001`，属预期」。

### 1.8 ★ 实施期裁决（R-1 / R-2 / C5，2026-09-29）

> 三条均为**设计侧问题**（设计写错 / 设计未给机制），非实现缺陷。**零 DDL 不变**。**本节优先于 §1.2 / §3.1 / §3.2 / §3.3 / §5 的对应表述**（下方逐处已同步订正）。

| # | 问题 | 裁决 | 依据 |
|---|---|---|---|
| **R-1** | 设计 §1.2 / §2.2 / §3.1 要求 `user-binding` 写 `elder.user_id + auth_end_at`，但 **`elder` 表无 `auth_end_at` 列**（实读 `02_schema.sql`：`elder` 只有 `user_id`；「到期」语义在 `elder_family_bind.auth_end_at`） | **收窄契约**：`UserBindingRequest` **删除 `authEndAt`**（只留 `userId`）；服务端**不接收、不落库、不告警**。理由：① 零 DDL ⇒ 无承载列；② 「授权有效期」语义属 `elder_authorization`（`valid_from`/`valid_to`/`is_permanent`，ER-07），本卡明令不接（D4-R3）⇒ **无合法承载**；③ **接受但不落库 = 静默丢弃一个安全语义字段**（调用方以为有效期已生效、实际为永久）→ 违「宁可失败，不可假数据」 | `02_schema.sql` `elder` 列清单；`elder_authorization` DDL；§1.1(c) 同源原则 |
| **R-1b** | 同表发现：**`uk_elder_user`（`user_id` 唯一）⇒ 老人↔账号是 `1:1`**（一个账号只能绑一个老人） | 服务端**显式**校验并冲突返 `10005`（**绝不 500**）：① 目标 `elder.user_id` 非空且 ≠ 请求 `userId` → `10005`；② 该 `userId` 已绑其他 `elder` → `DuplicateKeyException` 兜底 `10005`。**`1:1` 是承重语义，须写入契约/设计说明** | `02_schema.sql` `UNIQUE KEY uk_elder_user (user_id)` |
| **R-2** | 设计 §3.3 称「`status=3/4` 后同对**新增行**，靠生成列 `active_uk` 允许」—— **错**：`active_uk = IF(deleted=0, 1, NULL)`，**只有 `deleted=1` 才让位**；旧行 `deleted=0` ⇒ 新增同对必撞 `uk_elder_family` ⇒ **解绑/拒绝后重新申请恒 `10005`（功能死路）** | **改为「复用活性行」**：`create` 先按 `(elder_id, family_user_id)` 查 `deleted=0` 活性行（**该查询在第三闸内**，scope=5 经 `family_user_id=me`）→ 不存在 → INSERT（`status` 按 `hasLoginAccount` 落 0/1）；存在且 `status ∈ {0,1,2}` → **`10005`**（重复申请 / 已有生效绑定）；存在且 `status ∈ {3,4}` → **`UPDATE` 复位**（`status`=0/1、`reject_reason`=`NULL`、`verify_method`/`proof_cos_key` 覆盖为新值）。**`unbind` 仍 `status=4` 且 `deleted` 保持 0**（§5.4 原判不变）。历史以 `audit_log` 为准 | `active_uk` 生成列定义 + 表注释；`uk_elder_family`；**零 DDL** |
| **R-2b** | `deleted` 语义边界（顺带钉死） | **`deleted=1` 只用于「软删」，不得用于表达业务状态**：`deleted` 会让该行对**所有** `deleted=0` 过滤的读路径消失（含机构的历史查阅）。业务状态一律由 `status` 表达（schema 明列 `4-已解绑/过期`） | 全库软删约定 + `elder_family_bind.status` 列注释 |
| **C5** | 设计 §1.1 / §10.8 要求「唯一绕过第三闸的读」，但**未给落地机制**；实施期实测：`FamilyBindingVerifyMapper` 无机制 ⇒ scope=5 申请时 `SELECT COUNT(*) FROM elder` 被第三闸改写为 `elder.id IN (… status=2 …)` → **申请前恒空 ⇒ 申请永远 `10004`** | **落地机制 = 接口级 `@InterceptorIgnore(dataPermission = "1")`**（`DataPermissionInterceptor#beforeQuery` 先问 `InterceptorIgnoreHelper.willIgnoreDataPermission(msId)`，命中即**不改写 SQL**）。**★ 这是全仓唯一的注解级第三闸豁免**（`grep InterceptorIgnore` 仅命中本 Mapper 与其测试）⇒ **新增豁免必须过同一审查**。三重补偿见 §1.1（最小化/同码/频控）+ **两条钉死单测**：(i) 反射断言注解真值（删即红）；(ii) **方法集合 ⊆ `{countExactMatch, hasLoginAccount}`**（防将来新增查询悄悄继承豁免） | 工程师 MP 3.5.5 字节码核验 + 本次 grep 复核；§1.1 |

**R-2 的可机械验收断言（补入 T04 单测）**：解绑后重新申请 → `code=0` 且 `status ∈ {0,1}`（**不是 `10005`**）；拒绝后重新申请 → 同上；`status=2`(ACTIVE) 时重复申请 → `10005`；`status ∈ {0,1}` 时重复申请 → `10005`。
**R-1b 的可机械验收断言（补入 T06 单测）**：把同一 `userId` 绑到第二个老人 → `10005`（**不是 500**）；把第二个 `userId` 绑到已绑定的老人 → `10005`。
**C5 的可机械验收断言**：`FamilyBindingVerifyMapperTest` 增 `mapperOnlyExposesScalarReadMethods`（方法集合 ⊆ 两方法）。

---

## 2. 实现方案与完整文件列表

### 2.1 文件清单

> 标记：**新**=全新文件｜**改**=修改既有文件。后端相对路径以 `<ROOT>` 为根；端侧单列（`<APP>`）。

**A) `yl-module-archive`（家属域主体）**

| # | 相对路径 | 状态 | 职责 |
|---|---|---|---|
| 1 | `yl-modules/yl-module-archive/src/main/java/cn/yl/modules/archive/controller/FamilyBindingController.java` | 新 | 家属端 9 端点（`/api/v1/family/bindings/**`） |
| 2 | `.../archive/service/FamilyBindingService.java` | 新 | 绑定状态机（申请/审核/拒绝/解绑）+ 核验 + 频控 + 留痕 |
| 3 | `.../archive/service/FamilyReportQueryService.java` | 新 | 家属读报告/详情/趋势/照护（经绑定 relationId 收口） |
| 4 | `.../archive/service/ElderUserBindingService.java` | 新 | `elder↔user` 绑定（§1.2） |
| 5 | `.../archive/domain/entity/ElderFamilyBind.java` | 新 | `elder_family_bind` 实体（**不映射 `active_uk`**） |
| 6 | `.../archive/mapper/ElderFamilyBindMapper.java` | 新 | `BaseMapper<ElderFamilyBind>` |
| 7 | `.../archive/mapper/FamilyBindingVerifyMapper.java` | 新 | ★ 只读投影（`@Select` 精确计数，**只返布尔**，§1.1） |
| 8 | `.../archive/mapper/FamilyReportSourceMapper.java` | 新 | 只读投影（`eval_report` + `report_dimension_score`） |
| 9 | `.../archive/mapper/FamilyCarePlanSourceMapper.java` | 新 | 只读投影（`care_plan`） |
| 10 | `.../archive/dto/FamilyBindingCreateRequest.java` | 新 | 申请入参 |
| 11 | `.../archive/dto/FamilyBindingView.java` | 新 | 绑定出参（脱敏） |
| 12 | `.../archive/dto/FamilyBindingRejectRequest.java` | 新 | 拒绝入参（`reason`） |
| 13 | `.../archive/dto/FamilyBindingQuery.java` | 新 | 列表分页入参 |
| 14 | `.../archive/dto/FamilyReportView.java` | 新 | 报告列表行 |
| 15 | `.../archive/dto/FamilyReportDetailView.java` | 新 | 报告详情（含维度得分 + 照护条目） |
| 16 | `.../archive/dto/FamilyTrendPointView.java` | 新 | 趋势点 |
| 17 | `.../archive/dto/FamilyCarePlanView.java` | 新 | 照护方案 |
| 18 | `.../archive/dto/UserBindingRequest.java` | 新 | `elder↔user` 入参 |
| 19 | `.../archive/dto/UserBindingView.java` | 新 | `elder↔user` 出参 |
| 20 | `.../archive/dto/ReportSourceRow.java` | 新 | `eval_report` 投影行 |
| 21 | `.../archive/dto/DimensionScoreRow.java` | 新 | `report_dimension_score` 投影行 |
| 22 | `.../archive/dto/CarePlanRow.java` | 新 | `care_plan` 投影行 |
| 23 | `.../archive/src/test/.../service/FamilyBindingServiceTest.java` | 新 | 状态机/核验/频控/未装配拒绝 |
| 24 | `.../archive/src/test/.../service/FamilyReportQueryServiceTest.java` | 新 | 读链路（relationId 收口/仅 PUBLISHED/同码 10004） |
| 25 | `.../archive/src/test/.../mapper/FamilyBindingVerifyMapperTest.java` | 新 | ★ 钉"只返布尔、不返字段"（QA 证伪项） |
| 26 | `.../archive/src/test/.../controller/FamilyBindingControllerTest.java` | 新 | 端点鉴权/参数 |
| 27 | `.../archive/src/test/.../service/ElderUserBindingServiceTest.java` | 新 | 唯一键 10005 / 机构 20003 |

**B) `yl-module-account` + `yl-api` + `yl-common` + `yl-infrastructure`（`authLogout` 黑名单）**

| # | 相对路径 | 状态 | 职责 |
|---|---|---|---|
| 28 | `yl-modules/yl-module-account/src/main/java/cn/yl/modules/account/controller/AuthController.java` | 改 | +`logout` handler（无 `@RequiresPermission`） |
| 29 | `.../account/service/AuthService.java` | 改 | +`logout(accessToken, refreshToken, userId)` |
| 30 | `yl-common/src/main/java/cn/yl/common/security/TokenBlacklistStore.java` | 新 | access 黑名单接口（同 `RefreshTokenStore` 范式） |
| 31 | `yl-infrastructure/src/main/java/cn/yl/infrastructure/token/RedisTokenBlacklistStore.java` | 新 | Redis 实现（键 `yl:bl:at:`） |
| 32 | `yl-api/src/main/java/cn/yl/api/security/JwtAuthFilter.java` | 改 | `parse` 后查黑名单，命中即 `writeUnauthorized`（§1.3） |
| 33 | `yl-api/src/main/java/cn/yl/api/security/JwtTokenProvider.java` | 改 | +`remainingTtl(String token): Duration`（解析 `exp`） |
| 34 | `yl-modules/yl-module-archive/src/main/java/cn/yl/modules/archive/controller/ElderController.java` | 改 | +`user-binding` handler（`@RequiresPermission(ACCOUNT_ORG_MANAGE)`） |

> ⚠️ **落点提示**：`ElderController` 在 **`yl-module-archive`**（D-2 落点，非 account 模块）；实现时**不得**在 account 模块新建 `ElderController`。

**C) `yl-module-account` security（数据域两锚，**零 DDL**）**

| # | 相对路径 | 状态 | 职责 |
|---|---|---|---|
| 35 | `yl-modules/yl-module-account/src/main/java/cn/yl/modules/account/security/DataScopePermissionHandler.java` | 改 | +`ORG_VIA_PARENT` / `SELF_VIA_PARENT`；`SELF_DENY_TABLES` 移除 `elder_family_bind`；Javadoc 补 D-4 段 |
| 36 | `.../account/src/test/.../security/DataScopePermissionHandlerTest.java` | 改 | **+2 先行失败单测**（§9 T01） |

**D) 契约 / 门禁 / 种子 / 冒烟**

| # | 相对路径 | 状态 | 改动 |
|---|---|---|---|
| 37 | `openapi/yl-api.yaml` | 改 | 10 planned→implemented；+1 新操作；3 处 `x-data-scope` 订正（§7） |
| 38 | `yl-bootstrap/src/test/.../contract/OpenApiContractTest.java` | 改 | `CONTROLLERS` +`FamilyBindingController` |
| 39 | `yl-bootstrap/src/test/.../security/ControllerAuthorizationCoverageTest.java` | 改 | `CONTROLLERS` +`FamilyBindingController`；白名单 **+第 6 条 `AuthController#logout`**；类注释理由扩为"会话入口/出口 + 健康探针 + 公开验真" |
| 40 | `docker/mysql/init/04_seed_dev_accounts.sql` | 改 | +`elder_dev`(ELDER) / `family_dev`(FAMILY) + 角色/成员/`elder.user_id` 回填 |
| 41 | `scripts/smoke-report-review-chain.sh` | 改 | 新增 `[9/9]` 家属域段（申请人/审核/报告/趋势/照护 + 越权 + `unbind` ④闸 + 无令牌 401/10002） |
| 42 | `docs/reviews/D4-e2e-checklist.md` | 新 | 验收清单（冒烟对照） |

**E) 端侧（`<APP>` = `C:/Users/Administrator/WorkBuddy/2026-09-08-13-00-34/stage3_开发`）**

| # | 相对路径 | 状态 | 改动 |
|---|---|---|---|
| 43 | `weixin-app/src/utils/family.ts` | 改 | 保留类型/文案/选项；删除本地到期判定；新增 `RELATION_LABEL` |
| 44 | `weixin-app/src/utils/real-api.ts` | 改 | 家属段从"委托 `family.ts`"→真实调用；新增 `bindStatusOf` + 降级映射 |
| 45 | `weixin-app/src/pages/family-bind/family-bind.vue` | 改 | 入口收敛（去手机号）+ 入参改 `elderId` + 核验字段 |
| 46 | `weixin-app/src/pages/family-binds/family-binds.vue` | 改 | 解绑真实化（②闸 + ④闸凭据） |
| 47 | `weixin-app/src/pages/family-home/family-home.vue` | 改 | 数据源改 `api.ts` |
| 48 | `weixin-app/src/pages/family-reports/family-reports.vue` | 改 | 同 47 |
| 49 | `weixin-app/src/pages/family-trend/family-trend.vue` | 改 | 同 47 |
| 50 | `weixin-app/src/pages/family-care/family-care.vue` | 改 | 同 47 |
| 51 | `douyin-app/src/utils/{family,real-api}.ts`、`ios-app/src/utils/{family,real-api}.ts` | 改 | **md5 同步自 weixin-app**（4 文件 × 2 端） |
| — | `weixin-app/src/utils/{api.ts,request.ts}` | 改/不改 | `api.ts` **不改**（仍 `export * from './real-api'`）；`request.ts` **不改**（已满足） |

> **无需改动**：`docker/mysql/init/02_schema.sql`（**零 DDL**）、`03_seed_rbac.sql`（零新码）、`scripts/verify-schema.sh`（**83 不变**）、`SecurityConfig.java`（`/auth/logout` 走 `anyRequest().authenticated()`，**须已登录** → 不 `permitAll`）、`weapp/`（不在改列）。

### 2.2 关键类职责与方法签名（供工程师直接落笔）

**`FamilyBindingController`**（archive，`@RequestMapping("/api/v1/family/bindings")`）

```java
@GetMapping                                          // familyBindingList
@RequiresPermission(PermissionCode.ACCOUNT_FAMILY_BIND)   // FAMILY + ORG_ADMIN 均持有
public R<PageResult<FamilyBindingView>> list(FamilyBindingQuery query);

@PostMapping                                         // familyBindingCreate
@RequiresPermission(PermissionCode.ACCOUNT_FAMILY_BIND)
@Idempotent(verifyBody = true)
@Audit(action = "FAMILY_BIND_APPLY", bizType = "ELDER_FAMILY_BIND")
public R<FamilyBindingView> create(@Valid @RequestBody FamilyBindingCreateRequest request);

@PostMapping("/{id}/approve")                        // familyBindingApprove（机构审核）
@RequiresPermission(PermissionCode.ACCOUNT_ORG_MANAGE)   // ★C1：ORG_ADMIN 独占（原 account:family:bind 被 FAMILY 共享 → 可自我放行，§1.2a）
@Idempotent(verifyBody = true)
@Audit(action = "FAMILY_BIND_APPROVE", bizType = "ELDER_FAMILY_BIND")
public R<FamilyBindingView> approve(@PathVariable("id") Long id);

@PostMapping("/{id}/reject")                         // familyBindingReject（老人侧拒绝）
@RequiresPermission(PermissionCode.ACCOUNT_FAMILY_BIND_REJECT)   // 仅 ELDER
@Idempotent(verifyBody = true)
@Audit(action = "FAMILY_BIND_REJECT", bizType = "ELDER_FAMILY_BIND")
public R<FamilyBindingView> reject(@PathVariable("id") Long id,
                                   @RequestBody(required = false) FamilyBindingRejectRequest req);

@PostMapping("/{id}/unbind")                         // familyBindingUnbind（敏感）
@RequiresPermission(value = {PermissionCode.ACCOUNT_FAMILY_BIND, PermissionCode.ACCOUNT_FAMILY_UNBIND},
                    logical = Logical.OR)            // ★ 先例：AssessmentController L183-184
@Idempotent(verifyBody = true)
@Audit(action = "FAMILY_BIND_UNBIND", bizType = "ELDER_FAMILY_BIND", sensitive = true)
public R<Void> unbind(@PathVariable("id") Long id);

@GetMapping("/{relationId}/reports")                 // familyReportList
@RequiresPermission(PermissionCode.REPORT_VIEW)
public R<PageResult<FamilyReportView>> reports(@PathVariable("relationId") Long relationId,
                                               FamilyBindingQuery query);

@GetMapping("/{relationId}/reports/{reportId}")      // familyReportDetail
@RequiresPermission(PermissionCode.REPORT_VIEW)
public R<FamilyReportDetailView> reportDetail(@PathVariable("relationId") Long relationId,
                                              @PathVariable("reportId") Long reportId);

@GetMapping("/{relationId}/trend")                   // familyTrend
@RequiresPermission(PermissionCode.REPORT_VIEW)
public R<List<FamilyTrendPointView>> trend(@PathVariable("relationId") Long relationId);

@GetMapping("/{relationId}/care-plan")               // familyCarePlan
@RequiresPermission(PermissionCode.REPORT_VIEW)
public R<FamilyCarePlanView> carePlan(@PathVariable("relationId") Long relationId);
```
- 构造器注入 `FamilyBindingService` + `FamilyReportQueryService`，`@RequiredArgsConstructor(onConstructor_ = @SuppressFBWarnings("EI_EXPOSE_REP2"))` + justification（沿用 `ElderController`/`AssessmentController` 范式）。
- **身份一律取登录态**（`SecurityUtils.requireUser()`）；请求体**不承载**身份字段。

**`FamilyBindingService`**

```java
@Transactional
FamilyBindingView apply(FamilyBindingCreateRequest req, LoginUser operator);   // 核验（§1.1）→ 落 status=0/1（判定规则见 §3.3）
FamilyBindingView approve(Long bindingId, LoginUser operator);                 // status ∈ {0,1} → 2（★C2 宽容化）；auth_start_at=now
FamilyBindingView reject(Long bindingId, String reason, LoginUser operator);   // status ∈ {0,1} → 3；reject_reason
void unbind(Long bindingId, LoginUser operator);                               // →4；deleted 语义见 §5
```
- `apply` 顺序：① 频控（`10429`）；② `support.isAvailable()` 否则 `40001`；③ 算 `nameHash`/`idCardHash` → `verifyMapper.countExactMatch(elderId, nameHash, idCardHash)`；④ `count==0` → `NOT_FOUND(10004)`（同码防枚举）；⑤ **`verifyMapper.hasLoginAccount(elderId)`** 判老人账号态 → 定 `status`（有账号 → `0` 待老人确认；无账号 → `1` 直接进机构审核，**§3.3 判定规则**）；⑥ 查重（同 `elder_id`+`family_user_id`+`deleted=0` 已存在 → `10005`）；⑦ `insert(ElderFamilyBind{ status=⑤ 的结果, familyUserId=operator.userId(), ... })`。
- `approve` 状态机：**`{0,1} → 2`**（机构审核对「待老人确认」与「待机构审核」均可放行；老人不注册/未表态时机构**直接**放行，与 D-1 裁决 5「默认通过 + 可拒绝」一致）。**★C2**：若只认 `1→2`，则 `create` 落 `0` 的行**永无出口**（老人侧只有 `:reject`、本卡不新增 `@Scheduled`）→ 审核端点永远拿不到可审行。
- `unbind`：**服务层双保险**——先 `selectById(bindingId)`（第三闸 → 越权 `family_user_id`/机构 → 查不到 → `10004`），再置 `status=4`；`deleted` **保持 0**（**不物理删、也不软删**——业务状态由 `status` 表达，`deleted` 只表「软删」，见 §1.8 R-2b；历史留痕走 `audit_log`）。**★R-2 订正**：原文"`active_uk` 生成列允许同对新增"**不成立**（`active_uk = IF(deleted=0,1,NULL)`，仅 `deleted=1` 让位）⇒ 同对重新申请改为**复用本行**（`UPDATE` 复位），不新增行。

**`FamilyReportQueryService`**

```java
PageResult<FamilyReportView> reports(Long relationId, FamilyBindingQuery query);   // 仅 PUBLISHED(status=1)
FamilyReportDetailView detail(Long relationId, Long reportId);                     // report.elder_id == binding.elder_id
List<FamilyTrendPointView> trend(Long relationId);                                 // published_at 升序
FamilyCarePlanView carePlan(Long relationId);                                      // care_plan.status=1
private ElderFamilyBind requireMyActiveBinding(Long relationId);                   // 查不到 → 10004
```
- `requireMyActiveBinding`：`bindMapper.selectById(relationId)`（第三闸按角色收口：FAMILY → `family_user_id=me`；ORG_ADMIN → 经 `elder.org_id`）；查不到 **或** `status != 2` **或** 已过期 → `NOT_FOUND(10004)`（**同码**，不泄露存在性）。

**`ElderController#userBinding`**（archive，新增 handler）

```java
@PostMapping("/{elderId}/user-binding")
@RequiresPermission(PermissionCode.ACCOUNT_ORG_MANAGE)
@Idempotent(verifyBody = true)
@Audit(action = "ELDER_USER_BIND", bizType = "ELDER")
public R<UserBindingView> bindUser(@PathVariable("elderId") Long elderId,
                                   @Valid @RequestBody UserBindingRequest request);
```
- `ElderUserBindingService.bind(elderId, userId, operator)`（**★R-1：签名去掉 `authEndAt`**）：① 校验 `sys_user ∈ myOrgs`（否则 `20003`）；② `selectById(elderId)`（第三闸 → 越界 `10004`）；③ 若 `elder.userId != null && != userId` → `10005`；④ **只** `updateById` 写 `user_id`（**不写 `auth_end_at`**——`elder` 无该列）；⑤ `DuplicateKeyException`（`uk_elder_user`，`1:1` 约束）→ `10005`（**绝不 500**）。

### 2.3 跨域只读投影 Mapper 的**精确列清单**（防 `care_plan.org_id` 那类列名错误）

> 列名全部取自 `docker/mysql/init/02_schema.sql`（唯一事实源），**逐列照抄，禁表别名臆造**。**禁 `import` 兄弟模块实体类**（只发 SQL）。

**`FamilyBindingVerifyMapper`（§1.1）**

```sql
-- 只返回计数（服务层仅判 > 0）；绝不 SELECT 任何老人字段
SELECT COUNT(*)
FROM   elder
WHERE  id = #{elderId}
  AND  name_hash = #{nameHash}
  AND  id_card_hash = #{idCardHash}
  AND  deleted = 0
```

**`FamilyReportSourceMapper#selectPublishedPage`（分页；`Page<ReportSourceRow>` 参数）**

```sql
SELECT  r.id           AS report_id,
        r.report_no    AS report_no,
        r.elder_id     AS elder_id,
        r.org_id       AS org_id,
        r.grade_code   AS grade_code,
        r.total_score  AS total_score,
        r.published_at AS published_at
FROM    eval_report r
WHERE   r.deleted = 0
  AND   r.status  = 1
  AND   r.elder_id = #{elderId}
ORDER BY r.published_at DESC
```

**`FamilyReportSourceMapper#selectReportById`（详情头）**

```sql
SELECT  r.id, r.report_no, r.order_id, r.elder_id, r.org_id,
        r.grade_code, r.total_score, r.rule_version_id,
        r.preliminary_grade_code, r.preliminary_level_name,
        r.final_level_code, r.final_level_name, r.level_change_reason,
        r.published_at, r.status
FROM    eval_report r
WHERE   r.id = #{reportId}
  AND   r.deleted = 0
```

**`FamilyReportSourceMapper#selectDimensionScores`（`report_dimension_score` 无 `deleted` 列）**

```sql
SELECT  s.dimension_code, s.dimension_name, s.raw_score, s.max_score, s.refuse_count
FROM    report_dimension_score s
WHERE   s.report_id = #{reportId}
ORDER BY s.dimension_code
```

**`FamilyCarePlanSourceMapper#selectConfirmedPlan`**

```sql
SELECT  p.id, p.report_id, p.elder_id, p.grade_code, p.items_json,
        p.confirmed_by, p.confirmed_at, p.status
FROM    care_plan p
WHERE   p.elder_id = #{elderId}
  AND   p.deleted = 0
  AND   p.status = 1
ORDER BY p.confirmed_at DESC
LIMIT   1
```

> ⚠️ **列名/取值断言**：`eval_report.status` 为 `TINYINT`（**1=有效**，`02_schema.sql` L491）；`care_plan.status` 为 `TINYINT`（1=已确认，L541）；`eval_report.grade_code` ∈ `GRADE_INTACT/MILD/MODERATE/SEVERE/TOTAL`（**带 `GRADE_` 前缀**）；`total_score` 为 `DECIMAL(6,2)`（视图出参用 `Double` 或 `BigDecimal`）。
> 参与 JOIN 的 `eval_report`/`report_dimension_score`/`care_plan` **均在第三闸登记**（`ORG_COLUMN`/`RELATION_ELDER_COLUMN`/`RELATION_VIA_PARENT`）→ 拦截器自动追加行级条件（scope=5 下经 `elder_family_bind` 收口）。

---

## 3. 实体与 DTO

### 3.1 实体 `ElderFamilyBind`（对应 `elder_family_bind`）

| 属性 | 列 | 类型 | 备注 |
|---|---|---|---|
| `id` | `id` | Long | `@TableId(type = IdType.ASSIGN_ID)`（ADR-0003，无 AUTO_INCREMENT） |
| `elderId` | `elder_id` | Long | 直锚 |
| `familyUserId` | `family_user_id` | Long | 家属账号（scope=5 本表锚） |
| `relation` | `relation` | String | `CHILD/SPOUSE/KIN/GUARDIAN` |
| `verifyMethod` | `verify_method` | String | `SCAN/PHONE/FACE`（**NOT NULL**，无默认 → 创建必填） |
| `proofCosKey` | `proof_cos_key` | String | 只存键（不落盘） |
| `authStartAt` / `authEndAt` | 同名 | LocalDateTime | `auth_end_at` 是第三闸子查询判据 |
| `status` | `status` | Integer | 0-待确认 1-待机构审核 2-生效 3-已拒绝 4-已解绑/过期 |
| `rejectReason` | `reject_reason` | String | 拒绝原因 |
| `deleted` | `deleted` | Integer | **有该列，正常映射**；查询显式 `deleted=0` |
| ~~`activeUk`~~ | ~~`active_uk`~~ | — | **★ 生成列，实体侧不得映射**（否则 insert 报错，同 `care_plan`/`export_task` 坑） |
| `createdAt` / `updatedAt` | 同名 | LocalDateTime | 依赖 DB 默认值（实体不填充） |

**`Elder`（D-2 已有）**：本卡**不加字段**（`userId` 已存在），仅复用；`ElderUserBindingService` 直接 `updateById` **只写 `user_id`**（**★R-1：`elder` 表无 `auth_end_at` 列，不得写**）。另注：`uk_elder_user` 唯一 ⇒ **老人↔账号 `1:1`**（§1.8 R-1b）。

### 3.2 DTO

| DTO | 模块 | 字段 |
|---|---|---|
| `FamilyBindingCreateRequest` | archive | `elderId:@NotNull`, `relation:@NotBlank`, `verifyMethod:@NotBlank`, `proofCosKey?`, `elderName:@NotBlank`, `idCardNo:@NotBlank`（后两者**服务端要求必填**，见 §10#3） |
| `FamilyBindingView` | archive | `id, elderId, elderName(脱敏), relation, status, authStartAt, authEndAt, rejectReason` |
| `FamilyBindingRejectRequest` | archive | `reason` |
| `FamilyBindingQuery` | archive | `pageNum, pageSize` |
| `FamilyReportView` | archive | `reportId, reportNo, elderId, gradeCode, totalScore, publishedAt` |
| `FamilyReportDetailView` | archive | `reportId, reportNo, elderId, gradeCode, levelName, totalScore, publishedAt, dimensionScores:List<DimensionScoreRow>, careItems:List<String>` |
| `FamilyTrendPointView` | archive | `reportId, publishedAt, gradeCode, levelName, totalScore` |
| `FamilyCarePlanView` | archive | `planId, reportId, elderId, gradeCode, confirmedAt, items:List<String>` |
| `UserBindingRequest` | archive | `userId:@NotNull`（**★R-1：已删 `authEndAt`**） |
| `UserBindingView` | archive | `elderId, userId, boundAt` |
| `ReportSourceRow` | archive | `reportId, reportNo, elderId, orgId, gradeCode, totalScore, publishedAt, orderId?` |
| `DimensionScoreRow` | archive | `dimensionCode, dimensionName, rawScore, maxScore, refuseCount` |
| `CarePlanRow` | archive | `id, reportId, elderId, gradeCode, itemsJson, confirmedBy, confirmedAt, status` |

> **脱敏口径**：`FamilyBindingView.elderName` = `SensitiveFieldSupport.maskName(decrypt(elder.nameEnc))`（`张*`）；**申请阶段（未生效）不下发**（`null`）。
> **`items_json` → `items:List<String>`**：`care_plan.items_json` 为 JSON（`02_schema.sql` L537）；服务层用 Jackson 解析为字符串列表（空/异常 → 空列表，**不造数**）。

### 3.3 ★C2 绑定状态机（五态）转移表 —— **每个出口都须有承载端点**

**五态**（`elder_family_bind.status`）：`0`-待老人确认 / `1`-待机构审核 / `2`-生效 / `3`-已拒绝 / `4`-已解绑或过期。

| 当前态 | 触发动作 | 目标态 | **承载端点** | 本期有承载者？ | 备注 |
|---|---|---|---|---|---|
| —（无行） | 家属/机构发起申请 | **`0` 或 `1`** | `POST /api/v1/family/bindings`（`create`） | ✅ | 落 `0`/`1` 由**判定规则**决定（见下）；契约 L1042 已声明 `status=0/1` |
| `0` 待老人确认 | **机构审核放行** | `2` 生效 | `POST /{id}/approve` | ✅ | ★C2 宽容化：老人不注册/未表态时机构**直接**放行（D-1 裁决 5「默认通过」） |
| `0` 待老人确认 | 老人拒绝 | `3` 已拒绝 | `POST /{id}/reject` | ✅ | 权限码 `account:family:bind:reject`（仅 ELDER）；写 `reject_reason` |
| `1` 待机构审核 | **机构审核放行** | `2` 生效 | `POST /{id}/approve` | ✅ | 主路径 |
| `1` 待机构审核 | 老人拒绝 | `3` 已拒绝 | `POST /{id}/reject` | ✅ | 老人可主动否决待审行 |
| `2` 生效 | 解绑 | `4` 已解绑 | `POST /{id}/unbind` | ✅ | ★④闸（`OR` 含敏感码 → 强制二次验证）；`deleted` 保持 `0` |
| `2` 生效 | 到期（`auth_end_at < NOW()`） | `4` | **无端点** | ❌ **本期无承载者** | 不新增 `@Scheduled`（ADR-0004）；安全性由第三闸子查询 fail-closed 保证 → 登记 **D4-R1** |
| `0` 待老人确认 | **老人确认** | `1` 待机构审核 | **无端点** | ❌ **本期无此转移** | D-1 裁决 5：老人侧**只有 `:reject`、无 `:confirm`** → **本转移在 M2 不存在**（正因如此 `approve` 必须认 `0→2`） |
| `3` 已拒绝 | 重新申请 | `0`/`1` | `POST /api/v1/family/bindings` | ✅ | **★R-2 订正：复用既有活性行**（非新增行）：`UPDATE` 复位 `status`=0/1、清 `reject_reason`、覆盖 `verify_method`/`proof_cos_key`。原"新增行靠 `active_uk` 允许"**不成立**（仅 `deleted=1` 让位） |
| `4` 已解绑/过期 | 重新申请 | `0`/`1` | `POST /api/v1/family/bindings` | ✅ | 同上（**复用活性行**）。历史以 `audit_log` 为准 |

**★ `create` 落 `0` 还是 `1` 的判定规则（唯一约定）**

```
elderHasAccount = verifyMapper.hasLoginAccount(elderId)   // SELECT EXISTS(... elder.user_id IS NOT NULL)
status = elderHasAccount ? 0 /* 待老人确认 */ : 1 /* 待机构审核 */
```

| 情形 | 落态 | 理由 |
|---|---|---|
| 老人**已注册**（`elder.user_id` 非空） | `0` 待老人确认 | 老人可主动 `reject`；不表态则由机构 `approve` 兜底（`0→2`） |
| 老人**未注册**（`elder.user_id` 为空） | `1` 待机构审核 | 老人无端侧入口、无法表态 → **不进 `0`**，避免死路；直接由机构审核 |

> **设计不变式**：`status=0` 与 `status=1` **均有出口**（`approve` 认两者、`reject` 认两者）⇒ **不存在"无出口"的落态**。这正是修复 ★C2（`0` 无出口）的核心。

**可机械验收断言（写入 T04 单测）**
1. `FamilyBindingServiceTest#applyLandsPendingElderWhenElderHasAccount`：mock `hasLoginAccount` → `true` ⇒ 捕获 `insert` 的 `ElderFamilyBind.status == 0`。
2. `FamilyBindingServiceTest#applyLandsPendingOrgWhenElderHasNoAccount`：mock `hasLoginAccount` → `false` ⇒ `status == 1`。
3. `FamilyBindingServiceTest#approveAcceptsPendingElder`：构造 `status=0` 行 ⇒ `approve` 后 `status==2`（★C2 回归：若只认 `1→2`，该断言必红）。
4. `FamilyBindingServiceTest#approveAcceptsPendingOrg`：`status=1` 行 ⇒ `approve` 后 `status==2`。

---

## 4. 端点契约表（11 个操作）

权限码来源：`03_seed_rbac.sql` 的 `sys_role_permission`（**权威事实**）。

| # | Method / Path | operationId | 归属模块 | 权限码（id → 持有角色） | 数据域 mode | 二次验证 | 幂等 | 错误码（复用现有 `ErrorCode`） | 留痕 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `GET /api/v1/family/bindings` | `familyBindingList` | archive | `account:family:bind`（2013 → FAMILY 1003 / ORG_ADMIN 1004） | relation（FAMILY）/ org（ORG_ADMIN） | 否 | 否 | `10001` | 无 |
| 2 | `POST /api/v1/family/bindings` | `familyBindingCreate` | archive | `account:family:bind`（同上） | relation | 否 | **`Idempotency-Key` 必填** | `10001`；`10004`（核验不匹配/不存在，**同码**）；`10005`（重复申请）；`10429`（频控）；**`40001`（编解码器未装配）** | **`audit_log(FAMILY_BIND_APPLY)`** |
| 3 | `POST /api/v1/family/bindings/{id}/approve` | `familyBindingApprove` | archive | **`account:org:manage`**（2009 → **ORG_ADMIN 独占**；FAMILY **无此码** → 第二闸即挡 **`20003`**，★C1 修复，§1.2a） | org | 否 | **`Idempotency-Key` 必填** | `10001`；**`20003`（无权限码）**；`10004`（越界/不存在/状态非法）；`10005` | **`audit_log(FAMILY_BIND_APPROVE)`** |
| 4 | `POST /api/v1/family/bindings/{id}/reject` | `familyBindingReject` | archive | `account:family:bind:reject`（2014 → 仅 ELDER 1001） | **self（via elder）** ★订正 | 否 | **`Idempotency-Key` 必填** | `10001`；`10004`（越界/非本人老人） | **`audit_log(FAMILY_BIND_REJECT)`** |
| 5 | `POST /api/v1/family/bindings/{id}/unbind` | `familyBindingUnbind` | archive | **`account:family:bind` OR `account:family:unbind`**（2013/2016） | relation / org | **是**（`X-Second-Verify-Token`，**OR 含敏感码强制**） | **`Idempotency-Key` 必填** | `10001`；`10004`；**`10008`（缺二次验证）** | **`audit_log(FAMILY_BIND_UNBIND, sensitive=1)`** |
| 6 | `GET /api/v1/family/bindings/{relationId}/reports` | `familyReportList` | archive | `report:view`（2001 → 五角色） | relation | 否 | 否 | `10004`（relationId 非本人/未生效） | 无 |
| 7 | `GET /api/v1/family/bindings/{relationId}/reports/{reportId}` | `familyReportDetail` | archive | `report:view` | relation | 否 | 否 | `10004`（relationId 或 reportId 越界/不存在，**同码**） | 无 |
| 8 | `GET /api/v1/family/bindings/{relationId}/trend` | `familyTrend` | archive | `report:view` | relation | 否 | 否 | `10004` | 无 |
| 9 | `GET /api/v1/family/bindings/{relationId}/care-plan` | `familyCarePlan` | archive | `report:view` | relation | 否 | 否 | `10004`（无已确认方案时返**空方案** `code=0`） | 无 |
| 10 | `POST /api/v1/auth/logout` | `authLogout` | account | **none**（会话出口） | self | 否 | 否 | `10002`（未登录） | 无（`login_log` 由既有链路；本卡不新增） |
| 11 | `POST /api/v1/elders/{elderId}/user-binding` | `elderUserBinding` | archive | `account:org:manage`（2009 → ORG_ADMIN 1004） | **org** | 否 | **`Idempotency-Key` 必填** | `10004`（越界/不存在）；`10005`（`uk_elder_user` 冲突，**不 500**）；`20003`（目标账号非本机构） | **`audit_log(ELDER_USER_BIND)`** |

**数据域落点说明（11 端点 × 角色）**

| 端点 | ORG_ADMIN(2) | ELDER(1) | FAMILY(5) | SUPERVISOR(4) | ASSESSOR(2) |
|---|---|---|---|---|---|
| `familyBindingList` | `elder_family_bind.elder_id IN (SELECT p.id FROM elder p WHERE p.org_id IN (myOrgs))`（★F3 修复） | 无权限码 | `elder_family_bind.family_user_id = me` | 无权限码 | 无权限码 |
| `familyBindingCreate` | 可申请（scope=2 核验经**独立 Mapper**） | 无权限码 | 可申请 | 无权限码 | 无权限码 |
| `familyBindingApprove` | 同 list 的机构收口（★F3 修复） | 无权限码 | **无权限码**（第二闸 `account:org:manage` 即挡 → `20003`，★C1） | 无权限码（仅 2010 `:read`） | 无权限码 |
| `familyBindingReject` | 无权限码 | `elder_family_bind.elder_id IN (SELECT p.id FROM elder p WHERE p.user_id = me)`（★F1 修复） | 无权限码 | 无权限码 | 无权限码 |
| `familyBindingUnbind` | 机构收口；④闸 | 无权限码 | `family_user_id = me`；④闸 | 无权限码 | 无权限码 |
| `familyReport{List,Detail}` / `familyTrend` / `familyCarePlan` | 机构收口（`eval_report.org_id IN (…)`） | 无权限码 | 关系级（`eval_report.elder_id IN (my bound)`） | 无权限码 | 无权限码 |
| `authLogout` | 任意登录态（无数据域读） | 同左 | 同左 | 同左 | 同左 |
| `elderUserBinding` | `elder.org_id IN (myOrgs)` | 无权限码 | 无权限码 | 无权限码 | 无权限码 |

**错误码复用声明（无新增）**：全部命中现有 `ErrorCode`（`10001/10002/10004/10005/10007/10008/10429/20003/40001`），**不新增任何号段**。

**幂等**：`@Idempotent(verifyBody=true)`（`IdempotentAspect` 按 `Idempotency-Key` + 请求体摘要去重；缺失键 → `10007`）。

---

## 5. 程序调用流程（时序图）

> 完整时序图见 `docs/design/D4-sequence-diagram.mermaid`，含 **7 条链**。断言口径：业务失败看 `R.code`（恒 HTTP 200）；未认证额外看 **HTTP 401 + 10002**；缺二次验证 `10008`。

1. **5.1 申请绑定（含 ★F2 核验）**：`POST /family/bindings` → 第二闸（`account:family:bind`）→ 幂等 → `FamilyBindingService.apply`：频控 → `SensitiveFieldSupport.isAvailable()`（false → `40001`）→ `hashForSearch(elderName/idCardNo)` → `FamilyBindingVerifyMapper.countExactMatch`（**只返布尔**；`==0` → `10004` 同码）→ **`hasLoginAccount` 定落态（`0`/`1`，§3.3）** → 查重（`10005`）→ `insert(status=0/1)` → `@Audit(FAMILY_BIND_APPLY)`。
2. **5.2 机构审核**：`POST /family/bindings/{id}/approve` → 第二闸（**`account:org:manage`，ORG_ADMIN 独占；FAMILY 无此码 → `20003`**，★C1）→ 幂等 → `selectById`（第三闸经 `ORG_VIA_PARENT` 收口 → 跨机构查不到 → `10004`）→ 状态机 **`{0,1} → 2`**（★C2 宽容化）、`auth_start_at=now` → `@Audit(FAMILY_BIND_APPROVE)`。
3. **5.3 老人拒绝**：`POST /family/bindings/{id}/reject` → 第二闸（`account:family:bind:reject`，仅 ELDER）→ 幂等 → `selectById`（第三闸经 `SELF_VIA_PARENT` → `elder.user_id = me`；他人老人 → `10004`）→ `status→3`、`reject_reason` → `@Audit(FAMILY_BIND_REJECT)`。
4. **5.4 解绑（④闸）**：先 `POST /auth/second-verify` 换一次性凭据 → `POST /family/bindings/{id}/unbind`（`X-Second-Verify-Token`）→ 第二闸（**OR** + 含 `account:family:unbind` → **强制第四闸**；缺凭据 → `10008`）→ 幂等 → `selectById`（数据域双保险）→ `status→4` → `@Audit(FAMILY_BIND_UNBIND, sensitive=1)`。
5. **5.5 家属读报告/趋势/照护**：`GET /family/bindings/{relationId}/reports`（及 detail/trend/care-plan）→ 第二闸（`report:view`）→ `requireMyActiveBinding(relationId)`（第三闸 → 非本人/未生效 → `10004`）→ `FamilyReportSourceMapper`/`FamilyCarePlanSourceMapper`（第三闸经 `eval_report`/`care_plan` 的 `elder_id IN (my bound)` 收口）→ 服务层仅 `status=1`（PUBLISHED/已确认）→ 返回。
6. **5.6 `elder↔user` 绑定**：`POST /elders/{elderId}/user-binding` → 第二闸（`account:org:manage`）→ 幂等 → `ElderUserBindingService.bind`：校验目标账号 ∈ 本机构（否则 `20003`）→ `selectById(elderId)`（第三闸 → 越界 `10004`）→ `user_id` 占用判（`10005`）→ `update`（`uk_elder_user` 冲突 → `10005`）→ `@Audit(ELDER_USER_BIND)`。
7. **5.7 logout 黑名单 + 拒绝链路**：`POST /auth/logout`（Bearer A + `{refreshToken:R}`）→ `JwtAuthFilter` 装配 → `AuthService.logout`：`blacklistStore.add(sha256(A), userId, remaining(A))` + `refreshTokenStore.revoke(R)` → `200/code=0`；随后 **旧 A → 401+10002**（`JwtAuthFilter` 命中黑名单短路）、**旧 R → `refresh` 返 `10002`**；未认证链路 → **401+10002**；第二闸无权 → **200+20003**。

---

## 6. 数据域逐行 diff（★ 零 DDL）

### 6.1 ★ 本卡 **DDL 零变更**（显式声明 + 论证）

> **`docker/mysql/init/02_schema.sql` 本卡零改动；`verify-schema.sh` 保持 83；保留字金丝雀保持 1。**

**为什么不需要 DDL**（复述并强化 `D4-foundation-check.md` §4.4）：

| 维度 | 补 `org_id` 列（D-3 对 `export_task` 的做法） | 反查锚（本卡采纳） |
|---|---|---|
| `org` 与行的关系 | `export_task` 的机构是**真属性**（任务归属机构，不可由他表导出） | `elder_family_bind` 的机构**是 `elder.org_id` 的函数依赖**（无独立语义） |
| 冗余列风险 | 无（本就要存） | **有**：老人转机构后绑定行的 `org_id` 会**陈旧** → 两个真源 |
| DDL 成本 | +1 列 + 回填 + 索引 + schema 门禁 82→83 | **0**（门禁保持 **83**） |
| 一致性 | — | 永远与 `elder.org_id` 一致（**无同步逻辑**） |

⇒ **同一判据（"该字段是不是本表的真属性"）在 D-3 得出"补列"、在 D-4 得出"反查"，不是不一致，而是判据一致**。**禁止"为对称而对称"地补列**。

### 6.2 `DataScopePermissionHandler.java` 逐行 diff

**① 新增常量 `ORG_VIA_PARENT`（scope=2 机构级反查锚）**

```diff
+ /**
+  * 机构级（scope=2）**反查锚表** → 本表锚列 + 父表（父表须在 {@link #ORG_COLUMN}，用以把 org_id 收口）。
+  *
+  * <p>D-4：{@code elder_family_bind} 无 {@code org_id} 列（机构归属是 {@code elder.org_id} 的函数依赖），
+  * 原先未登记 → scope=2 下「未登记 → 放行」= 机构管理员跨机构可读/可审（★F3）。零 DDL，经父表收口。
+  */
+ private static final Map<String, RelationAnchor> ORG_VIA_PARENT =
+         Map.ofEntries(Map.entry("elder_family_bind", new RelationAnchor("elder_id", "elder")));
```

**② 新增常量 `SELF_VIA_PARENT`（scope=1 本人级反查锚）**

```diff
+ /**
+  * 本人级（scope=1）**反查锚表** → 本表锚列 + 父表（父表须在 {@link #SELF_COLUMN}，用以把 user_id 收口）。
+  *
+  * <p>D-4：ELDER(scope=1) 的拒绝端点须命中「本人老人」的绑定行，而 {@code elder_family_bind} 无本人列
+  * → 恒假（★F1 功能死路）。零 DDL，经 {@code elder.user_id} 收口。
+  */
+ private static final Map<String, RelationAnchor> SELF_VIA_PARENT =
+         Map.ofEntries(Map.entry("elder_family_bind", new RelationAnchor("elder_id", "elder")));
```

**③ `SELF_DENY_TABLES` 移除 `elder_family_bind`（**迁移**，非删除能力）**

```diff
  private static final Set<String> SELF_DENY_TABLES =
          Set.of(
                  "eval_report",
                  "report_dimension_score",
                  "eval_answer",
                  "eval_evidence",
                  "consent_record",
                  "elder_authorization",
-                 "elder_family_bind",
                  "org_bed",
                  "care_task",
                  "supervise_report");
```
> **结论**：`elder_family_bind` **从 `SELF_DENY_TABLES` 移除**（迁往 `SELF_VIA_PARENT`），与 D-2「`elder` 从 deny 迁往 `SELF_COLUMN`」同构。`elder_authorization` / `consent_record` **保持 deny**（无本人列，`(1=0)` 不变）。

**④ `getSqlSegment` 两处分支插入反查锚判定（scope=2 与 scope=1）**

```diff
      if (scope == DataScope.ORG.code()) {
          String column = ORG_COLUMN.get(tableName);
-         return column == null ? null : orgCondition(table, column, user.orgIds());
+         if (column != null) {
+             return orgCondition(table, column, user.orgIds());
+         }
+         RelationAnchor viaOrg = ORG_VIA_PARENT.get(tableName);
+         return viaOrg == null
+                 ? null
+                 : orgViaParentCondition(table, viaOrg, user.orgIds());
      }
```
```diff
      String column = SELF_COLUMN.get(tableName);
      if (column != null) {
          return selfCondition(table, column, user.userId());
      }
+     RelationAnchor viaSelf = SELF_VIA_PARENT.get(tableName);
+     if (viaSelf != null) {
+         return selfViaParentCondition(table, viaSelf, user.userId());
+     }
      return SELF_DENY_TABLES.contains(tableName) ? denyAll() : null;
```
- `relationSegment`（scope=5）**不动**：`elder_family_bind` 在 scope=5 仍走 `selfCondition(table, "family_user_id", familyUserId)`（**不变**）。

**⑤ 新增两个私有构造方法（复用 `subSelect` 拼装，禁字符串拼接整段 SQL）**

```java
/** scope=2：{@code table.锚列 IN (SELECT p.id FROM 父表 p WHERE p.org_id IN (orgIds))}；orgIds 空 → 恒假。 */
private Expression orgViaParentCondition(Table table, RelationAnchor via, List<Long> orgIds) {
    if (orgIds == null || orgIds.isEmpty()) {
        return denyAll();
    }
    String inner = "SELECT p.id FROM " + via.parentTable() + " p WHERE p.org_id IN ("
            + joinLongs(orgIds) + ")";
    InExpression in = new InExpression();
    in.setLeftExpression(new Column(table, via.column()));
    in.setRightExpression(subSelect(inner));
    return new Parenthesis(in);
}

/** scope=1：{@code table.锚列 IN (SELECT p.id FROM 父表 p WHERE p.user_id = 我)}。 */
private Expression selfViaParentCondition(Table table, RelationAnchor via, long userId) {
    String inner = "SELECT p.id FROM " + via.parentTable() + " p WHERE p.user_id = " + userId;
    InExpression in = new InExpression();
    in.setLeftExpression(new Column(table, via.column()));
    in.setRightExpression(subSelect(inner));
    return new Parenthesis(in);
}
```
> `joinLongs(List<Long>)` 为新增小工具（逗号拼接，或复用 `orgCondition` 的循环思路）。**类 Javadoc** 追加一段 D-4 说明（`ORG_VIA_PARENT`/`SELF_VIA_PARENT` + 为何零 DDL）。

### 6.3 渲染结果（**SQL 片段级，机械可验收**）

| scope | 表 | 渲染 |
|---|---|---|
| 2 | `elder_family_bind` | `(elder_family_bind.elder_id IN (SELECT p.id FROM elder p WHERE p.org_id IN (1)))` |
| 1 | `elder_family_bind` | `(elder_family_bind.elder_id IN (SELECT p.id FROM elder p WHERE p.user_id = 900000000000000003))` |
| 5 | `elder_family_bind` | `(elder_family_bind.family_user_id = me)`（**不变**） |
| 1 | `elder_authorization` / `consent_record` | `(1 = 0)`（**不变**） |
| 1 | `eval_report` / `report_dimension_score` / `eval_answer` | `(1 = 0)`（**不变**，`SELF_DENY_TABLES`）→ **★C3：老人读不到自己的评估报告**（残留 **D4-R7**，§6.6） |

### 6.4 `verify-schema.sh` / 保留字金丝雀

- **`verify-schema.sh`：零改动**（`EXPECTED_TOTAL_ASSERTIONS` 保持 **83**）。本卡不新增列/表/索引 → 无断言可增。
- **保留字金丝雀**：保持 **1**（`elder_family_bind` 的列均为既有列，未新增列名）。

### 6.5 种子影响

- **`03_seed_rbac.sql`：零改动**（11 端点全部命中现有授权；无新权限码/授权；`sys_permission` 仍 22、`sys_role_permission` 仍 40）。
- **`04_seed_dev_accounts.sql`：改**（+`elder_dev`/`family_dev`，见 §7）。

### 6.6 ★C3 ELDER(scope=1) 数据域可达边界（D3-R6 覆盖声明）

> **目的**：D3-R6（`ELDER(1)` / `FAMILY(5)` 真实 HTTP E2E）归 D-4。**显式写清 ELDER 的可达边界**，避免 QA 把「既有残留」当 D-4 缺陷、或让 D3-R6 的「三角色已验」变成假绿。

| 类别 | 端点 / 表 | ELDER(scope=1) 是否可达 | 说明 |
|---|---|---|---|
| **可达（D-4 修好 ★F1 之后）** | `POST /api/v1/family/bindings/{id}/reject` | ✅ 可达 | 第三闸 `SELF_VIA_PARENT` → `elder_family_bind.elder_id IN (SELECT p.id FROM elder p WHERE p.user_id = me)` |
| **可达** | `GET /elders/{elderId}`、`GET /elders`（自读） | ✅ 可达 | D-2 的 `elder.user_id` 锚（`SELF_COLUMN` → `(elder.user_id = me)`） |
| **不可达（既有残留，**非** D-4 缺陷）** | `eval_report` / `report_dimension_score` / `eval_answer` 在 `scope=1` 下 | ❌ **恒假**（`SELF_DENY_TABLES` → `(1 = 0)`） | ⇒ **老人读不到自己的评估报告**。其根因与「D-2 之前 `elder` 也读不到」同构：**缺一条"评估-报告 → 老人本人"的锚**（`eval_report.elder_id IN (SELECT p.id FROM elder p WHERE p.user_id = me)`）。**不在 D-4 修复范围**（D-4 只修 `elder_family_bind` 的两锚，未触及评估域） |

**结论：保持残留（推荐）→ 登记 D4-R7。** 理由：① 修它需给 `eval_report` 新增 `SELF_VIA_PARENT` 锚 —— 这会让**老人端获得"读自己评估报告"的能力**，属**新功能**（需 PRD 授权 + 老人端页面承载），**超出 D-4「家属端 + 端侧切真」的卡范围**；② 一旦落锚，`report_dimension_score`/`eval_answer`（经 `eval_report` 反查）会**连带可达**，**爆炸半径 = 评估域全部读端点对 ELDER 开放**，须独立验收（含"老人能否读**他人**报告"的反向越权断言）→ 应独立开卡（**D-5 / P2**）。

**⇒ D3-R6 验收口径（写入 T08 冒烟 / `D4-e2e-checklist.md`）**：ELDER 的 E2E **只验** `reject` + `GET /elders` 自读；**明确标注「ELDER E2E 不覆盖报告读取」**（该能力本期恒假、属 D4-R7）。**不得**把"老人读到自己的报告"写进 D-4 的通过判据。

> 若某日决定纳入本卡，锚方案与爆炸半径见上表末行；但**需先更新 PRD 与老人端页面范围**，故本轮**不纳入**。

---

## 7. 契约变更清单（v1.1.2 → v1.1.3）

| 变更 | 内容 |
|---|---|
| **版本** | `info.version: 1.1.2 → 1.1.3` |
| **翻旗（10 个 planned → implemented）** | `authLogout`(L389) · `familyBindingList` · `familyBindingCreate` · `familyBindingApprove` · `familyBindingReject` · `familyBindingUnbind` · `familyReportList` · `familyReportDetail` · `familyTrend` · `familyCarePlan`（L990-1197 九个） |
| **新增 1 个操作** | `POST /api/v1/elders/{elderId}/user-binding`（`operationId: elderUserBinding`；`x-required-permission: account:org:manage`；`x-data-scope: { mode: org, by-role: { ORG_ADMIN: org } }`；`x-status: implemented`；`parameters: [IdempotencyKey]`；`requestBody: UserBindingRequest`；`responses: R<UserBindingView>`） |
| **★ 3 处 `x-data-scope` 订正**（本体订正，同 `ElderCreate.required` 先例） | ① `familyBindingReject`：`{ mode: relation, via: elder_family_bind, extra: … }` → **`{ mode: self, via: elder, condition: "elder_family_bind.elder_id IN (SELECT id FROM elder WHERE elder.user_id = 登录用户)" }`**；② `familyBindingApprove`：`{ mode: org, by-role: { ORG_ADMIN: org }, extra: … }` → **补 `condition: "elder_family_bind.elder_id IN (SELECT id FROM elder WHERE elder.org_id IN (登录机构))"`**；③ `familyBindingList`：补同 ② 的 `condition`（ORG_ADMIN 视角） |
| **★C1 `x-required-permission` 本体订正** | `familyBindingApprove`（`openapi/yl-api.yaml` L1057）：`account:family:bind` → **`account:org:manage`**（2009，ORG_ADMIN 独占）—— 挡 FAMILY 自我放行越权（§1.2a）；`x-data-scope` 仍按上表订正②（**权限码与数据域各修各的闸**） |
| **新增 schema** | `UserBindingRequest: { userId: int64(required) }`（**★R-1：已删 `authEndAt`**）；`UserBindingView: { elderId: int64, userId: int64, boundAt: date-time }` |
| **`FamilyBindingCreate` 说明补注** | 补 `elderName`/`idCardNo` 的"核验必填（服务端要求）"说明（`required` 数组**不改**，服务端更严，登记于 §10#3） |
| **两处硬编码 `CONTROLLERS`** | `OpenApiContractTest` +`FamilyBindingController.class`；`ControllerAuthorizationCoverageTest` +`FamilyBindingController.class` |
| **覆盖守卫白名单** | 5 条 → **6 条**（+`AuthController#logout`）；类注释理由由"三类"扩为"**会话入口/出口** + 健康探针 + 公开验真" |
| **`FamilyBindingView` 说明补注** | 明确 `authStartAt`/`authEndAt`/`rejectReason` 的 nullable 语义（已声明，仅补"未生效阶段 `elderName` 不下发"说明） |

**契约终态核对**：操作总数 **44**；`implemented 44 / planned 0`；双向漂移 **0**。

---

## 8. 门禁影响清单

> ⚠️ **"翻旗后不同步会立刻变红"的位置已加粗。**

| # | 门禁 / 测试 | 受影响 | 必须同步的动作 |
|---|---|---|---|
| 1 | **`OpenApiContractTest.declaredImplementedOperationsMatchControllersExactly`** | **是（红）** | 翻旗 11 + 新增控制器 `FamilyBindingController` **登记进 `CONTROLLERS`**；缺一即双向漂移 |
| 2 | `OpenApiContractTest.{everyOperationDeclaresPermissionAndDataScope, dataScopeModesAreInEnum, publicModeImpliesNonePermission, everyDeclaredPermissionCodeExistsInConstants, sensitiveCodesRequireSecondVerify}` | 软 | 11 操作本身合规；`familyBindingUnbind` 携敏感码 `x-second-verify: true`（**已声明**）；`authLogout` `mode: self`+`none`（**合规**：唯一约束是 `public ⟹ none`，反向不成立） |
| 3 | **`scripts/verify-openapi.sh`［5/5］漂移** | **是（红）** | 与 #1 同源；脚本 `find '*Controller.java'` **自动发现** → 无硬编码需改 |
| 4 | **`ControllerAuthorizationCoverageTest.everyHandlerEndpointDeclaresPermission`** | **是（红）** | `FamilyBindingController` 登记进 `CONTROLLERS`；**9 个 handler 全部挂 `@RequiresPermission`**；`AuthController#logout`/`ElderController#userBinding` 依下表 |
| 5 | **`ControllerAuthorizationCoverageTest.whitelistHasNoRedundantEntry`** | **是（红）** | 白名单 **+`AuthController#logout`**；该 handler **不得**挂 `@RequiresPermission` |
| 6 | **`DataScopePermissionHandlerTest`** | 是（增量） | +2 先行失败单测（scope=1/2 经 `elder` 收口），见 T01 |
| 7 | `scripts/verify-schema.sh`（83） | **否** | **零 DDL**；断言总数与金丝雀均不变 |
| 8 | `ReservedWordColumnMappingTest` | 否 | 未新增列名 |
| 9 | `JwtAuthFilterTest`（若存在） | 软 | 注入 mock `TokenBlacklistStore`（默认 `false`）→ 未命中路径行为不变 |
| 10 | `scripts/smoke-report-review-chain.sh` | 是（增量） | 新增 `[9/9]` 段（**无固定总数，勿硬编码**）；★**运行前置：服务须带 `SENSITIVE_FIELD_KEY` / `SENSITIVE_FIELD_HASH_KEY` 启动**（脚本自身不导出，§1.7c） |
| 11 | `mvn -o verify`（Checkstyle/Spotless/SpotBugs） | 软 | 禁 `System.out`/`java.util.Date`/星号导入/Tab/行尾空格；圈复杂度 ≤15；控制器/服务注入用 `@RequiredArgsConstructor(onConstructor_=@SuppressFBWarnings("EI_EXPOSE_REP2"))` **且带 justification**；格式 `mvn -o -q spotless:apply` |
| 12 | 进门禁前 | — | 停运行实例（防 fat jar 锁，D-2 坑④） |

**门禁期望值（**数字只许向上**）**

| 门禁 | D-3 基线 | D-4 目标 |
|---|---|---|
| `mvn -o verify` 单测 | 185 / 0 | **≥185**（预计 +40~55：11 端点 + 3 数据域锚 + 核验 Mapper + 频控/黑名单 + 端侧无） |
| `verify-schema.sh` | 83 | **83**（**零 DDL**；金丝雀仍 = 1） |
| `verify-openapi.sh` | 8/8 · 33/10 | **8/8 · implemented 44 / planned 0 · 漂移 0** |
| 冒烟 | 79 / 0 | **≥79**（新增 `[9/9]` 段） |
| `Checkstyle` / `SpotBugs` | 0 / 0 | **0 / 0** |

### 8.3 QA 突变清单（在 D-3 的 5 条基础上**换靶**，共 6 条）

| # | 突变 | 期望变红 |
|---|---|---|
| **M1** | 摘 `ORG_VIA_PARENT` 的 `elder_family_bind` 登记 | `DataScopePermissionHandlerTest.orgScopeAnchorsElderFamilyBindViaElderOrgId` |
| **M2** | 摘 `SELF_VIA_PARENT` 的 `elder_family_bind` 登记 | `DataScopePermissionHandlerTest.selfScopeAnchorsElderFamilyBindViaElderUserId`（★F1 回归） |
| **M3** | 摘 `familyBindingApprove` 的 `@RequiresPermission` | `ControllerAuthorizationCoverageTest`（报"裸奔"） |
| **M4** | 摘 `OpenApiContractTest.CONTROLLERS` 里的 `FamilyBindingController` | 双向漂移断言（契约多登） |
| **M5** | 把 `unbind` 的 `logical = OR` 改回默认 `ALL` | **真实 HTTP 必红**（FAMILY 无 `UNBIND` 码 → 谁都进不去，`R.code=20003`） |
| **M6** | 无令牌访问家属端点 | **HTTP 401 + `code=10002`**；反向证伪：**FAMILY 打 `POST /family/bindings/{id}/approve` → HTTP 200 + `R.code=20003`**（★C1：权限码 `account:org:manage`，FAMILY 不持有 → **第二闸即挡**）；**附加断言**：FAMILY 打 `POST /elders/{elderId}/user-binding` → **200 + 20003** |

> **M6 注（★C1 修订）**：`approve` 的权限码已**本体订正**为 `account:org:manage`（§1.2a）⇒ FAMILY **在第二闸即被挡** → **HTTP 200 + `R.code=20003`**（`ROLE_NOT_PERMITTED`）。该断言**直接钉住 C1 的修复**：若有人把 `approve` 的权限码改回 `account:family:bind`，FAMILY 便能过第二闸（且数据域经 `family_user_id=me` 可达）→ 该断言**立刻变红**（**比原 `user-binding` 断言更强**——`user-binding` 从不被 FAMILY 持有，与 `approve` 的修复无强绑定）。**原 `user-binding` 断言保留为附加断言**（同码 `20003`，双重保险）。

> 沿用 D-3 两条**抗中断纪律**：**增量落盘** + **突变即时还原**。

---

## 9. 有序任务列表（供工程师直接执行）

> **硬约束**：**严格串行**（同一 Maven 工程禁并行改，防门禁互踩）；每任务 ≥3 文件；T01 为数据域基线 + **先行失败单测**。
> **说明（与默认 ≤5 任务的差异）**：D-4 范围（11 端点 + 5 处跨模块改造 + 端侧切真）显著大于 D-2/D-3，且团队负责人明确要求**严格串行、先行失败单测**的 9 段节拍，故按 T01–T09 拆分；每段均为**可独立提交单元**。
> 关键路径：**T01 → T02 → T03 → T04 → T05 → T06 → T07 → T08 → T09**。

### T01（P0）数据域两锚 + **先行失败单测**（★F1/★F3 修复）
- **Source Files**：
  - `yl-modules/yl-module-account/src/test/.../security/DataScopePermissionHandlerTest.java`（**改：先写 2 条红单测**）
  - `yl-modules/yl-module-account/src/main/java/cn/yl/modules/account/security/DataScopePermissionHandler.java`（改：`ORG_VIA_PARENT`/`SELF_VIA_PARENT` + 两分支 + 两构造方法 + `SELF_DENY_TABLES` 移除 `elder_family_bind` + Javadoc）
- **要点**：**必须先写测试、先跑红、再改处理器转绿**。
  - `selfScopeAnchorsElderFamilyBindViaElderUserId`：`login(UID, orgIds, 1)` → `(elder_family_bind.elder_id IN (SELECT p.id FROM elder p WHERE p.user_id = UID))`；
  - `orgScopeAnchorsElderFamilyBindViaElderOrgId`：`login(UID, List.of(1L), 2)` → `(elder_family_bind.elder_id IN (SELECT p.id FROM elder p WHERE p.org_id IN (1)))`。
  - 两条在改处理器前**必须红**（现状：scope=1 → `(1=0)`；scope=2 → `null`）。
- **Dependencies**：无
- **作为独立提交单元**：✅（门禁不翻旗，仅数据域 + 单测）
- **Priority**：P0

### T02（P0）契约冻结 + 两处硬编码清单预置（**红点收敛起点**）
- **Source Files**：
  - `openapi/yl-api.yaml`（改：10 planned→implemented；+1 新操作；3 处 `x-data-scope` 订正；**+1 处 `x-required-permission` 本体订正（`approve` → `account:org:manage`，★C1）**；新增 `UserBinding*` schema）
  - `yl-bootstrap/src/test/.../contract/OpenApiContractTest.java`（改：`CONTROLLERS` +`FamilyBindingController`）
  - `yl-bootstrap/src/test/.../security/ControllerAuthorizationCoverageTest.java`（改：`CONTROLLERS` +`FamilyBindingController`；白名单 +`AuthController#logout`；类注释扩为"会话入口/出口 + 健康探针 + 公开验真"）
- **要点**：翻旗后**立刻跑一次** `bash scripts/verify-openapi.sh` + `mvn -o -q -pl yl-bootstrap test`，**让已知红点（漂移双向）+ 编译错误（引用尚不存在的控制器类）一次性亮出来**，确认红得"对"（因控制器未建），随后由 T03~T06 逐个收敛至全绿。
- **Dependencies**：T01
- **Priority**：P0

### T03（P0）实体 / Mapper / DTO + **★F2 核验 Mapper**
- **Source Files**：`ElderFamilyBind.java`、`ElderFamilyBindMapper.java`、`FamilyBindingVerifyMapper.java`、`FamilyReportSourceMapper.java`、`FamilyCarePlanSourceMapper.java`、`FamilyBindingCreateRequest.java`、`FamilyBindingView.java`、`FamilyBindingRejectRequest.java`、`FamilyBindingQuery.java`、`ReportSourceRow.java`、`DimensionScoreRow.java`、`CarePlanRow.java`、`FamilyReportView.java`、`FamilyReportDetailView.java`、`FamilyTrendPointView.java`、`FamilyCarePlanView.java`、`UserBindingRequest.java`、`UserBindingView.java` + `FamilyBindingVerifyMapperTest.java`
- **要点**：实体**不映射 `active_uk`**、映射 `deleted`；`FamilyBindingVerifyMapper` **两个方法只返标量**（`countExactMatch(int)` + **`hasLoginAccount(Long):boolean`**，单测反射扫描钉死 ⊆`{int,boolean,long}`，§1.1）；投影 Mapper **逐列照 §2.3**（禁表别名臆造）；`care_plan`/`report_dimension_score` 已登记第三闸。
- **Dependencies**：T02
- **Priority**：P0

### T04（P0）绑定状态机五端点（`list`/`create`/`approve`/`reject`/`unbind`）
- **Source Files**：`FamilyBindingService.java`、`FamilyBindingController.java`、`test/.../service/FamilyBindingServiceTest.java`、`test/.../controller/FamilyBindingControllerTest.java`
- **要点**：**`approve` 权限码 = `ACCOUNT_ORG_MANAGE`（★C1，不是 `ACCOUNT_FAMILY_BIND`）**；`create` 核验三连（频控 → 未装配 40001 → hash 匹配 `10004` 同码）+ **`hasLoginAccount` 定落态（`0`/`1`，§3.3 判定规则）**；**状态机 `approve` 认 `{0,1}→2`、`reject` 认 `{0,1}→3`（★C2，按 §3.3 五态转移表逐行落笔）**；`unbind` **OR 语义 + 强制第四闸**（照抄 `AssessmentController` L183-184）；`@Audit` 四动作；`reject` 挂 `ACCOUNT_FAMILY_BIND_REJECT`；数据域双保险。
- **★C1/C2 断言**：`FamilyBindingControllerTest#approveRequiresOrgManage`（反射读注解值 == `{ACCOUNT_ORG_MANAGE}`）；`approveAcceptsPendingElder` / `approveAcceptsPendingOrg` / `applyLandsPendingElderWhenElderHasAccount` / `applyLandsPendingOrgWhenElderHasNoAccount`（§3.3）。
- **Dependencies**：T03
- **Priority**：P0

### T05（P0）家属读三端点 + 照护
- **Source Files**：`FamilyReportQueryService.java`、`FamilyBindingController.java`（同 T04 追加 4 个读 handler）、`test/.../service/FamilyReportQueryServiceTest.java`
- **要点**：`requireMyActiveBinding(relationId)` → 非本人/未生效/过期 → `10004` 同码；仅 `status=1`（PUBLISHED / 已确认）；`report.elder_id == binding.elder_id`；`items_json` → 字符串列表（空态**不造数**）。
- **Dependencies**：T04
- **Priority**：P0

### T06（P0）`elder↔user` 绑定 + `authLogout`
- **Source Files**：`ElderUserBindingService.java`、`ElderController.java`（archive，+`user-binding` handler）、`test/.../service/ElderUserBindingServiceTest.java`、`TokenBlacklistStore.java`（common）、`RedisTokenBlacklistStore.java`（infrastructure）、`JwtAuthFilter.java`、`JwtTokenProvider.java`（+`remainingTtl`）、`AuthController.java`、`AuthService.java`（+`logout`）
- **要点**：`user-binding` 三道显式校验（`20003`/`10005`/`10004`）；`uk_elder_user` 冲突 → `10005`（**不 500**）；logout 黑名单键 `yl:bl:at:<sha256>` + TTL=剩余 + refresh `revoke`；`JwtAuthFilter` `parse` 后短路。
- **Dependencies**：T05
- **Priority**：P0

### T07（P1）dev 种子账号
- **Source Files**：`docker/mysql/init/04_seed_dev_accounts.sql`（改）
- **要点**：+`elder_dev`（id `…003`，角色 ELDER/1001，`sys_org_member` role 1001）+ `family_dev`（id `…004`，角色 FAMILY/1003，role 1003）；口令经环境变量 `DEV_LOGIN_PASSWORD` 注入（**2026-10-10 轮换**，各账号散列互不相同）；`elder_dev` 绑定到某 `elder.user_id`（种子 UPDATE 或经 `user-binding` 端点）。
- **Dependencies**：T06（`user-binding` 端点已存在，用于 E2E 绑定）
- **Priority**：P1

### T08（P1）冒烟 `[9/9]` 段 + 验收清单
- **Source Files**：`scripts/smoke-report-review-chain.sh`（改）、`docs/reviews/D4-e2e-checklist.md`（新）
- **要点（冒烟断言，下沉 `R.code`）**：申请（FAMILY）→ `code=0` 且 `status∈{0,1}`；`approve`（ORG_ADMIN）→ `code=0` 且 `status=2`；`reports`/`trend`/`care-plan`（FAMILY）→ `code=0`；**越权（他人 `relationId` → `10004`；FAMILY 打 `approve` → `20003`（★C1）；FAMILY 打 `user-binding` → `20003`）**；`unbind` 缺凭据 → `10008`、带凭据 → `code=0`；**ELDER 段（★C3 覆盖声明）**：`reject` → `code=0`、`GET /elders` 自读 → `code=0`，**并注明「ELDER E2E 不覆盖报告读取（D4-R7 恒假）」**；**无令牌 → HTTP 401 + `code=10002`**；logout 六步断言（§1.3）。
- **Dependencies**：T07
- **Priority**：P1
- **★C4 前置（硬要求，§1.7c）**：`D4-e2e-checklist.md` 必须首段给出**逐字**的起服务命令（含 `unset SERVER__PORT`、`REDIS_PASSWORD=''`、`SENSITIVE_FIELD_KEY`/`SENSITIVE_FIELD_HASH_KEY` 两密钥），并写明「缺该前置 → `elder*`/`family*` 用例返 `40001`，属预期而非缺陷」。冒烟脚本**自身不导出**这两把密钥（D2/D3 同）。

### T09（P1）端侧切真（weixin-app → 三端 md5 同步）+ H5 E2E
- **Source Files**：`<APP>/weixin-app/src/utils/{family.ts,real-api.ts}`、`<APP>/weixin-app/src/pages/family-{bind,binds,home,reports,trend,care}/*.vue`、`<APP>/{douyin-app,ios-app}/src/utils/{family.ts,real-api.ts}`
- **要点**：`real-api.ts` 家属段切真实（§1.4 降级映射 + `bindStatusOf`）；`family-bind.vue` 入口收敛；`family-binds.vue` 解绑真实化（②闸 + ④闸）；**`requestWithText()` 用于 `relationId`/`reportId`**（雪花精度）；**保留"返回前经 `relationId` 校验"**。**验收命令**：见 `D4-frontend-migration.md` §5（`md5sum` 三端逐一比对）。
- **Dependencies**：T08（后端全链绿）
- **Priority**：P1

---

## 10. 共享知识（跨文件约定）

1. **模块与包根**：家属域 `cn.yl.modules.archive`（`controller/service/mapper/dto/domain/entity/support`，对齐 evaluation）；`authLogout` 落 `cn.yl.modules.account`；access 黑名单接口 `cn.yl.common.security`、实现 `cn.yl.infrastructure.token`。控制器 `XxxController`，服务 `XxxService`，实体无后缀，入参 `XxxRequest`/`XxxQuery`，出参 `XxxView`。
2. **身份来源**：一律 `SecurityUtils.requireUser()`/`requireUserId()` 取登录态；**请求体不得承载身份字段**（`familyUserId` 由服务端取登录态）。这是 CR-M2-002 铁律。
3. **状态码 ↔ `BindStatus` 映射表（唯一约定，勿各写一份）**：`0→PENDING_ELDER`、`1→PENDING_ORG`、`2→ACTIVE`、`3→REJECTED`、`4→EXPIRED`；未知值 → `EXPIRED`（最保守，不给数据可见性）。**整数 ↔ 字符串显式映射，不得靠字符串猜测**。**状态迁移以 §3.3 五态转移表为唯一口径**：`approve` 认 **`{0,1}→2`**、`reject` 认 **`{0,1}→3`**、`unbind` 认 `2→4`；`create` 落 `0`/`1` **按 `hasLoginAccount(elderId)` 分派**（有账号→`0`；无账号→`1`）。**任何"无承载端点"的转移（`2→4` 到期、老人确认 `0/1→1`）本期不存在，不得在实现里臆造。**
4. **`audit_log` 动作名**：`FAMILY_BIND_APPLY`（申请）/ `FAMILY_BIND_APPROVE`（审核通过）/ `FAMILY_BIND_REJECT`（拒绝）/ `FAMILY_BIND_UNBIND`（解绑，`sensitive=1`）/ `ELDER_USER_BIND`（老人↔账号绑定）；`biz_type` = `ELDER_FAMILY_BIND` / `ELDER`。
5. **错误码选用**（全部复用现有 `ErrorCode`，**零新增**）：`10001` 参数；`10002` 未登录；`10004` 越界/不存在（**同码，不泄露存在性**）；`10005` 唯一键冲突；`10007` 缺幂等键；`10008` 缺二次验证；`10429` 频控；`20003` 角色/机构越权；`40001` 敏感编解码器未装配。
6. **统一错误语义**：业务失败 = **HTTP 200 + `R.code`**；未认证 = **HTTP 401 + `code=10002`**；**不得用 HTTP 状态码表达业务失败**。
7. **数据域（第三闸）**：读操作的机构/关系收口由 `DataScopePermissionHandler` **自动**完成，服务层**不再手写** `org_id`/`elder_id` 过滤；写操作的机构合法性由**服务/控制器显式校验**。**新增表必须显式登记或显式论证为字典表**。★**「权限已授 ≠ 数据意图」**：当权限码被多角色**共享**而语义只属其中一个时，**数据域修不掉越权**（★C1：`approve` 的 `account:family:bind` 被 FAMILY 共享，且 `elder_family_bind` 在 scope=5 下经 `family_user_id=me` 可达 → 改数据域无效，**只能改权限码**）。机构专属写操作一律挂 **ORG_ADMIN 独占码**（`account:org:manage`）。
8. **唯一绕过第三闸的读**：`FamilyBindingVerifyMapper`（§1.1）—— **只返布尔 + 同码防枚举 + 频控**，且须写在类 Javadoc 与 QA 证伪项里。★**C5 落地机制（实施期补，§1.8）**：接口级 **`@InterceptorIgnore(dataPermission = "1")`** —— **全仓唯一注解级第三闸豁免**，新增豁免须过同一审查；必须同时满足「方法集合 ⊆ `{countExactMatch, hasLoginAccount}`」与反射断言注解真值两条钉死单测。
9. **主键 / 时间 / 软删**：`@TableId(type = IdType.ASSIGN_ID)`；手写 SQL 显式赋 `id`（表无 AUTO_INCREMENT）；时间列 `DATETIME`；`created_at/updated_at` 依赖 DB 默认值；`elder_family_bind` **有 `deleted`**（查询显式 `deleted=0`、**不映射生成列 `active_uk`**）。
10. **雪花 id 与端侧**：`relationId` = `elder_family_bind.id`（19 位）→ 端侧凡以 `relationId`/`reportId` 作 path 参数处**必须走 `requestWithText()` 取原文**；**`requestWithText()` 的使用边界 = 仅"读取服务端返回的长整型 id"**（发送时以字符串放请求体即可，勿用它发请求）。
11. **端侧适配层的显式降级清单**：见 §1.4 表（`name`/`gender`/`age`/`boundAt`/`note` **缺失即空态，绝不造数**；`expiresOn` 由 `authEndAt` 派生，**本地到期判定逻辑删除**）。
12. **不新增 `@Scheduled`**：`auth_end_at` 到期不流转 `status`（ADR-0004）；安全性由第三闸子查询 fail-closed 保证。
13. **门禁自检**：提交前 `mvn -o verify` + `bash scripts/verify-openapi.sh` + `bash scripts/verify-schema.sh` 全绿；格式 `mvn -o -q spotless:apply`；进门前先停运行实例。
14. **★C4 敏感字段装配口径**：读路径组装 `elderName` **不加 `isAvailable()` 守卫**（未装配 → `40001`，与 `ElderArchiveService#toView` 同口径，**禁静默降级**）；`isAvailable()` 仅用于**可降级的检索分支**（如 `getElders` 关键词 `name_hash`）。**冒烟/E2E 起服务必须导出 `SENSITIVE_FIELD_KEY`/`SENSITIVE_FIELD_HASH_KEY`**（脚本不自带，§1.7）。

---

## 11. 待明确事项（按阻塞度排序，≤7 条）

| # | 事项 | 阻塞度 | 建议处置 |
|---|---|---|---|
| 1 | **`authLogout` 请求体是否必带 `refreshToken`**（契约无 `requestBody`） | **中** | 设计取「**可选**」：带则同时 `revoke`（双令牌失效）；不带则仅 access 入黑名单（refresh 仍可用至自然过期）。建议**契约补 `requestBody: { refreshToken: string }`（非 required）**；登记为契约待订正项 |
| 2 | **`elder_family_bind` 是否有生产写入方校验 `verify_method` 白名单** | 中 | 服务端校验 `verifyMethod ∈ {SCAN, PHONE, FACE}`（否则 `10001`）；本期仅 `SCAN` 有真实链路（`PHONE`/`FACE` 属 P2-8 核身通道） |
| 3 | **`FamilyBindingCreate` 的 `elderName`/`idCardNo` 在契约里是 optional，但服务端要求必填（核验前置）** | **中（契约与实现张力）** | 服务端**更严不违规**（同 `ElderCreate.orgId` 先例）；**建议契约把二者加入 `required`** 并注明"核验必填"。**本轮不改 `required`**（登记为契约待订正，避免翻旗批次夹带语义变更） |
| 4 | **`familyCarePlan` 无已确认方案时的返回** | 低 | 返回**空方案**（`items=[]`，`code=0`），**不返 10004**（"尚未生成"非越权）；前端走空态 |
| 5 | **`elder.authorization` 未接线（D4-R3）** | 低 | 本期只写 `elder.user_id`；`elder_authorization` 的"第三方范围化授权"另开卡 |
| 6 | **详情"全名"通道** | 低 | 契约仅 `elderName`（脱敏）；本期**界面展示脱敏名**，文案同步（端侧专章）；若需"生效后全名"，另开契约项 |
| 7 | **手机号搜索老人（D4-R2）** | 低 | 端侧隐藏入口（保留"敬请期待"占位）；解析端点 + 手机号搜索另开卡 |

**残留登记（供实施记录回填）**

| # | 残留 | 说明 |
|---|---|---|
| **D4-R1** | `elder_family_bind.auth_end_at` 到期后 `status` 不流转 | ADR-0004；安全性由第三闸子查询 fail-closed 保证；清理/流转排 P2-7/D-5 |
| **D4-R2** | 端侧手机号搜索老人 + "标识→elderId"解析端点未实现 | §1.5；端侧隐藏该入口 |
| **D4-R3** | `elder_authorization`（第三方范围化授权）未接线 | §1.2；本期只做 `elder.user_id` |
| **D4-R4** | `proof_cos_key` 只存键不落盘；证明文件生命周期清理未实现 | §1.6#2；排 P2-7（与 `export_task.expire_at` 同卡） |
| **D4-R5** | 短信核身通道（`verifyMethod=PHONE` / 老人自主认领）未实现 | `D4-foundation-check.md` §6.1；立独立卡 P2-8（含合规评审） |
| **D4-R6** | 绑定未落 `consent_record`（有意） | §1.6#1；留痕走 `audit_log` |
| **D4-R7** | **ELDER(scope=1) 读不到自己的评估报告**（`eval_report`/`report_dimension_score`/`eval_answer` 在 scope=1 下恒假） | ★C3，§6.6；**既有残留（非 D-4 缺陷）**：缺"评估-报告→老人本人"锚（`eval_report.elder_id IN (SELECT p.id FROM elder p WHERE p.user_id=me)`）。**D-4 保持残留**（修它=给老人开新读能力，超卡范围；且经 `eval_report` 反查会连带开放 `report_dimension_score`/`eval_answer`，爆炸半径=评估域全部读端点对 ELDER 开放）。**D3-R6 口径：ELDER E2E 不覆盖报告读取**。另开卡 D-5/P2 |
| **D4-R8** | **敏感字段密钥相关残留**：① dev 无密钥 → 核验/读路径均 `40001`（§1.1c、§1.7）；② 生产**密钥轮换** → 旧密文不可解 → 读路径 `40001` | ① 属预期环境口径（T08 checklist 写死启动前置）；② 本卡无 KMS 接入、不实现双密钥并行期，须与 **ADR-011** 密钥轮换周期同批设计 |
| **D4-R9** | **老人↔账号绑定无「有效期」语义**（★R-1：`elder` 无 `auth_end_at` 列，契约 `authEndAt` 已删，§1.8） | 绑定为**长期有效**，撤销靠显式解绑（`account:family:unbind` + ④闸）。若需「范围化/限期授权」，走 `elder_authorization`（`valid_from`/`valid_to`/`is_permanent`，ER-07）——**另开卡**（与 D4-R3 同批） |
| **D4-R10** | **未知字段被静默忽略**（QA `O-1`，P2）：请求体仍带已删的 `authEndAt` → Jackson 忽略、返 `code=0` | **保持残留**：本工程未开 `FAIL_ON_UNKNOWN_PROPERTIES`；若契约加 `additionalProperties: false` 而实现仍返 200，会造成**契约≠实现**（违"契约 = 实现镜像"）。改为在契约 `description` **显式声明**「字段已移除；客户端仍发送将被忽略」。**端侧（T09）不得发送该字段** |
| **D4-R11** | **趋势端点不下发维度分**（`FamilyTrendPointView` 无 `dimensionScores`）⇒ 端侧 `family-trend` 维度分恒空态 | **保持残留**：`D4-frontend-migration.md` §2.5 原假设「`dims` 由后端 `dimensionScores` 映射」**与后端 DTO 不符**（实施期实读证伪）。维度分只有「报告详情」端点可得；端侧按"缺失→空态、不造比例还原"处理。若要趋势图带维度，须扩 DTO（**超本卡范围**，另开卡） |

---

## 附：图文件

- 类图：`docs/design/D4-class-diagram.mermaid`
- 时序图：`docs/design/D4-sequence-diagram.mermaid`
- 端侧专章：`docs/design/D4-frontend-migration.md`
- 验收清单：`docs/reviews/D4-e2e-checklist.md`

> 命名沿用本仓 `docs/design/` 惯例（`D4-` 前缀），与 `D2-*`/`D3-*` 并列。
