# D-4 家属域 · 端到端验证清单（E2E Checklist）

> 角色：工程师（Alex）｜依据：`docs/design/D4-family-domain-design.md`、`docs/design/D4-class-diagram.mermaid`、
> `docs/design/D4-sequence-diagram.mermaid`、`docs/reviews/D4-foundation-check.md`
> 范围：`yl-server` 家属域 **11 操作**（家属端 9 + `authLogout` + 新增 `POST /elders/{elderId}/user-binding`），
> 跨 `yl-module-archive`（10）/ `yl-module-account`（1）/ `yl-api` / `yl-common` / `yl-infrastructure`。

---

## 0. 判定口径（务必先读）

- 本项目业务失败一律 **HTTP 200 + 响应体 `R.code`**（`GlobalExceptionHandler#handleBiz` 无 `@ResponseStatus`）。
  **拒绝的证据是 `R.code != 0`，绝不以 HTTP 200 当"放行"证据（恒真、假绿）**。
- 唯一例外：**未携带凭据**（Spring Security 过滤器链）→ **HTTP 401 + `R.code = 10002`**；`authLogout` 后旧 access 亦走此路径
  （`JwtAuthFilter` 命中黑名单短路）。须**同时**校验状态码与业务码。
- **★C1**：`familyBindingApprove` 权限码已**本体订正**为 `account:org:manage`（ORG_ADMIN 独占）⇒ FAMILY 打 approve 在**第二闸即被挡** →
  **HTTP 200 + `R.code=20003`**（`ROLE_NOT_PERMITTED`）。该断言**直接钉住 C1 修复**。
- **★C3**：ELDER 段覆盖 `reject` + `GET /elders` 自读；**ELDER E2E 不覆盖报告读取**（D4-R7：ELDER 无关系级链路，恒假）。

---

## 1. 环境前置（★C4 启动前言，逐字照抄）

| 项 | 值 / 命令 |
|---|---|
| MySQL 8 | `C:\mysql`（不在 PATH）：`MYSQL_BIN=/c/mysql/bin/mysql.exe`，root 口令 `Mysql8@2026#Dev` |
| Redis | `C:\redis`，6379 无密码（启动时须 `REDIS_PASSWORD=''`） |
| 敏感字段密钥 | `SENSITIVE_FIELD_KEY` / `SENSITIVE_FIELD_HASH_KEY`，**Base64 且 ≥32 字节**（D-4 申请核验 `hashForSearch` 必需；**缺失 → 申请 `40001`**） |
| 二次验证口令 | `SECOND_VERIFY_DEV_CODE`（解除绑定 `account:family:unbind` 敏感点必需） |
| dev 账号 | `assessor_dev`（ASSESSOR）/ `orgadmin_dev`（ORG_ADMIN）/ **`elder_dev`（ELDER）/ `family_dev`（FAMILY）**；口令由环境变量 **`DEV_LOGIN_PASSWORD`** 注入（**不落任何已跟踪文件**） |

> **★C4 前言（缺任一 → 预期内失败，非缺陷）**：D-4 新增端到端链**强依赖**以下启动前置——
> ① `SENSITIVE_FIELD_KEY` + `SENSITIVE_FIELD_HASH_KEY`（否则 `POST /family/bindings` 核验前即 `40001`，**零 insert**）；
> ② `unset SERVER__PORT`（宿主注入会覆盖 `server.port`）；
> ③ `REDIS_PASSWORD=''`（本机 Redis 无 AUTH，否则启动 AUTH 失败）；
> ④ `DB_PASSWORD`（连库）。
> **若缺失 `SENSITIVE_FIELD_KEY`，申请端点返回 `40001` 属预期内**（见 §4 残留 R-**）。

启动后端（三个坑必须绕开）：

```sh
# ① 必须先 unset 宿主注入的 SERVER__PORT（会覆盖 server.port → "Port ... already in use"）
# ② REDIS_PASSWORD='' （本机 Redis 无密码；yml 默认会 AUTH 失败）
unset SERVER__PORT
export DB_PASSWORD='Mysql8@2026#Dev' REDIS_PASSWORD='' SECOND_VERIFY_DEV_CODE=246810 \
       SENSITIVE_FIELD_KEY="$(head -c 32 /dev/urandom | base64 -w0)" \
       SENSITIVE_FIELD_HASH_KEY="$(head -c 32 /dev/urandom | base64 -w0)"
# ④ 从 target/ 之外的 jar 副本启动（★关键）：
#    并发 `mvn verify` 的 jar:jar/repackage 会**就地替换** target/yl-bootstrap.jar；
#    运行中的实例懒加载后续类时会读到被改写的 jar → NoClassDefFoundError → 崩溃（冒烟表现为 HTTP 000）。
#    故先复制到稳定路径再启动：
mkdir -p /tmp/d4-run && cp -f yl-bootstrap/target/yl-bootstrap.jar /tmp/d4-run/
java -cp /tmp/d4-run/yl-bootstrap.jar \
     org.springframework.boot.loader.launch.JarLauncher \
     --spring.profiles.active=dev --server.port=8080
# ⑤ 复验前确认新代码真在跑：netstat -ano | grep :8080 → taskkill //F //PID <pid> → 重建 → 起新实例 → 看日志时间戳
```

> 注：本机 sandbox 会静默丢弃 `env -u ...` 形式的命令，故用 `unset` + `export` 替代。
> 注：fat-jar 用裸 `java -jar` 会秒退，必须用 `java -cp … org.springframework.boot.loader.launch.JarLauncher`。

---

## 2. 逐端点验收

### 2.1 绑定状态机（`/api/v1/family/bindings`）

| 端点 | 步骤 | 期望 | 判定 |
|---|---|---|---|
| `POST`（`familyBindingCreate`） | FAMILY 申请（老人**有**登录账号） | `code=0` 且 `status=0` | ✅ 冒烟 `[9/9]` |
| 同上 | FAMILY 申请（老人**无**登录账号） | `code=0` 且 `status=1`（★C2 判定规则） | ✅ 冒烟 `[9/9]` |
| 同上 | 核验姓名/身份证不匹配 | `code=10004`（**同码，不泄露存在性**） | ✅ 单测 |
| 同上 | 缺 `SENSITIVE_FIELD_KEY` | `code=40001`，**零 insert** | ✅ 单测 |
| 同上 | 频控超限（60s/10 次） | `code=10429` | ✅ 单测 |
| 同上 | 同对**重复**申请（`status ∈ {0,1,2}`） | `code=10005`（含 `uk_elder_family` 兜底） | ✅ 单测 + 冒烟 `[9/9]` 9.4b/9.6b |
| 同上 | **拒绝后**重新申请（`status=3`） | `code=0` 且 `status∈{0,1}`，**复用原活性行**（`id` 不变），`reject_reason` 复位 `NULL` | ✅ 单测 + 冒烟 `[9/9]` 9.4b（★R-2） |
| 同上 | **解绑后**重新申请（`status=4`） | `code=0` 且 `status∈{0,1}`，**复用原活性行**（`id` 不变） | ✅ 单测 + 冒烟 `[9/9]` 9.7b（★R-2） |
| `GET`（`familyBindingList`） | FAMILY | `code=0`，仅见 `family_user_id=me`（scope=5） | ✅ 单测 |
| `GET` | ORG_ADMIN | `code=0`，仅见 `elder.org_id IN (myOrgs)`（★F3 反查锚） | ✅ 单测 |
| `POST {id}/approve` | ORG_ADMIN，`{0,1}→2`（★C2 宽容化） | `code=0` 且 `status=2` | ✅ 冒烟 `[9/9]` |
| `POST {id}/approve` | **FAMILY**（无 `account:org:manage`） | **`code=20003`**（★C1 第二闸即挡） | ✅ 冒烟 `[9/9]` |
| `POST {id}/reject` | ELDER（第三闸经 `elder.user_id` 反查） | `code=0` 且 `status=3` | ✅ 冒烟 `[9/9]` |
| `POST {id}/unbind` | 缺 `X-Second-Verify-Token` | `code=10008`（OR 含敏感码 ⇒ 强制第四闸） | ✅ 冒烟 `[9/9]` |
| `POST {id}/unbind` | 带一次性凭据 | `code=0`（`deleted` 保持 0） | ✅ 冒烟 `[9/9]`（需 `SECOND_VERIFY_DEV_CODE`） |

### 2.2 家属读三端点（`report:view`，`x-data-scope: relation`）

| 端点 | 步骤 | 期望 | 判定 |
|---|---|---|---|
| `GET {relationId}/reports` | 生效绑定（`status=2`） | `code=0`，仅 `eval_report.status=1`（已发布），倒序 | ✅ 冒烟 + 单测 |
| `GET {relationId}/reports/{reportId}` | 本人绑定 + 本人老人报告 | `code=0`（维度得分 + 照护条目） | ✅ 单测 |
| 同上 | `reportId` 越界 / 未发布 / 不属该老人 | `code=10004`（**同码**） | ✅ 单测 |
| `GET {relationId}/trend` | 生效绑定 | `code=0`（`published_at` 升序；`levelName` 优先终判回退初判） | ✅ 冒烟 + 单测 |
| `GET {relationId}/care-plan` | 有已确认方案 | `code=0`，`items` 非空 | ✅ 单测 |
| 同上 | **无已确认方案** | `code=0` + **空方案**（`planId=null`、`items=[]`，**不返 10004**） | ✅ 冒烟 + 单测 |
| 全部 4 端点 | 他人 / 不存在 `relationId` | `code=10004`（**同码，不泄露存在性**） | ✅ 冒烟 + 单测 |
| 全部 4 端点 | 未生效（`status!=2`）/ 已过期（`auth_end_at<now`） | `code=10004` | ✅ 单测 |
| `items_json` 解析 | 空 / 非法 JSON / 非数组 | `items=[]`（**不造数**，仅告警日志） | ✅ 单测 |

### 2.3 `POST /api/v1/elders/{elderId}/user-binding`（`account:org:manage`）

| 步骤 | 期望 | 判定 |
|---|---|---|
| ORG_ADMIN 绑定本机构账号 | `code=0`（写 `elder.user_id`） | ✅ 冒烟 `[9/9]` |
| 请求体只含 `userId`（**不收 `authEndAt`**，R-1 收窄契约） | 多余字段不参与绑定；`elder` 无 `auth_end_at` 列 | ✅ 契约 + 单测 |
| **FAMILY** 打（无该权限码） | **`code=20003`**（★C1 附加断言） | ✅ 冒烟 `[9/9]` |
| 目标账号非本机构 | `code=20003` | ✅ 单测 |
| `elderId` 越界 / 不存在（第三闸） | `code=10004` | ✅ 单测 |
| ② 第二个账号绑**已绑定老人**（占用判） | `code=10005` | ✅ 单测 `bindSecondUserToAlreadyBoundElderReturnsConflict` |
| ① **同一账号绑第二个老人**（`uk_elder_user` 冲突） | `code=10005`（**绝不 500**） | ✅ 单测 `bindSecondElderToSameUserReturnsConflict` |

### 2.4 `POST /api/v1/auth/logout`（`authLogout`，无权限码；§1.3 六步）

| 步 | 步骤 | 期望 | 判定 |
|---|---|---|---|
| ① | `login(family_dev)` | 得 `A`(access) + `R`(refresh) | ✅ 冒烟 `[9/9]` |
| ② | `GET /elders` with `A` | HTTP 200 + `code=0`（`A` 有效） | ✅ 冒烟 `[9/9]` |
| ③ | `POST /auth/logout` with `A` + `{refreshToken:R}` | HTTP 200 + `code=0` | ✅ 冒烟 `[9/9]` |
| ④ | `GET /elders` with `A` | **HTTP 401 + `code=10002`**（`A` 入黑名单，`JwtAuthFilter` 短路） | ✅ 冒烟 `[9/9]` |
| ⑤ | `POST /auth/refresh` with `{refreshToken:R}` | HTTP 200 + **`code=10002`**（`R` 已 revoke） | ✅ 冒烟 `[9/9]` |
| ⑥ | 无 `Authorization` 访问 `/elders` | **HTTP 401 + `code=10002`**（CR-M2-007 回归） | ✅ 冒烟 `[9/9]` |

> 黑名单键 `yl:bl:at:<sha256Hex(token)>`（**不存原令牌**）；TTL = 令牌剩余有效期；`refresh` 走既有 `yl:rt:` `revoke`。

---

## 3. 一键冒烟

```sh
unset SERVER__PORT
MYSQL_BIN=/c/mysql/bin/mysql.exe MYSQL_PASSWORD='Mysql8@2026#Dev' \
  SECOND_VERIFY_DEV_CODE=246810 \
  SENSITIVE_FIELD_KEY="$(head -c 32 /dev/urandom | base64 -w0)" \
  SENSITIVE_FIELD_HASH_KEY="$(head -c 32 /dev/urandom | base64 -w0)" \
  bash scripts/smoke-report-review-chain.sh
```

> ★ 冒烟脚本内的后端须以**同一组** `SENSITIVE_FIELD_KEY/*_HASH_KEY` 启动（见 §1 前言），否则申请核验 `40001`。

> 断言数随环境变量变化（`MYSQL_BIN/MYSQL_PASSWORD`、`SECOND_VERIFY_DEV_CODE`、`EXPORT_DIR`、`[7/7] ELDER_ID`），**勿硬编码总数**：
> 未设 `SECOND_VERIFY_DEV_CODE` → 解绑带凭据 1 跳过；未设密钥/`ELDER_ID` → 家属域整段相应子段跳过。

---

## 4. 四项门禁实测（2026-09-30，D-4a 收尾）

| 门禁 | 命令 | 实测（2026-09-30） |
|---|---|---|
| 单测 + 静态检查 | `mvn -o verify` | **255 通过 / 0 失败**（D-4a 收尾 247→255，+8 新断言；≥185 达标）；Checkstyle **0** violations（14 模块）；SpotBugs `BugInstance size is 0`（9 模块）；**BUILD SUCCESS** |
| 结构门禁 | `bash scripts/verify-schema.sh` | **83 通过 / 0 失败 / 0 待批准守卫**（**零 DDL**，保留字金丝雀 1） |
| 契约门禁 | `bash scripts/verify-openapi.sh` | **8 通过 / 0 失败**；契约操作 44（implemented **44** / planned **0**），双向漂移 **0** |
| 端到端冒烟 | `bash scripts/smoke-report-review-chain.sh` | **116 通过 / 0 失败 / 1 跳过**（D-4a 收尾 108→116，+8 条 ★R-2 断言；`[9/9]` 41 条全绿；唯一跳过 = D-3 B9 CSV 魔数/表头/注入转义，需 `EXPORT_DIR`） |

> **★ 冒烟实测前置**：后端须从 `target/` **之外**的 jar 副本启动（见 §1 ④），否则并发 `mvn verify` 替换 fat jar 会使运行实例崩溃 → 冒烟出现 HTTP `000` 假红。

---

## 5. 残留与不可达项（须在验收说明中显式声明）

1. **R-1（已裁决，设计侧已订正）`elder` 表无 `auth_end_at` 列**：设计早期 §1.2/§2.2/§3.1 曾要求 `user-binding` 写
   `user_id + auth_end_at`，但实读 `02_schema.sql` **无此列**；本卡零 DDL ⇒ **收窄契约**：`UserBindingRequest` **只含 `userId`**（**删** `authEndAt`：不接收、不落库、不告警），只写 `elder.user_id`。
   老人↔账号为 **1:1**（`elder.uk_elder_user` 唯一），冲突一律 **`10005`、绝不 500**。「有效期」的合法承载是 `elder_authorization`（ER-07），本卡明令不接（D4-R3）。
2. **★C2 五态出口 `4`（到期自动解绑）无承载端点**：`auth_end_at < now` 的到期转移无 `@Scheduled`（ADR-0004），安全性由第三闸子查询
   **fail-closed** 保证（过期绑定在 `requireMyActiveBinding` 与数据域子查询双重判假）→ 登记 **D4-R1**。
3. **`status=0`（待老人确认）无「老人确认」端点**：D-1 裁决 5 老人侧仅 `:reject`；正因如此 `approve` 认 `{0,1}→2`（★C2）。
4. **R-2（已裁决，设计侧已订正）重新申请复用活性行**：`active_uk = IF(deleted=0, 1, NULL)` ⇒ **只有 `deleted=1` 让位**（`NULL` 在唯一索引中互不相等，故同一老人可保留多条历史解绑记录）。
   `create` 对同对 `deleted=0` 活性行分派：**无** → INSERT（落态按 `hasLoginAccount`）；**`status ∈ {0,1,2}`** → `10005`；**`status ∈ {3,4}`** → **复位复用**（`status`→0/1、`reject_reason`→NULL、`verify_method`/`proof_cos_key` 覆盖为新值；`auth_start_at`/`auth_end_at` **不动**），**不新增行**。
   ⇒ 拒绝/解绑后重新申请返回 **`code=0` 且 `status ∈ {0,1}`**（不是 `10005`）；仅 `status ∈ {0,1,2}` 重复申请才 `10005`。`unbind` 保持 `status=4`/`deleted=0`；**`deleted=1` 只表软删，不得用来表达业务状态**。
5. **★C3：ELDER E2E 不覆盖报告读取**（D4-R7 恒假）：ELDER 无关系级链路，`report:view` 对 ELDER 仅经数据域恒假收口。
6. **★C4：`SENSITIVE_FIELD_KEY` 缺失时申请端点 `40001` 属预期内**（非缺陷）：见 §1 前言。
7. **`elder_dev` 的 `elder.user_id` 归属绑定不在 04 种子内**：经 `POST /elders/{elderId}/user-binding` 在建档后回填（设计 §T07 许可二选一），
   规避「明文落密文列」与「解密失败 500」。
8. **【环境】并发 `mvn verify` 替换 fat jar 会击穿运行中的后端**：fat jar 被就地替换 → 运行实例懒加载类失败崩溃（HTTP `000`）。
   **规避**：后端从 `target/` 之外的 jar 副本启动（§1 ④）。此非代码缺陷，但会污染冒烟结果，须先规避再复验。
9. **【环境】8080 端口 / 实例争用**：曾观测到外部实例重启占用 8080 使冒烟中断；复验建议用自包含循环
   （清 java → 起副本 → 冒烟 → 保留实例），或在无人并行起服务的时间窗内执行。

---

## 6. QA 突变自检（删一处应全红）

> 下表为**工程师自检**结果；正式验收建议由独立 QA 复跑一遍。

| # | 突变 | 期望变红 | 自检结果 |
|---|---|---|---|
| 1 | 从 `DataScopePermissionHandler.SELF_VIA_PARENT` 删 `elder_family_bind` | `DataScopePermissionHandlerTest#selfScopeAnchorsElderFamilyBindViaElderUserId` | ✅ T01 已验（先红后绿） |
| 2 | 从 `ORG_VIA_PARENT` 删 `elder_family_bind` | `DataScopePermissionHandlerTest#orgScopeAnchorsElderFamilyBindViaElderOrgId` | ✅ T01 已验（先红后绿） |
| 3 | `approve` 权限码由 `account:org:manage` 改回 `account:family:bind` | `FamilyBindingControllerTest#approveRequiresOrgManage` + 冒烟 FAMILY 打 approve | ✅ 单测已钉死（★C1） |
| 4 | 移除 `JwtAuthFilter` 黑名单短路 | `JwtAuthFilterTest#blacklistedTokenShortCircuitsToUnauthorized` | ✅ 单测已钉死 |
| 5 | 把某 D-4 操作 `x-status` 由 `implemented` 翻回 `planned` | `verify-openapi.sh` 双向漂移 | ✅ 实测（T02 翻旗后曾现 11 漂移，T03–T06 收敛为 0） |
| 6 | 从 `FamilyBindingVerifyMapper` 删 `@InterceptorIgnore(dataPermission="1")` | `FamilyBindingVerifyMapperTest#mapperIsExemptedFromDataPermissionThirdGate` + 冒烟「家属申请」 | ✅ T08 已验（无注解时申请恒 `10004`；补注解后 `code=0`） |
| 7 | 从 `FamilyReportQueryService.requireMyActiveBinding` 删「`status!=2` → 10004」 | `FamilyReportQueryServiceTest`（未生效/过期 → 10004） | ✅ 单测已钉死 |
| 8 | 往 `FamilyBindingVerifyMapper` 接口**新增**任意方法（如返回 `Elder`/`String` 的查询） | `FamilyBindingVerifyMapperTest#mapperOnlyExposesScalarReadMethods`（**★C5** 方法集合 ⊆ `{countExactMatch, hasLoginAccount}`）+ `#allMethodsReturnScalarsOnly` | ✅ D-4a 收尾新增（钉死"接口级、全方法生效"的豁免） |
| 9 | 把 `FamilyBindingService.apply` 复用分支改回「查重即 `10005`」（不按 status 分派） | `FamilyBindingServiceTest#applyReusesRejectedRowAndResetsToPending` / `#applyReusesUnboundRowAndResetsToPending` + 冒烟 `[9/9]` 9.4b / 9.7b | ✅ D-4a 收尾新增（★R-2） |
| 10 | 复位改用 `updateById`（null 字段被忽略 → `reject_reason` 不被复位 / `SET` 不含该列） | `FamilyBindingServiceTest#applyReusesRejectedRowAndResetsToPending`（断言 `SET` 含 `reject_reason=` 且以 NULL 入参） | ✅ D-4a 收尾新增（★R-2） |

复现命令（跑完须还原）：

```sh
# 3/4：改控制器注解 / 过滤器后
mvn -o -q -pl yl-bootstrap -am test -Dtest=ControllerAuthorizationCoverageTest -Dsurefire.failIfNoSpecifiedTests=false
# 5：改 openapi x-status 后
bash scripts/verify-openapi.sh
```
