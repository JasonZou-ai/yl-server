# D-4a 独立验证 QA 报告（家属域后端 + elder↔user 绑定 + authLogout）

> 角色：独立验证 QA（Edward）｜立场：**证伪，非复述** —— 工程师的每个数字均由本 QA 从源码/真实 HTTP/真实 MySQL 独立复跑，不直接采信。
> 交付范围：T01–T08（**不含 T09 端侧切真 / H5 E2E**）。设计权威：`docs/design/D4-family-domain-design.md`（§1.7/§1.8/§3.3/§6/§8.3/§10/§11）。
> 验证时间：2026-09-30。**只读验证 + 临时突变（已全部还原，见 §3 与 §7）**；未改 `docs/design/*`、`docs/change/*`、生产代码语义。
>
> **本报告每个结论均附原始输出**；凡未亲自复跑者，一律列入 §7「未覆盖项」，不以工程师的措辞背书。

---

## §0 结论（一句话）

**放行。** 四门禁独立复跑全部达到/超过基线且**未发现任一 P0/P1 缺陷**；10 条突变**全部具有区分力**（逐条变红）；真实 HTTP 功能核查与三锚渲染逐字吻合；`approve` 权限码 = `account:org:manage` 已在运行时钉住。仅 2 条 P2 级观察项（§5），不影响放行。

---

## §1 环境与复跑口径

- 后端根 `<ROOT>` = `C:\Users\Administrator\WorkBuddy\2026-09-18-11-08-33\stage3_开发\yl-server`
- MySQL **8.0.46**（127.0.0.1:3306，root/`Mysql8@2026#Dev`）、Redis 可达（`redis-cli ping` → `PONG`）
- 起服务：**从 `/tmp/d4-run/` 的 jar 副本**启动（规避并发 `mvn verify` 就地替换 fat jar 击穿运行实例）：
  `java -cp /tmp/d4-run/yl-bootstrap.jar org.springframework.boot.loader.launch.JarLauncher --spring.profiles.active=dev --server.port=8080`
  已 `unset SERVER__PORT`；`DB_PASSWORD`/`REDIS_PASSWORD=''`/`SENSITIVE_FIELD_KEY`/`SENSITIVE_FIELD_HASH_KEY`/`SECOND_VERIFY_DEV_CODE=246810` 导出（密钥持久化于 `/tmp/d4-run/env.sh`，重启不变）。冒烟/复跑前 `netstat -ano | grep :8080` 清场确保单实例。
- 断言纪律：业务失败 = **HTTP 200 + `R.code`**；未认证 = **401 + `code=10002`**；无权 `20003`；缺二次验证 `10008`；不存在/越界 `10004`（同码不泄露存在性）。

---

## §2 四门禁独立复跑（原始输出）

### 2.1 单测 + 静态检查 `mvn -o verify`

✅ **255 通过 / 0 失败**（= 各模块 11+61+73+83+3+11+13）｜Checkstyle **0** violations（14 模块）｜SpotBugs `BugInstance size is 0`（9 模块）｜**BUILD SUCCESS**。

```
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0     # yl-api
[INFO] Tests run: 61, Failures: 0, Errors: 0, Skipped: 0     # yl-module-account
[INFO] Tests run: 73, Failures: 0, Errors: 0, Skipped: 0     # yl-module-evaluation
[INFO] Tests run: 83, Failures: 0, Errors: 0, Skipped: 0     # yl-module-archive
[INFO] Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0     # yl-module-supervise
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0     # yl-module-report
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0     # yl-bootstrap
[INFO] You have 0 Checkstyle violations.
[INFO] BugInstance size is 0
[INFO] BUILD SUCCESS
```
> 逐类明细（35 个测试类）已在原始日志 /tmp/d4-mvn-verify.log 核对，合计 255。**第 2 轮回归（突变全部还原后复跑）结果完全一致**：`BUILD SUCCESS`、255/0、Checkstyle 0、SpotBugs 0（/tmp/d4-mvn-verify2.log）。

### 2.2 结构门禁 `verify-schema.sh`

✅ **通过 83 项，失败 0 项，待批准守卫 0 项（合计 83 项）**；保留字金丝雀 = **1**。零 DDL 成立。

```
  ✓ 列名与 MySQL 保留字冲突数（命中列须反引号映射） = 1
 结果：通过 83 项，失败 0 项，待批准守卫 0 项（合计 83 项）
```

### 2.3 契约门禁 `verify-openapi.sh`

✅ **通过 8 项，失败 0 项**；契约操作 **44（implemented 44 / planned 0）**；双向漂移 **0**。

```
契约操作总数：44（implemented 44 / planned 0）
[5/5] 漂移：契约 x-status=implemented ↔ 控制器端点（双向核对）
  ✓ 控制器端点数 = 44
  ✓ 契约缺登的已实现端点（错误路径/漏登）条数 = 0
  ✓ 契约多登的 implemented 端点（未实现却标 implemented）条数 = 0
 契约门禁结果：通过 8 项，失败 0 项（契约操作 44，其中 implemented 44）
```

### 2.4 端到端冒烟 `smoke-report-review-chain.sh`

✅ **通过 116 项，失败 0 项，跳过 1 项**（唯一跳过 = D-3 B9 CSV 魔数/表头/注入转义，需 `EXPORT_DIR`，与本卡无关）。

```
 结果：通过 116 项，失败 0 项，跳过 1 项
```
`[9/9]` 家属域段 41 条断言全绿（原始日志 /tmp/d4-smoke.log），含 ★R-2 9.4b/9.6b/9.7b、★C1（FAMILY 打 approve/user-binding → 20003）、logout①–⑥。

---

## §3 突变测试（10 条 · 逐条：改动 → 命令 → 原始失败输出 → 还原确认）

突变纪律：每改一处立刻跑、立刻还原；**还原用文件备份 + md5 校验**（D-4 工作成果未提交，**严禁 `git checkout`**）。10 条全部执行，**全部变红**，全部 `RESTORE_OK`（md5 与原始一致）。

| # | 改动 | 期望靶点 | 结果 |
|---|---|---|---|
| M1 | `DataScopePermissionHandler.ORG_VIA_PARENT` 摘 `elder_family_bind`（→ `Map.of()`） | `orgScopeAnchorsElderFamilyBindViaElderOrgId` | ✅ 红 |
| M2 | `SELF_VIA_PARENT` 摘 `elder_family_bind` | `selfScopeAnchorsElderFamilyBindViaElderUserId` | ✅ 红 |
| M3 | 摘 `familyBindingApprove` 的 `@RequiresPermission` | `ControllerAuthorizationCoverageTest`（裸奔） | ✅ 红 |
| M4 | 摘 `OpenApiContractTest.CONTROLLERS` 的 `FamilyBindingController` | 双向漂移断言 | ✅ 红 |
| M5 | `unbind` 的 `logical = OR` 改回默认 `ALL` | 单测 + **真实 HTTP** | ✅ 红（双证） |
| M6 | （不改码）无令牌 / FAMILY 越权 | 401+10002 / 200+20003 | ✅ 正证 |
| M7 | 删 `FamilyBindingVerifyMapper` 的 `@InterceptorIgnore` | 单测 + **真实 HTTP 申请恒 10004** | ✅ 红（双证，★C5 现场重现） |
| M8 | 往该 Mapper 加返回 `String` 的方法 | 「只返标量」3 条断言 | ✅ 红 |
| M9 | `apply` 复用分支改回直接 `INSERT` | 单测 + **真实 HTTP**（应 `10005` 非 `code=0`） | ✅ 红（双证） |
| M10 | 摘 `FamilyBindingController` 的 `@SuppressFBWarnings(EI_EXPOSE_REP2)` | SpotBugs 报 1 个 `EI_EXPOSE_REP2` | ✅ 红 |

### 逐条证据（原始失败输出节选）

**M1** `mvn -o -pl yl-modules/yl-module-account test -Dtest=DataScopePermissionHandlerTest`（Spotless/Checkstyle 跳过）
```
[ERROR] Tests run: 22, Failures: 0, Errors: 2, Skipped: 0
[ERROR]   DataScopePermissionHandlerTest.orgScopeAnchorsElderFamilyBindFailClosedWithoutOrg:292 NullPointer "expr" is null
[ERROR]   DataScopePermissionHandlerTest.orgScopeAnchorsElderFamilyBindViaElderOrgId:282 NullPointer "expr" is null
RESTORE_OK
```

**M2** 同上命令
```
[ERROR] Tests run: 22, Failures: 0, Errors: 1, Skipped: 0
[ERROR]   DataScopePermissionHandlerTest.selfScopeAnchorsElderFamilyBindViaElderUserId:307 NullPointer "expr" is null
RESTORE_OK
```

**M3**（`install archive` 后）`mvn -o -pl yl-bootstrap test -Dtest=ControllerAuthorizationCoverageTest`
```
[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0
[ERROR]   ControllerAuthorizationCoverageTest.everyHandlerEndpointDeclaresPermission:97
  存在未声明权限码的端点（裸奔）：[FamilyBindingController#approve] ==> expected: <[]> but was: <[FamilyBindingController#approve]>
RESTORE_OK（随后重装 archive 为原始版本）
```

**M4** `mvn -o -pl yl-bootstrap test -Dtest=OpenApiContractTest`
```
[ERROR] Tests run: 6, Failures: 1, Errors: 0, Skipped: 0
[ERROR]   OpenApiContractTest.declaredImplementedOperationsMatchControllersExactly
RESTORE_OK
```

**M5**（`logical=OR` → `ALL`）
- 单测：`FamilyBindingControllerTest.unbindUsesOrLogicalWithSensitiveCode` → **FAILURE**（Tests run: 15, Failures: 1）。
- 真实 HTTP（突变 jar 重启后）：`POST /api/v1/family/bindings/1/unbind`（FAMILY）
```
HTTP=200 body={"code":20003,"message":"角色无权执行该操作",...}   # ALL 语义下 FAMILY 无 UNBIND 码 → 第二闸即挡
RESTORE_OK
```

**M6**（不改码，正证 —— 真实 HTTP）
```
无令牌 GET /family/bindings            → HTTP=401 body={"code":10002,...}
FAMILY POST /family/bindings/1/approve → HTTP=200 body={"code":20003,...}
FAMILY POST /elders/1/user-binding     → HTTP=200 body={"code":20003,...}
```

**M7**（删 `@InterceptorIgnore`）
- 单测：`FamilyBindingVerifyMapperTest.mapperIsExemptedFromDataPermissionThirdGate` → **FAILURE**（Tests run: 4, Failures: 1）。
- 真实 HTTP（突变 jar 重启后，FAMILY 用「真实存在的老人 + 正确姓名/身份证」申请）：
```
HTTP=200 body={"code":10004,"message":"资源不存在",...}
服务端 SQL 日志（★C5 现场重现）：
  SELECT COUNT(*) FROM elder WHERE id=? AND name_hash=? AND id_card_hash=? AND deleted=0
    AND (elder.id IN (SELECT b.elder_id FROM elder_family_bind b WHERE b.family_user_id=900000000000000004
         AND b.status=2 AND b.deleted=0 AND (b.auth_end_at IS NULL OR b.auth_end_at>NOW())))
  → 申请前子查询恒空 → 核验恒 0 → 10004
RESTORE_OK
```

**M8**（新增返回 `String` 的方法）
```
[ERROR] Tests run: 4, Failures: 3, Skipped: 0
[ERROR]   mapperOnlyExposesScalarReadMethods / allMethodsReturnScalarsOnly / noMethodReturnsPersonallyIdentifiableOrEntityTypes
RESTORE_OK
```

**M9**（复用分支 → 直接 INSERT）
- 单测：`applyReusesUnboundRowAndResetsToPending` + `applyReusesRejectedRowAndResetsToPending` → **FAILURE**（Tests run: 19, Failures: 2）。
- 真实 HTTP（突变 jar 重启后）：申请→拒绝→重新申请
```
apply  → code=0 id=2104979380169322498 status=0
reject → code=0 status=3
重新申请 → HTTP=200 body={"code":10005,"message":"该老人已存在绑定关系（含申请中/已生效）",...}   # 正常应 code=0
RESTORE_OK
```

**M10**（摘 `@SuppressFBWarnings`）
```
[INFO] BugInstance size is 1
[ERROR] Medium: new cn.yl.modules.archive.controller.FamilyBindingController(FamilyBindingService, FamilyReportQueryService)
        may expose internal representation ... EI_EXPOSE_REP2  At FamilyBindingController.java:[line 59]
[INFO] BUILD FAILURE
RESTORE_OK
```
> 结论：该抑制**不是"多余抑制"**——去掉后 SpotBugs 立即报 `EI_EXPOSE_REP2`（P2 反向缺陷**不成立**）。

### 还原确认（突变后逐文件 md5 == 原始）
```
  OK  .../account/security/DataScopePermissionHandler.java
  OK  .../archive/controller/FamilyBindingController.java
  OK  .../archive/service/FamilyBindingService.java
  OK  .../archive/mapper/FamilyBindingVerifyMapper.java
  OK  .../bootstrap/contract/OpenApiContractTest.java
RESTORE_OK
```
另经符号级残留检查：`ORG_VIA_PARENT = Map.of()`/`SELF_VIA_PARENT = Map.of()` = 0 处；`findNameEnc` = 0 处；`@InterceptorIgnore(dataPermission="1")` 在位（L39）；`resetReusableBind(existing…` 在位（L169）；`logical = Logical.OR` 在位（L117）；`ACCOUNT_ORG_MANAGE` 在位（L91）。

---

## §4 独立功能核查（真实 HTTP + 真实 MySQL）

### §4.1 数据域三锚现场复核（服务端 SQL 日志逐字）
| 登录态 | 表 | 渲染片段（原始） | 判定 |
|---|---|---|---|
| FAMILY(5) | `elder_family_bind` | `(elder_family_bind.family_user_id = 900000000000000004)` | ✅ |
| ELDER(1) | `elder_family_bind` | `(elder_family_bind.elder_id IN (SELECT p.id FROM elder p WHERE p.user_id = 900000000000000003))` | ✅ ★F1 |
| ORG_ADMIN(2) | `elder_family_bind` | `(elder_family_bind.elder_id IN (SELECT p.id FROM elder p WHERE p.org_id IN (1)))` | ✅ ★F3 |
| FAMILY(5) | `eval_report` | `(r.elder_id IN (SELECT b.elder_id FROM elder_family_bind b WHERE b.family_user_id = 900000000000000004 AND b.status = 2 AND b.deleted = 0 AND (b.auth_end_at IS NULL OR b.auth_end_at > NOW())))` | ✅ |

### §4.2 越权 + scope=5 承重语义
- 他人/不存在 `relationId` 读报告 → **10004**。
- **注入「同老人、他人 `family_user_id`(…099, status=2)」行后**：FAMILY `list` 中该 `elderId` 仅 **1** 条（本人），SQL 显示该 elder 有 2 条 `deleted=0` 行 → **确认 scope=5 按 `family_user_id` 过滤，未顺带读到同老人其他家属行**（承重语义成立）。注入行已清理。

### §4.3 R-1（`authEndAt` 收窄）+ R-1b（1:1）
- 请求体带 `authEndAt` → **`code=0`**（Jackson 忽略未知字段；非 400/10001）；`elder.user_id` 落库 = 目标账号；`elder` 表 `auth_end_at` 列数 = **0**；`updated_at` 确被更新。→ **不落库成立；无 `auth_end_at` 列成立**。
- ① 同一 `userId` 绑第二个老人 → **10005**；② 已绑定老人再绑其他 `userId` → **10005**（均非 500）。

### §4.4 R-2 五态 + 复用活性行（`EA` 有账号 → 0）
```
申请(有账号) → code=0 status=0；重复申请 → 10005
老人 reject → code=0 status=3
拒绝后重新申请 → code=0 id=2104975870744739842 status=0 rejectReason=[]   # id 不变、状态复位、原因清空
重复申请(pending) → 10005；orgadmin approve → code=0 status=2；重复申请(2) → 10005
unbind(带凭据) → code=0；解绑后重新申请 → code=0 id=2104975870744739842 status=0
SQL 同对 deleted=0 行数 = 1 ；同对历史行总数 = 1（复用不新增）
```

### §4.5 ④闸（`unbind`）
```
缺 X-Second-Verify-Token → 10008
带凭据 → code=0
同一凭据二次使用 → 10008（一次性、已消耗）
业务失败(unbind 非生效行 status=4)→10004；业务失败后同一凭据再用 → 10008（凭据不退还，CR-M2-002 §8.7 口径成立）
```

### §4.6 `authLogout` 六步 + 黑名单 TTL
```
logout 后旧 access → HTTP=401 code=10002
redis TTL(yl:bl:at:<sha256>) = 7195~7196s（>0；≈ accessTtl 2h，与「剩余有效期」口径一致）
```
（六步①②③④⑤⑥ 亦由冒烟全绿，见 §2.4。）

### §4.7 `@Audit` 留痕（`audit_log`，`sensitive` 列加反引号）
```
ELDER_USER_BIND      ELDER              sensitive=0
FAMILY_BIND_APPLY    ELDER_FAMILY_BIND  sensitive=0
FAMILY_BIND_APPROVE  ELDER_FAMILY_BIND  sensitive=0
FAMILY_BIND_REJECT   ELDER_FAMILY_BIND  sensitive=0
FAMILY_BIND_UNBIND   ELDER_FAMILY_BIND  sensitive=1   ← 解绑敏感留痕成立
```
5 类动作齐备；解绑 `sensitive=1` 正确。

---

## §5 缺陷清单（分级 P0/P1/P2）

**P0：无。P1：无。**

### P2 / 观察项

- **O-1【P2 · 语义观察】`authEndAt` 未知字段被静默忽略（Jackson）而非"校验拒绝"**。契约已删除该字段、DTO 已删字段；但客户端若仍携带 `authEndAt`，服务端**静默丢弃并返回 `code=0`**（未报 10001/400）。这与设计 §1.8「不接收、不落库、不告警」一致，但「调用方以为有效期已生效、实际为永久」的**误用面**依旧存在（即残留 D4-R9 的边界）。
  *影响*：低（契约已收窄；属契约/文档级）。*建议*：如上行严格口径，可给 `UserBindingRequest` 加 `@JsonIgnoreProperties(ignoreUnknown=false)` 或契约标 `additionalProperties:false`，使多余字段显式 `10001`。**非阻断。**
- **O-2【P2 · 已排除的反向缺陷】`@SuppressFBWarnings(EI_EXPOSE_REP2)` 是承重抑制，不是多余抑制**。M10 证明去掉即报 `EI_EXPOSE_REP2`（BugInstance size 1）。→ 该抑制合法、必要（构造器注入 Spring 无状态单例的经典误报）。**登记为"已验证、非缺陷"。**

> 附：无「未复现/可疑」缺陷——所有断言均可复现（见 §3/§4 原始输出）。

---

## §6 残留与口径核对（登记 vs 事实）

| 残留 | 登记口径 | QA 现场核对 | 结论 |
|---|---|---|---|
| **D4-R7** | ELDER(scope=1) 读不到自己的 `eval_report`（恒假）；**E2E 不得声称已覆盖报告读取** | ELDER 经 `GET /family/bindings/{relationId}/reports` 访问：SQL 渲染 `... AND (1 = 0)` → `total=0`、`code=0`（**读不到数据**） | ✅ 与登记一致；**`D4-e2e-checklist.md` §5.5 明确声明不覆盖，无虚假覆盖** |
| **D4-R1** | `auth_end_at` 到期不流转 `status`，仅第三闸 fail-closed | 置某绑定 `auth_end_at=2020-01-01`（过期）后：DB `status` **仍=2**（无 `@Scheduled` 流转）；读 `reports` → **10004**（fail-closed） | ✅ 与登记一致 |
| **★C1（approve 权限码）** | 必须是 `account:org:manage`（**非** `account:family:bind`） | 源码 `@RequiresPermission(PermissionCode.ACCOUNT_ORG_MANAGE)`（L91）；运行时 FAMILY 打 approve → **200+20003** | ✅ 已修，运行时钉住 |
| **★C5（唯一注解级第三闸豁免）** | 全仓唯一；`InterceptorIgnore` 仅命中本 Mapper 与其测试 | `grep -rl InterceptorIgnore --include=*.java` **仅 2 文件**（`FamilyBindingVerifyMapper.java` + 其测试） | ✅ 唯一性成立 |
| **D4-R3 / R4 / R5 / R6 / R8 / R9** | 见设计 §11 / BACKLOG | 本卡未实现、非本卡范围（`elder_authorization` 未接线、`proof_cos_key` 只存键、短信核身未实现、未写 `consent_record`、密钥轮换未实现、绑定无有效期） | 未逐条复跑，**与"未实现/残留"登记一致**（见 §7） |

---

## §7 未覆盖项（诚实列出 —— 本 QA 未能/未验证的东西）

1. **T09 端侧切真 / H5 E2E**：按卡边界**不做**（weixin/douyin/ios 三端 `family.ts`/`real-api.ts`/页面 md5 同步未验证）。
2. **家属读「真实报告数据」的 HTTP 端到端**：冒烟与我的 HTTP 复核在「无已发布报告」下得 `code=0` 空列表；`reportDetail`/`trend`/`carePlan` 含**真实行数据**（维度得分、`items_json` 非平凡内容、`levelName` 初/终判回退）**仅有单测覆盖**，我未在真实库造完整 `eval_order→eval_report(published)→report_dimension_score→care_plan` 链并 HTTP 读取逐字段比对。
3. **`items_json` 的非法 JSON / 空串 / 非数组渲染**：单测覆盖，我未做 HTTP 层注入验证。
4. **黑名单 TTL 与「剩余有效期精确相等」**：我观测 TTL≈7195s（≈默认 accessTtl 2h）；未用短 TTL 令牌验证「TTL == `exp - now`」的精确等式（无短时令牌签发途径）。
5. **M3 的 HTTP 表现**：M3 仅以单测（覆盖守卫）证明变红；未为 M3 再做一次「突变 jar 重启 + 真实 HTTP 观察 approve 裸奔」的端到端复现（M5/M7/M9 已示范该法可行）。
6. **M6 的 `approve` 用 id=1（不存在）**：因第二闸先于服务执行，20003 已在第二闸获得；未再补「用真实待审绑定 id」的 FAMILY approve 断言（冒烟 9.6 已用真实 `REL2` 覆盖）。
7. **第二验证凭据的其它失败路径**（如错误口令 → ?、过期凭据 → ?）：未逐一枚举；仅验证「缺失→10008 / 有效→0 / 一次性与不退还」。
8. **`ORG_ADMIN` 多机构（`orgIds` 多值）与跨机构负例**：三锚仅以单机构渲染片段验证；未构造第二个机构做跨机构绑定读/审的负例。
9. **`elder` 转机构后反查锚一致性**：属设计零 DDL 论证的推论，未做数据迁移场景验证。
10. **并发/幂等竞争**：`@Idempotent` 的并发去重仅由既有基建保证，我未做并发压测。
11. **真实报告 PDF / 导出 CSV（B9）**：B9 因缺 `EXPORT_DIR` 被冒烟跳过，未复跑（与本卡无关）。

---

## §8 原始日志索引（本报告证据出处）

| 文件 | 内容 |
|---|---|
| `/tmp/d4-mvn-verify.log` / `/tmp/d4-mvn-verify2.log` | 四门禁①门禁（首轮 / 突变还原后回归轮） |
| `/tmp/d4-schema.log` | 门禁② 83 项 + 金丝雀 = 1 |
| `/tmp/d4-smoke.log` | 门禁④ 116/0/1 |
| `/tmp/d4-mut/M1.log … M10.log` | 10 条突变原始输出 |
| `/tmp/d4-mut/bak/ORIG.md5` | 突变前五文件 md5（还原比对基线） |
| `/tmp/d4-run/service*.log` | 服务端 SQL 日志（三锚 / ★C5 改写现场） |
