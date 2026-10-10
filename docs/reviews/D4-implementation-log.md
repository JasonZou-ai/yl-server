# D-4a 实施日志（家属域后端 + elder↔user 绑定 + authLogout）

> 角色：实现工程师（Alex）｜范围：T01–T08（**不含 T09 端侧切真 / H5 E2E**）
> 设计（唯一权威，已冻结）：`docs/design/D4-family-domain-design.md`
> 纪律：**增量落盘**（每完成一个 T 追加一段）+ **突变即时还原**；任何偏离设计处**显式说明理由**。

---

## T01（P0）数据域两锚 + 先行失败单测（★F1 / ★F3 修复）

**改动文件**

- `yl-modules/yl-module-account/src/main/java/cn/yl/modules/account/security/DataScopePermissionHandler.java`
- `yl-modules/yl-module-account/src/test/java/cn/yl/modules/account/security/DataScopePermissionHandlerTest.java`

**先红后绿证据（本卡纪律）**

先只加 3 条单测、不动处理器，运行：

```
mvn -o -q -pl yl-modules/yl-module-account test -Dtest=DataScopePermissionHandlerTest
```

红（3 条，正是预期）：

```
[ERROR] Tests run: 22, Failures: 1, Errors: 2, Skipped: 0
[ERROR]   selfScopeAnchorsElderFamilyBindViaElderUserId:305 expected:
          <(elder_family_bind.elder_id IN (SELECT p.id FROM elder p WHERE p.user_id = 900000000000000003))>
          but was: <(1 = 0)>
[ERROR]   orgScopeAnchorsElderFamilyBindViaElderOrgId:282 NullPointer ... "expr" is null
[ERROR]   orgScopeAnchorsElderFamilyBindFailClosedWithoutOrg:292 NullPointer ... "expr" is null
```

> 现状印证：scope=1 → `elder_family_bind` 在 `SELF_DENY_TABLES` → `(1 = 0)`（★F1 功能死路）；scope=2 → 未登记 →
> 返回 `null`（★F3 "未登记即放行"）。

**处理**

1. 新增 `ORG_VIA_PARENT`（`elder_family_bind → (elder_id, elder)`）与 `SELF_VIA_PARENT`（同上）。
2. `SELF_DENY_TABLES` **移除** `elder_family_bind`（迁往 `SELF_VIA_PARENT`，同 D-2 把 `elder` 迁往 `SELF_COLUMN` 同构）。
3. `getSqlSegment` 两分支各插入反查锚判定（scope=2 / scope=1）；新增私有 `orgViaParentCondition` /
   `selfViaParentCondition` / `joinLongs`（复用 `subSelect` 拼装，禁字符串拼接整段 SQL）。
4. `relationSegment`（scope=5）**不动**：`elder_family_bind` 仍走 `selfCondition(table, "family_user_id", me)`。
5. 类 Javadoc 追加 D-4 段（两锚 + 为何零 DDL）。

**绿（4 条：3 新增 + 既有关联回归，全部通过）**

```
mvn -o -q -pl yl-modules/yl-module-account test -Dtest=DataScopePermissionHandlerTest
（exit 0，无失败）
```

**渲染结果（SQL 片段级，与设计 §6.3 逐字一致）**

| scope | 表 | 渲染 |
|---|---|---|
| 2 | `elder_family_bind` | `(elder_family_bind.elder_id IN (SELECT p.id FROM elder p WHERE p.org_id IN (1)))` |
| 2（无机构） | `elder_family_bind` | `(1 = 0)`（失败即拒） |
| 1 | `elder_family_bind` | `(elder_family_bind.elder_id IN (SELECT p.id FROM elder p WHERE p.user_id = <me>))` |
| 5 | `elder_family_bind` | `(elder_family_bind.family_user_id = <me>)`（**不变**） |

**与设计的偏离**：无。新增了 1 条设计未显式列出的红单测 `orgScopeAnchorsElderFamilyBindFailClosedWithoutOrg`
（scope=2 且 `orgIds` 为空 → 必须恒假），属**加强**（钉死"反查锚不得因 orgIds 为空退化为不加限"），非语义偏离。

**状态**：✅ 完成（单测 22/0 于本类；全仓数字见 T08 收口段）。

---

## T02（P0）契约冻结（v1.1.2 → v1.1.3）

**改动文件**

- `openapi/yl-api.yaml`（版本、翻旗、数据域订正、权限码订正、新 schema、新操作）
- `yl-bootstrap/src/test/java/cn/yl/bootstrap/contract/OpenApiContractTest.java`（`CONTROLLERS` +`FamilyBindingController`）
- `yl-bootstrap/src/test/java/cn/yl/bootstrap/security/ControllerAuthorizationCoverageTest.java`（`CONTROLLERS` +`FamilyBindingController`；白名单 5→6；类注释扩为四类理由）

**改动明细**

1. `info.version: 1.1.2 → 1.1.3`。
2. **翻旗 10 个** planned → implemented：`authLogout` / `familyBindingList` / `familyBindingCreate` / `familyBindingApprove` /
   `familyBindingReject` / `familyBindingUnbind` / `familyReportList` / `familyReportDetail` / `familyTrend` / `familyCarePlan`。
3. **新增 1 个操作** `POST /api/v1/elders/{elderId}/user-binding`（`elderUserBinding`；`account:org:manage`；`x-data-scope.
   mode: org`；`Idempotency-Key` 必填；`R<UserBindingView>`）→ 合计 **翻旗 11**。
4. **3 处 `x-data-scope` 订正**（设计 §7）：
   - `familyBindingReject`：`mode: relation` → **`mode: self` + `via: elder` + condition `elder_family_bind.elder_id IN
     (SELECT id FROM elder WHERE elder.user_id = 登录用户)`**（★F1）；
   - `familyBindingApprove`：补 condition `elder_family_bind.elder_id IN (SELECT id FROM elder WHERE elder.org_id IN
     (登录机构))`（★F3）；
   - `familyBindingList`：补同 ② 的 ORG_ADMIN condition（本表 FAMILY 视角保留）。
5. **★C1 `x-required-permission` 本体订正**：`familyBindingApprove`：`account:family:bind` → **`account:org:manage`**。
6. 新增 schema `UserBindingRequest` / `UserBindingView`；补注 `FamilyBindingCreate`（核验必填说明）与 `FamilyBindingView`
   （未生效阶段 `elderName` 不下发）。

**已知红（翻旗后、控制器尚未建，一次亮清）**

```
bash scripts/verify-openapi.sh
契约操作总数：44（implemented 44 / planned 0）
[5/5] 漂移：契约 x-status=implemented ↔ 控制器端点（双向核对）
  ✗ 控制器端点数 = 33（期望 44）
  ✗ 契约多登的 implemented 端点（未实现却标 implemented）条数 = 11（期望 0）
    契约多登：POST /api/v1/auth/logout · POST /api/v1/elders/{elderId}/user-binding ·
      GET/POST /api/v1/family/bindings · /{id}/approve | reject | unbind ·
      /{relationId}/reports | reports/{reportId} | trend | care-plan
[1/5]~[4/5] 全绿（44 操作扩展键/mode/public/sensitive 自洽）
```

> **该红点即"门禁有区分力"的验收证据**：契约多登 11 条**逐条命中**本卡待实现操作；[1/5]~[4/5] 已绿说明订正后的
> `x-data-scope` / `x-required-permission` / 敏感点标注均**自洽合规**。红点将由 T03~T06 逐条收敛至 0。

**与设计的偏离**：无。

- 说明①：`authLogout` 补 `requestBody`（`refreshToken` 可选）—— 依据设计 §11#1 的**建议**（该处建议补、并登记为契约待订正项）；
  非 §7 强制项，且不影响任何门禁（本卡 T06 实现即接受可选 `refreshToken`）。
- 说明②：`familyBindingUnbind` 的 `x-required-permission` **保持** `account:family:unbind`（**未**改为 OR 双码）—— 依 §7
  "仅 1 处权限码订正"；实现侧 `@RequiresPermission` 用 OR 语义（含敏感码 ⇒ 强制第四闸），契约在 `description` 显式说明 OR 语义以透明收口。

**状态**：✅ 契约代码侧改动完成（已知红待 T03~T06 收敛）。

---

## T03（P0）实体 / Mapper / DTO + ★F2 核验 Mapper

**新增文件（18 主 + 1 测）**

- 实体：`domain/entity/ElderFamilyBind.java`（**不映射 `active_uk`**、映射 `deleted`）
- Mapper：`mapper/ElderFamilyBindMapper.java`、`mapper/FamilyBindingVerifyMapper.java`（★F2）、
  `mapper/FamilyReportSourceMapper.java`、`mapper/FamilyCarePlanSourceMapper.java`
- DTO：`FamilyBindingCreateRequest` / `FamilyBindingView` / `FamilyBindingRejectRequest` / `FamilyBindingQuery` /
  `ReportSourceRow` / `DimensionScoreRow` / `CarePlanRow` / `FamilyReportView` / `FamilyReportDetailView` /
  `FamilyTrendPointView` / `FamilyCarePlanView` / `UserBindingRequest` / `UserBindingView`
- 测试：`test/.../mapper/FamilyBindingVerifyMapperTest.java`

**★F2 核验 Mapper**：`countExactMatch(Long,String,String):int`（`SELECT COUNT(*) ... name_hash/id_card_hash/`deleted=0`）
+ `hasLoginAccount(Long):boolean`（`SELECT EXISTS(...)`）——**只返标量**；类 Javadoc 显式论证"本卡唯一绕过第三闸的读" +
最小化/同码防枚举/频控三重补偿。单测反射扫描 **全部方法返回类型 ⊆ {int, boolean, long}**（第二断言进一步禁 `String`/集合/实体）。

**验证**

```
mvn -o -q -pl yl-modules/yl-module-archive test -Dtest=FamilyBindingVerifyMapperTest  → exit 0
```

**与设计的偏离**：

- ① `FamilyReportSourceMapper#selectReportById` 的 `r.id` **改为 `r.id AS report_id`**（设计 §2.3 原文为 `r.id`）——
  设计 §3.2 的 `ReportSourceRow` 无 `id` 属性、只有 `reportId`；不加别名则 `reportId` 恒 null。属"使设计意图**真正生效**"的
  必要订正，非语义变更。
- ② `ReportSourceRow` 补 `orderId/ruleVersionId/preliminary*/final*/levelChangeReason/status` 字段（设计 §3.2 仅列 8 字段，
  但 §2.3 `selectReportById` 选取了这些列）—— 使详情头 `levelName` 有来源。

**状态**：✅ 完成。

---

## T04（P0）绑定状态机五端点（`list`/`create`/`approve`/`reject`/`unbind`）

**新增文件**

- `service/FamilyBindingService.java`（`apply` / `approve` / `reject` / `unbind` / `list`）
- `controller/FamilyBindingController.java`（T04 落 5 handler；T05 追加 4 读 handler）
- `test/.../service/FamilyBindingServiceTest.java`、`test/.../controller/FamilyBindingControllerTest.java`

**要点落实**

- `approve` 挂 **`ACCOUNT_ORG_MANAGE`**（★C1，非 `ACCOUNT_FAMILY_BIND`）；反射断言 `approveRequiresOrgManage` 钉死。
- `apply` 顺序：① 频控 → ② `verifyMethod ∈ {SCAN,PHONE,FACE}` 否则 10001 → ③ `support.isAvailable()` 否则 **40001（零
  insert）** → ④ `hashForSearch` → ⑤ `countExactMatch==0 → 10004`（同码）→ ⑥ `hasLoginAccount` 定落态（有账号→0 / 无账号→1）
  → ⑦ 查重 `10005` → ⑧ insert。
- 状态机：`approve` 认 **{0,1}→2**（★C2）、`reject` 认 {0,1}→3、`unbind` 认 2→4（`deleted` 保持 0）。
- `unbind`：`@RequiresPermission({BIND,UNBIND}, OR)`（含敏感码 ⇒ 强制第四闸）；服务层 `selectById` 双保险。
- `@Idempotent(verifyBody=true)`（缺键 → 10007 由切面抛）；`@Audit` 四动作
  `FAMILY_BIND_APPLY/APPROVE/REJECT/UNBIND`（`biz_type=ELDER_FAMILY_BIND`，解绑 `sensitive=1`）。

**验证**

```
mvn -o -q -pl yl-modules/yl-module-archive spotless:apply
mvn -o -q -pl yl-modules/yl-module-archive test   → exit 0（含 FamilyBindingServiceTest 15 例 + ControllerTest 9 例）
```

**与设计的偏离（★重要，需主理人裁决）**：

- **频控改用既有端口 `cn.yl.common.ratelimit.RateLimiter`（`RedisRateLimiter` 实现），而非直接注入 `StringRedisTemplate`。**
  理由：`yl-module-archive` 的编译期依赖仅 `yl-api` + `mybatis-plus` + `test`，**Redis 不在其 classpath**（`StringRedisTemplate`
  不可编译）；`RateLimiter` 正是项目既有的限流端口（`yl-common`，经 `yl-api` 传递可达），语义（按 userId、60s 窗口、上限 10）
  完全一致。**键名由 `yl:fam:verify:<userId>` 变为 `yl:rl:fam:verify:<userId>:<窗口起点>`**（`RedisRateLimiter` 自带 `yl:rl:`
  前缀 + 窗口分桶）。若主理人坚持字面键名，需给 archive pom 增 `spring-boot-starter-data-redis` 并直接注入 `StringRedisTemplate`。
- **`apply` 查重口径按设计 §2.2⑥ 字面实现**（同 `elder_id`+`family_user_id`+`deleted=0` 已存在 → 10005）。**发现设计内部张力**：
  §3.3 声称「status=3/4 旧行保留、同对可**新增行**」，但冻结的 `uk_elder_family(elder_id, family_user_id, active_uk)` 在旧行
  `deleted=0` 时 `active_uk=1` → **新增同对行必违反唯一键**。零 DDL 下二者不可兼得。本实现取"字面 ⑥ + 兜底 `DuplicateKeyException→10005`
  （绝不 500）"，即**解绑/拒绝后重新申请返回 10005**（PRD「重新申请」流程受阻）。**登记为待裁决残留（见报告）**，未静默改设计。
- **`approve`/`reject` 返回视图的 `elderName` 为 `null`**：仅 `list`（读路径）按设计 §1.7 组装脱敏名（且仅对 status=2 行）。
  理由：减少写路径对 `elder` 读取的耦合，且未生效阶段本就不下发姓名（§1.5）。冒烟不涉及该字段。

**状态**：✅ 完成。

---

## T05（P0）家属读三端点 + 照护（`reports` / `reportDetail` / `trend` / `carePlan`）

**新增/修改文件**

- 新增 `service/FamilyReportQueryService.java`（`reports` / `detail` / `trend` / `carePlan` + 私有 `requireMyActiveBinding`）
- 改 `controller/FamilyBindingController.java`：追加 4 个读 handler（构造器新增 `FamilyReportQueryService`）
- 改 `mapper/FamilyReportSourceMapper.java`：新增 `selectTrendPoints(Long):List<ReportSourceRow>`（见偏离 ①）
- 新增 `test/.../service/FamilyReportQueryServiceTest.java`（14 例）
- 改 `test/.../controller/FamilyBindingControllerTest.java`：构造器加 mock + 4 读端点委托断言 + `readEndpointsRequireReportView`

**收口与口径**

- **唯一入口 `requireMyActiveBinding(relationId)`**：`bindMapper.selectById(relationId)`（第三闸按角色行级收口；FAMILY →
  `family_user_id=me`，ORG_ADMIN 经 `elder.org_id`）→ 查不到 / `status!=2` / `deleted!=0` / **已过期（`auth_end_at<now`）**
  一律**同码 `10004`**（不泄露存在性）。
- `reports`：`selectPublishedPage(Page, elderId)`（仅 `eval_report.status=1`，倒序）→ `FamilyReportView`。
- `detail`：`selectReportById(reportId)` 后**三重同码校验**（存在 / `status=1` / `report.elder_id == binding.elder_id`，否则 `10004`）→
  维度得分 + 照护条目（`selectConfirmedPlan(elderId)`）。
- `trend`：`selectTrendPoints(elderId)`（`published_at` **升序**）→ `FamilyTrendPointView`（`levelName` 优先终判、回退初判）。
- `carePlan`：`selectConfirmedPlan(elderId)`（`care_plan.status=1`）→ 命中即组装；**无已确认方案 → 空方案**（`planId=null`、`items=[]`、
  HTTP 200 + `code=0`，**不返 10004**）。
- **`items_json` → `List<String>`**：自建 `ObjectMapper` 解 `[{title,content,source}]`；渲染 `title：content`（仅其一存在取其一、纯字符串元素原样保留）；
  **空串 / 非法 JSON / 非数组 → 空列表**（仅 `log.warn`，不抛错、**不造数**）。
- 第二闸：4 读 handler 全部 `@RequiresPermission(REPORT_VIEW)`（`report:view`，五角色），**无** `@Idempotent`/`@Audit`（纯读，设计 §4 第 6–9 行）。

**验证**

```
mvn -o -q -pl yl-modules/yl-module-archive spotless:apply
mvn -o -q -pl yl-modules/yl-module-archive -am test
→ FamilyReportQueryServiceTest 14/0 · FamilyBindingControllerTest 15/0 · FamilyBindingServiceTest 14/0（模块全绿）
```

**与设计的偏离**：

- ① **新增 `FamilyReportSourceMapper#selectTrendPoints`**：设计 §2.3「精确列清单」与类图仅列 3 个 Mapper 方法，**未给出趋势 SQL**；
  而 `FamilyTrendPointView` 需要 `levelName` 且趋势须升序，`selectPublishedPage`（倒序、不含等级名）无法满足。故按设计意图补 1 个
  **同源只读投影**（列名逐列取自 `02_schema.sql` 的 `eval_report`，与 `selectReportById` 同列），**不新增文件、不引兄弟模块类**。属设计空白的
  实现补齐（非语义变更）。
- ② `requireMyActiveBinding` 用 **`selectById`**（遵设计 §2.2 / 时序图 L166），服务层**另加 `deleted!=0 → 10004` 显式校验**兜底
  （`selectById` 不过滤软删列；本卡模型下 `unbind` 保持 `deleted=0`，此校验为防御性，不影响正常链路）。

**状态**：✅ 完成。

---

## T06（P0）`elder↔user` 绑定 + `authLogout`

**新增/修改文件**

- 新增 `yl-common/.../security/TokenBlacklistStore.java`（接口：`add(String,long,long)` / `isBlacklisted(String)`）
- 新增 `yl-infrastructure/.../token/RedisTokenBlacklistStore.java`（键 `yl:bl:at:<sha256Hex(token)>`，值 userId，TTL=剩余）
- 新增 `yl-module-archive/.../mapper/ElderUserOrgMemberMapper.java`（只读投影 `sys_org_member`）
- 新增 `yl-module-archive/.../service/ElderUserBindingService.java`（`bind` 三道校验）
- 新增 `yl-module-account/.../dto/LogoutRequest.java`（`refreshToken` 可选）
- 改 `yl-api/.../security/JwtTokenProvider.java`（+`remainingTtl`）
- 改 `yl-api/.../security/JwtAuthFilter.java`（+`TokenBlacklistStore` 依赖；`parse` 成功后命中即短路 401）
- 改 `yl-module-account/.../controller/AuthController.java`（+`logout` handler，无 `@RequiresPermission`）
- 改 `yl-module-account/.../service/AuthService.java`（+`TokenBlacklistStore` 依赖；+`logout`）
- 改 `yl-module-archive/.../controller/ElderController.java`（+`userBinding` handler，`account:org:manage` + 幂等 + `@Audit ELDER_USER_BIND`）
- 新增 `test/.../archive/service/ElderUserBindingServiceTest.java`（8 例）
- 改 `test/.../api/security/JwtAuthFilterTest.java`（注入 mock 黑名单 + 命中/未命中 2 例）
- 改 `test/.../account/service/AuthServiceChannelDowngradeTest.java`（构造器 +黑名单 mock）
- 改 `test/.../archive/controller/ElderControllerTest.java`（构造器 + `bindUser` 委托/权限码/未认证 3 例）

**要点**

- **黑名单键口径**：`RedisTokenBlacklistStore` 内部对**原令牌**做 `sha256`，`add` / `isBlacklisted` 共用同一键函数 → 键空间一致；
  不存原令牌。`JwtAuthFilter` 在 `parse` 成功后、装配 `LoginUser` 之前查黑名单，命中即 `writeUnauthorized`（401 + `R.code=10002`），
  **不装配身份**。
- **`remainingTtl`**：`exp - now`；已过期 → `Duration.ZERO`（调用方跳过登记）；不可解析 → 退化 `accessTtl`。
- **logout**：access 入黑名单（TTL=剩余）+ 携带 refresh 则 `revoke`（双令牌同时失效）；幂等、不抛业务异常；身份取登录态。
- **user-binding**：① `user ∈ myOrgs`（`ElderUserOrgMemberMapper` ∩ `operator.orgIds`，否则 **20003**）→ ② `selectById(elderId)`
  （第三闸 → 越界/不存在 **10004**）→ ③ 占用判（`user_id` 非空且 ≠ 请求 → **10005**）→ ④ `updateById(user_id)`（`uk_elder_user` 冲突
  → **10005**，绝不 500）。

**验证**

```
mvn -o -q spotless:apply && mvn -o -q test
→ 全仓单测 246/0；新增 ElderUserBindingServiceTest 8/0、JwtAuthFilterTest 8/0
bash scripts/verify-openapi.sh → 契约 44（implemented 44 / planned 0），8/8 通过，漂移 0
MYSQL_PASSWORD=… MYSQL_BIN=/c/mysql/bin/mysql.exe bash scripts/verify-schema.sh → 83/0
```

**与设计的偏离（★需主理人裁决）**：

- ① **【设计错误】`elder` 表无 `auth_end_at` 列**：设计 §1.2/§2.2/§3.1 多处要求 `user-binding` 写 `elder.user_id + auth_end_at`，但实读
  `02_schema.sql` 的 `elder` 表**无 `auth_end_at` 列**（该列只存在于 `elder_family_bind` L229），且本卡**零 DDL**（不得加列）。本实现按设计
  §1.2 标题「本期只做 `elder.user_id`」落地：**只写 `elder.user_id`**，请求体 `authEndAt` 予以接收但**不落库**（仅 `log.warn` 留痕）。
  **未自改设计**，登记为待裁决残留（见报告 R-**）。
- ② **archive 侧自建 `ElderUserOrgMemberMapper` 而非复用 account 的 `SysOrgMemberMapper`**：① 模块边界（archive 不依赖 account）；② **同名简单类会
  触发 Spring Bean 名冲突**（注解扫描按简单类名生成 Bean 名，两个 `SysOrgMemberMapper` → `ConflictingBeanDefinitionException`）。类图 §"ElderUserBindingService →
  SysOrgMemberMapper" 为概念引用，本卡以 archive 自持投影落地其语义（`sys_org_member` 逐列照 `02_schema.sql`）。
- ③ 新增 `LogoutRequest` DTO（`yl-module-account/dto`）：类图 `AuthController#logout(HttpServletRequest, LogoutRequest)` 需要，而设计 §2.1
  **文件清单 A/B 未列**该 DTO（清单遗漏）。

**状态**：✅ 完成。

---

## T07（P1）dev 联调种子账号（老人本人 + 家属）

**修改文件**：`docker/mysql/init/04_seed_dev_accounts.sql`（只加不改；**零 DDL**、**不动 verify-schema 断言**）

- 新增 `sys_user`：`elder_dev`（id `…003`，ELDER/1001，data_scope=1）、`family_dev`（id `…004`，FAMILY/1003，
  data_scope=5）；口令 `YlDev@2026`（沿用同款 BCrypt 散列）。
- 新增 `sys_user_role`：`…003→1001`、`…004→1003`。
- 新增 `sys_org_member`：两行归属联调机构 `org=1`（`role_id` 与 `sys_user_role` 一致，`is_default=1`）。
- 全部 `INSERT IGNORE`（幂等）。

**验证**

```
MYSQL_PASSWORD=… MYSQL_BIN=/c/mysql/bin/mysql.exe bash scripts/verify-schema.sh → 83/0（断言数不变）
select … → 四账号/四角色绑定/四机构成员齐备
```

**与设计的偏离**：

- **不在 04 种子中造 `elder` 行**：设计 §T07 允许「种子 UPDATE 或经 `user-binding` 端点」二选一。本卡取**后者**——`elder.name_enc`
  为 `NOT NULL` **且禁止明文**，而种子阶段无可用 dev 敏感字段编解码器，强写占位值会在 `detail`/`list` 解密时抛错（500），故一律由
  `POST /api/v1/elders/{elderId}/user-binding` 在建档后回填（T08 冒烟 `[9/9]` ELDER 段执行），规避「明文落密文列」与「解密失败」两类风险。

**状态**：✅ 完成。

---

## T08（P1）冒烟 `[9/9]` 段 + 验收清单 + 四项门禁收口

**改动文件**

- `scripts/smoke-report-review-chain.sh`（改：新增 `[9/9]` 家属域段 9.1–9.9 + `ELDER_USER/FAMILY_USER` 变量 + 清理块 + 头注）
- `docs/reviews/D4-e2e-checklist.md`（新）
- `docs/reviews/D4-implementation-log.md`（本文件，增量追加）

**★ 缺陷修复（T08 实测暴露；**非设计偏离**）——`FamilyBindingVerifyMapper` 从未真正绕过第三闸**

- **现象**：`POST /family/bindings`（FAMILY）核验恒失败 → `R.code = 10004`（同 plaintext 下 `POST /elders` 建档成功、库内 `name_hash`/`id_card_hash` 齐备）。
- **定位证据**：
  1. 手工 SQL `SELECT COUNT(*) FROM elder WHERE id=? AND name_hash=? AND id_card_hash=? AND deleted=0` → **1**（数据 + 摘要均匹配）；
  2. 服务端 SQL 日志显示核验 SQL **被第三闸改写**为 `… AND (elder.id IN (SELECT b.elder_id FROM elder_family_bind b WHERE b.family_user_id=? AND b.status=2 …))` —— 申请前该子查询**恒空** → 核验恒 0 → `10004`；
  3. 即：类 Javadoc 声称"本卡唯一绕过第三闸的读"，但**接口从未携带任何绕过机制**（`DataScopePermissionHandler.getSqlSegment` 对所有表一视同仁，`mappedStatementId` 未参与判定）。
- **修复**：`FamilyBindingVerifyMapper` 接口加 `@com.baomidou.mybatisplus.annotation.InterceptorIgnore(dataPermission = "1")`。
  - 依据（已核 MP 3.5.5 字节码）：`DataPermissionInterceptor#beforeQuery` 先调 `InterceptorIgnoreHelper.willIgnoreDataPermission(ms.getId())`，命中即**跳过 SQL 改写**；`MybatisMapperAnnotationBuilder#parse` 会把类级 / 方法级 `@InterceptorIgnore` 写入该缓存。
  - 这正是设计 §10.8「唯一绕过第三闸的读」要求却**未给出机制**的落地方式（不改任何其它 Mapper / 不引跨模块字符串耦合）。
- **回归钉死**：`FamilyBindingVerifyMapperTest#mapperIsExemptedFromDataPermissionThirdGate`（反射断言 `@InterceptorIgnore.dataPermission` 为真）—— 删注解即红，防"家属申请链静默失效"复现。

**★ 环境缺陷（非代码）——并发 `mvn verify` 替换 fat jar 会击穿运行中的后端**

- **现象**：冒烟中途大量 HTTP `000`、响应体为空（后端不可达）；同一实例前序请求曾正常。
- **根因**：fat jar 位于 `yl-bootstrap/target/yl-bootstrap.jar`；`jar:jar` + `spring-boot:repackage` 会**就地替换**该文件。后端正以 `-cp …/target/yl-bootstrap.jar` 运行时，类加载器**懒加载**后续类会读到被改写的 jar → `NoClassDefFoundError` → 进程崩溃（崩溃实例的令牌 / 数据域装配随之异常，读接口误判 `10004`）。
- **规避（已写入 checklist §1 前言）**：后端从 `target/` **之外**的 jar 副本启动：
  `mkdir -p /tmp/d4-run && cp yl-bootstrap/target/yl-bootstrap.jar /tmp/d4-run/ && java -cp /tmp/d4-run/yl-bootstrap.jar org.springframework.boot.loader.launch.JarLauncher …`。
- 改用 jar 副本后，同一链路 **108 通过 / 0 失败 / 1 跳过**（唯一跳过为 D-3 的 B9 CSV 魔数 / 表头 / 注入转义，需 `EXPORT_DIR`，与本卡无关）。

**四项门禁实测（2026-09-29）**

| 门禁 | 命令 | 实测 |
|---|---|---|
| 单测 + 静态检查 | `mvn -o verify` | **247 通过 / 0 失败**；Checkstyle **0** violations（14 模块）；SpotBugs `BugInstance size is 0`（9 模块）；**BUILD SUCCESS** |
| 结构门禁 | `MYSQL_BIN=… MYSQL_PASSWORD=… bash scripts/verify-schema.sh` | **83 通过 / 0 失败 / 0 待批准守卫**（零 DDL；保留字金丝雀 1） |
| 契约门禁 | `bash scripts/verify-openapi.sh` | **8 通过 / 0 失败**；契约操作 **44（implemented 44 / planned 0）**；双向漂移 **0** |
| 端到端冒烟 | `… bash scripts/smoke-report-review-chain.sh` | **108 通过 / 0 失败 / 1 跳过**（`[9/9]` 33 条全绿） |

**`[9/9]` 家属域断言全绿（摘要）**

user-binding `code=0`、FAMILY 打 user-binding → `20003`（★C1）、申请 `code=0` 且 `status∈{0,1}`（有账号→0 / 无账号→1，★C2）、老人 reject `code=0`→`status=3`、ELDER 自读 `/elders` `code=0`（★C3 声明 D4-R7 恒假）、FAMILY 打 approve → `20003`（★C1 直接钉住）、ORG_ADMIN approve `code=0`→`status=2`、reports / trend / care-plan `code=0`、越权 `relationId` → `10004`、unbind 缺凭据 `10008` / 带凭据 `code=0`、无凭据 `401 + 10002`、logout 六步（access 入黑名单 → 旧 access `401+10002`；refresh 吊销 → 旧 refresh `200+10002`）。

**第三闸三锚**现场复核（服务端 SQL 日志实测）

| 登录态 | 表 | 渲染 |
|---|---|---|
| FAMILY(5) | `elder_family_bind` | `(elder_family_bind.family_user_id = 900000000000000004)` |
| FAMILY(5) | `eval_report` | `(r.elder_id IN (SELECT b.elder_id FROM elder_family_bind b WHERE b.family_user_id = …004 AND b.status = 2 AND b.deleted = 0 AND (b.auth_end_at IS NULL OR b.auth_end_at > NOW())))` |
| ELDER(1) | `elder_family_bind` | `(elder_family_bind.elder_id IN (SELECT p.id FROM elder p WHERE p.user_id = 900000000000000003))`（★F1） |
| ORG_ADMIN(2) | `elder_family_bind` | `(elder_family_bind.elder_id IN (SELECT p.id FROM elder p WHERE p.org_id IN (1)))`（★F3） |

**与设计的偏离**：除上记「绕过机制落地」（设计 §10.8 要求、未给机制）外无新增偏离；T01–T07 偏离见各节，均未静默改设计。

**状态**：✅ 完成（T01–T08 全绿收口）。

---

## 附：D-4a 收口报告（T01–T08）

**交付物清单**

- 生产代码：`yl-module-archive`（家属域 11 端点 + 实体/Mapper/DTO/服务/控制器 + `@InterceptorIgnore` 修复）、`yl-module-account`（第三闸两锚 + `AuthController#logout`/`AuthService#logout` + `LogoutRequest`）、`yl-api`（`JwtTokenProvider#remainingTtl` + `JwtAuthFilter` 黑名单短路）、`yl-common`（`TokenBlacklistStore`）、`yl-infrastructure`（`RedisTokenBlacklistStore`）。
- 契约：`openapi/yl-api.yaml`（v1.1.3；翻旗 11；3 处 `x-data-scope` + ★C1 `x-required-permission` 订正；`UserBinding*` schema）。
- 种子：`docker/mysql/init/04_seed_dev_accounts.sql`（+`elder_dev`/`family_dev`）。
- 测试：新增/改 10+ 测试类（含 `FamilyBindingVerifyMapperTest` 的绕过钉死）。
- 脚本/文档：`scripts/smoke-report-review-chain.sh`（`[9/9]`）、`docs/reviews/D4-e2e-checklist.md`、本日志。

**四项门禁**：`mvn -o verify` 247/0（Checkstyle 0 / SpotBugs 0）；`verify-schema.sh` 83/0；`verify-openapi.sh` 8/0（44；漂移 0）；冒烟 108/0/1。**零 DDL、零新增错误码、业务失败恒 HTTP 200 + `R.code`、未认证恒 HTTP 401 + `10002`** 均保持。

**待主理人裁决的残留 / 风险**

- **R-1【设计错误】`elder` 表无 `auth_end_at` 列**：设计 §1.2/§2.2/§3.1 要求 `user-binding` 写 `elder.user_id + auth_end_at`，实读 `02_schema.sql` 无此列；零 DDL ⇒ 本卡**只写 `elder.user_id`**，请求体 `authEndAt` 接收不落库（仅 `log.warn`）。未自改设计。
- **R-2【设计内部张力】`uk_elder_family` 与"重新申请"**：设计 §3.3 称 `status=3/4` 旧行保留、同对可新增行，但冻结唯一键（旧行 `deleted=0` ⇒ `active_uk=1`）使新增同对必冲突 ⇒ 解绑 / 拒绝后**重新申请返回 `10005`**（未静默改设计）。
- **R-3【环境】并发构建替换 fat jar**：见 T08 环境缺陷段；已给规避法。
- **R-4【环境】端口 / 实例争用**：本机曾出现外部实例重启占用 8080，导致冒烟中断（HTTP `000`）；最终以"自包含循环（清 java → 起副本 → 冒烟）"取得 108/0。
- **R-5**：`care_plan`/`report_dimension_score` 等投影仅覆盖本期读需求；`elder_authorization`（第三方范围化授权）仍未接入（设计登记 D4-R3）。

---

## D-4a 收尾（2026-09-30）：R-1 / R-2 / C5（设计侧已订正 §1.8 + 逐处；**本卡不动 `docs/design/*`、`docs/change/*`**）

> 主理人裁决：R-1、R-2 均为**设计写错**（非实现错）；C5 修法（`@InterceptorIgnore`）采纳并已写入设计。以下为按裁决落地的改码 + 新增断言 + 四门禁实测。

### R-1 `authEndAt` **删除**（收窄契约，非"接收但不落库"）

**改动文件**

- `yl-modules/yl-module-archive/.../dto/UserBindingRequest.java`：**删** `authEndAt` 字段 + 其 `import java.time.LocalDateTime`（字段无独立校验注解，随字段一并删除）；类 Javadoc 记「不接收、不落库、不告警」理由 + 1:1 承重语义。
- `yl-modules/yl-module-archive/.../service/ElderUserBindingService.java`：`bind(...)` 签名去掉 `authEndAt`（`bind(Long elderId, Long userId, LoginUser operator)`）；**删** `log.warn(...)` 整段 + **删** 随之无用的 `@Slf4j` 与其 import；只 `updateById` 写 `user_id`。类 Javadoc 订正为「收窄契约」。
- `yl-modules/yl-module-archive/.../controller/ElderController.java`：`bindUser` 调用改为 3 参；Javadoc 「请求体只带 `userId`（不含 `authEndAt`）」+ 补 1:1 说明。
- `openapi/yl-api.yaml`：`UserBindingRequest` schema **删** `authEndAt`，只留 `userId`（required）；`description` 补「老人↔账号 1:1（`uk_elder_user` 唯一）；冲突一律 `10005`，绝不 500；有效期不在此承载（`elder_authorization` ER-07，D4-R3）」。**操作总数不变（仍 44 / implemented 44 / planned 0）**。

**设计错误根因（实读确认）**：`docker/mysql/init/02_schema.sql` 的 `elder` 表**无 `auth_end_at`/`auth_start_at`**，只有 `user_id`（且 `UNIQUE KEY uk_elder_user (user_id)`）。零 DDL ⇒ 无合法承载；「接受但不落库」= 静默丢弃**安全语义**字段（调用方以为有效期已生效、实际永久）⇒ 违「宁可失败，不可假数据」，故**删而非收**。

**新增断言（10005，1:1 承重语义）**

- `ElderUserBindingServiceTest#bindSecondUserToAlreadyBoundElderReturnsConflict`（②：第二个账号绑已绑定老人 → 服务层占用判 → 10005，**不 update**）。
- `ElderUserBindingServiceTest#bindSecondElderToSameUserReturnsConflict`（①：同一账号绑第二个老人 → 撞 `uk_elder_user` → `DuplicateKeyException` 兜底 → 10005，**绝不 500**）。
- 既有 `bindRejectsWhenOccupiedByOtherUser` / `bindMapsDuplicateKeyToConflict` 保留（冲突兜底未回退）。

### R-2 「重新申请」**复用活性行**（不新增行）

**改动文件**：`yl-modules/yl-module-archive/.../service/FamilyBindingService.java`（`apply` 重构 + 两个私有方法）

- 核验 + 频控**照旧先跑**（复用分支**不跳过**核验 —— 申请人可能已换人）：① 频控 → ② 核验方式白名单 → ③ 编解码器未装配（40001，零 insert）→ ④ 算 hash → ⑤ `countExactMatch==0 → 10004` → ⑥ `hasLoginAccount` 定落态 0/1。
- ⑦ **新增**：按 `(elder_id, family_user_id)` 查 `deleted=0` **活性行**（`findActiveBind`，经 Mapper ⇒ **在第三闸内**，scope=5 经 `family_user_id=me` 收口）：
  - **无** → `insertNewBind`（INSERT，落态 0/1，不变）；
  - **`status ∈ {0,1,2}`** → `10005`（重复申请 / 已有生效绑定）；
  - **`status ∈ {3,4}`** → `resetReusableBind`：`UpdateWrapper` **显式复位** `status`=0/1、`reject_reason`=**NULL**、`verify_method`/`proof_cos_key` 覆盖为新值；`auth_start_at`/`auth_end_at` **不动**。
- **为何用 `UpdateWrapper` 而非 `updateById`**：MyBatis-Plus `updateById` 默认忽略 null 字段 ⇒ 无法把 `reject_reason` 复位为 `NULL`（这是"暴露设计意图真正生效"的必要订正，实现补白，非语义变更）。
- `unbind` **保持原样**（`status=4`、`deleted=0`）；**`deleted=1` 只表软删，不得表达业务状态**（否则该行对所有 `deleted=0` 读路径消失，含机构历史查阅）。

**设计错误根因（实读确认）**：`02_schema.sql` 的 DDL 注释 —— `active_uk = IF(deleted=0, 1, NULL)`，`NULL` 在唯一索引中互不相等 ⇒ **只有 `deleted=1` 才让位**；§3.3 原句「`status=3/4` 旧行保留、同对可**新增行**」与冻结的唯一键 `uk_elder_family(elder_id, family_user_id, active_uk)` **不可兼得**（旧行 `deleted=0` ⇒ `active_uk=1` ⇒ 新增同对必冲突）。

**新增断言**

- 单测：`FamilyBindingServiceTest#applyReusesRejectedRowAndResetsToPending`（status=3 → 0，`id` 不变、`reject_reason` 复位 NULL、`SET` 只碰 4 列且 `auth_*` 不动、零 insert）、`#applyReusesUnboundRowAndResetsToPending`（status=4 → 1）、`#applyStillVerifiesWhenReusableRowExists`（复用分支**不得跳过核验**：核验 0 → 10004、零 insert/update）、`#applyRejectsDuplicateWhenPendingOrg`（status=1 → 10005）、`#applyRejectsWhenAlreadyActive`（status=2 → 10005）；既有 `#applyRejectsDuplicateBinding`（status=0 → 10005）保留。
- 冒烟 `[9/9]`：9.4b（拒绝后重新申请 `code=0` + `id` 不变 + `status∈{0,1}` + 重复申请 10005）、9.6b（`status=2` 重复申请 10005）、9.7b（解绑后重新申请 `code=0` + `id` 不变 + `status∈{0,1}`）。

### C5 `@InterceptorIgnore` 接口级豁免 —— 补钉死单测 + Javadoc

**改动文件**

- `yl-modules/yl-module-archive/.../mapper/FamilyBindingVerifyMapper.java`：类 Javadoc 补「**豁免为接口级、全方法生效**」并点明"新方法绝不可返回老人字段/PII"。
- `yl-modules/yl-module-archive/.../test/.../mapper/FamilyBindingVerifyMapperTest.java`：**新增** `#mapperOnlyExposesScalarReadMethods` —— 断言接口**方法集合 ⊆ `{countExactMatch, hasLoginAccount}`**（并反向断言两方法必在）。豁免是**接口级、全方法生效**，故将来往接口加查询会**悄悄继承**第三闸豁免 → 本断言封死后门（新增方法即变红）。

### 四门禁实测（2026-09-30，工位实测原始输出）

| 门禁 | 命令 | 实测 |
|---|---|---|
| 单测 + 静态检查 | `mvn -o verify` | **255 通过 / 0 失败**（247→255，+8 新断言）；Checkstyle **0** violations（14 模块）；SpotBugs `BugInstance size is 0`（9 模块）；**BUILD SUCCESS** |
| 结构门禁 | `MYSQL_BIN=… MYSQL_PASSWORD=… bash scripts/verify-schema.sh` | **83 通过 / 0 失败 / 0 待批准守卫**（零 DDL；保留字金丝雀 1） |
| 契约门禁 | `bash scripts/verify-openapi.sh` | **8 通过 / 0 失败**；契约操作 **44（implemented 44 / planned 0）**；双向漂移 **0** |
| 端到端冒烟 | `MYSQL_BIN=… MYSQL_PASSWORD=… SECOND_VERIFY_DEV_CODE=… bash scripts/smoke-report-review-chain.sh` | **116 通过 / 0 失败 / 1 跳过**（108→116，+8 条 ★R-2 断言；`[9/9]` 41 条全绿；唯一跳过 = D-3 B9 CSV，需 `EXPORT_DIR`） |

**原始输出（节选）**

```
# verify（各模块汇总）→ 255 = 11+61+73+83+3+11+13；Checkstyle 0 / SpotBugs 0，BUILD SUCCESS
[INFO] Tests run: 83, Failures: 0, Errors: 0, Skipped: 0        # yl-module-archive（含新增 8 例）
[INFO] You have 0 Checkstyle violations.
[INFO] BugInstance size is 0
[INFO] BUILD SUCCESS

# verify-openapi.sh
 契约门禁结果：通过 8 项，失败 0 项（契约操作 44，其中 implemented 44）

# verify-schema.sh
 结果：通过 83 项，失败 0 项，待批准守卫 0 项（合计 83 项）

# smoke（[9/9] 新增段，★R-2）
  ✓ user-binding 绑定老人本人账号（code=0） = 0
  ✓ FAMILY 打 user-binding 被拒（业务码 20003，★C1） = 20003
  ✓ ★R-2 拒绝后重新申请（code=0，不得 10005） = 0
  ✓ ★R-2 拒绝后重新申请复用原活性行（id 不变） = 2104971979957379073
  ✓ ★R-2 拒绝后重新申请落态 status∈{0,1}（实际 0）
  ✓ ★R-2 重复申请 status∈{0,1}（code=10005） = 10005
  ✓ ★R-2 status=2 重复申请（code=10005） = 10005
  ✓ ★R-2 解绑后重新申请（code=0，不得 10005） = 0
  ✓ ★R-2 解绑后重新申请复用原活性行（id 不变） = 2104972018888908802
  ✓ ★R-2 解绑后重新申请落态 status∈{0,1}（实际 1）
 结果：通过 116 项，失败 0 项，跳过 1 项
```

**起服务口径（★沿用 T08 环境规避）**：副本启动 `mkdir -p /tmp/d4-run && cp -f yl-bootstrap/target/yl-bootstrap.jar /tmp/d4-run/` → `unset SERVER__PORT; export DB_PASSWORD=… REDIS_PASSWORD='' SENSITIVE_FIELD_KEY=… SENSITIVE_FIELD_HASH_KEY=… SECOND_VERIFY_DEV_CODE=…` → `java -cp /tmp/d4-run/yl-bootstrap.jar org.springframework.boot.loader.launch.JarLauncher --spring.profiles.active=dev --server.port=8080`。冒烟前 `netstat -ano | grep :8080` 清场。

### 一处非预期但必须处理的静态检查项（SpotBugs）

- **现象**：加完 R-2（`FamilyBindingService` 引入 `UpdateWrapper` 复位分支）后，`spotbugs:check` 对 `FamilyBindingController` 触发 **1 个** `EI_EXPOSE_REP2`（`new FamilyBindingController(FamilyBindingService, …)` 存 `bindingService`）；此前该模块 SpotBugs 为 0。
- **性质**：构造器注入 Spring **无状态单例 Bean** 的经典**误报**（非安全缺陷）。
- **处理（与先例同构）**：按 `ElderController` / 全仓 15+ 处先例，给 `FamilyBindingController` 构造器补 **构造器级** `@SuppressFBWarnings(value="EI_EXPOSE_REP2", justification="…Spring 无状态单例 Bean，构造器注入是标准 DI 用法")`，并把类 Javadoc「无抑制」旧表述改为「显式抑制（误报随分析输入变化，不作长期假设）」。**消除后 archive SpotBugs 复为 0**（`EI_EXPOSE_REP2` 从不构成安全缺陷，抑制安全）。

### 与设计的偏离

- **无**。R-1/R-2/C5 均按主理人裁决落地；`UpdateWrapper` 复位 `NULL` 是"使设计意图真正生效"的必要正交实现（`updateById` 忽略 null），非语义变更；`docs/design/*`、`docs/change/*`、三端 App 目录未触碰（T09 仍不做）。

### D-4a 收尾新增/修改文件清单

- 生产：`UserBindingRequest.java`、`ElderUserBindingService.java`、`ElderController.java`、`FamilyBindingService.java`、`FamilyBindingVerifyMapper.java`、`FamilyBindingController.java`（SpotBugs 抑制）。
- 契约：`openapi/yl-api.yaml`（`UserBindingRequest` 删 `authEndAt` + 1:1 description）。
- 测试：`ElderUserBindingServiceTest.java`（+2）、`ElderControllerTest.java`（签名）、`FamilyBindingServiceTest.java`（+5）、`FamilyBindingVerifyMapperTest.java`（+1）。
- 脚本/文档：`scripts/smoke-report-review-chain.sh`（`[9/9]` +3 子段/8 条断言）、`docs/reviews/D4-e2e-checklist.md`（§2.1/§2.3/§4/§5/§6 订正）、本日志。

