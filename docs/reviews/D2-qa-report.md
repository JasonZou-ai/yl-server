# D-2 档案域后端 · 独立 QA 验证报告

> 角色：QA 工程师（严过关）｜类型：**独立证伪式验证**（源码非我所写、非团队我写）
> 被测：`yl-server` 档案域 4 端点翻旗 + CR-M2-007 未认证修复 + `elder.user_id` 自见锚列
> 基线：`openapi/yl-api.yaml` v1.1.1 ｜ 实现记录：`docs/change/D2-archive-domain-implementation.md`
> 日期：2026-09-26 ｜ 环境：Windows + JDK 17.0.20.1 + Maven 3.9.16 + MySQL 8.0.46（本地便携版）+ Redis

---

## TL;DR

**放行（NoOne）** —— A/B/C/D 四组断言**全部实测通过**；**三条受控突变测试全部"红→复原→绿"**（守卫具真实区分力）；未发现源码缺陷。

一句话理由：**最重要的反向证伪（CR-M2-007 第二闸未被误吞）实测得到 `HTTP 200 + code=20003`（而非 403）**，且三条门禁突变均能变红——门禁不是"看着绿"，是真有区分力。

- 缺陷数：**0**
- 突变测试：**3/3 全部红（复原后全绿）**
- 全量回归：`mvn -o verify` BUILD SUCCESS（148 例 / 0 失败）、`verify-openapi.sh` 8/8、`verify-schema.sh` 82/82、HTTP 冒烟 53/53/0
- 未覆盖：见文末 §6（诚实声明）

---

## 0. 被测环境与"新 jar"证据（本项目踩过的坑：旧进程锁 jar → 假失败）

启动方式（sandbox 会静默丢弃 `env -u ...`，故按任务书改用 `unset` + 显式 launcher）：

```sh
unset SERVER__PORT
export DB_PASSWORD='Mysql8@2026#Dev' REDIS_PASSWORD='' \
       SENSITIVE_FIELD_KEY='<base64 32B>' SENSITIVE_FIELD_HASH_KEY='<base64 32B>' \
       SECOND_VERIFY_DEV_CODE=246810
java -cp yl-bootstrap/target/yl-bootstrap.jar \
     org.springframework.boot.loader.launch.JarLauncher \
     --spring.profiles.active=dev --server.port=8080
```

**"跑的是新 jar"三时间一致证据：**

| 运行 | jar mtime | 进程 PID / 启动时间 | 启动日志 `Tomcat started` | 判定 |
|---|---|---|---|---|
| A/B 实测（首次） | 2026-09-26 **10:49:34** | PID 17812 / **10:50:00** | **10:50:11** | 一致 ✓（jar 早于进程） |
| C 全量回归（终态） | 2026-09-26 **11:00:48** | PID 15776 / **11:01:31** | **11:01:42** | 一致 ✓ |

> 两次 jar 均由 QA 亲自 `mvn -o clean package` / `mvn -o verify` 产出，`find *.java -newer jar` 为空（无源码晚于 jar）。

**⚠️ 复现到工程风险（非缺陷）**：首次 `mvn -o verify` **失败**，错误为
`spring-boot-maven-plugin:repackage ... Unable to rename 'yl-bootstrap.jar' to 'yl-bootstrap.jar.original'`
——根因是**运行中的 yl-server 进程锁住了 fat jar**（正是任务书 §C 警示的坑，也印证实现记录 §七 #6）。
此时**全部 148 例单测已 0 失败**，失败只发生在最后的 repackage。`taskkill //PID 17812 //F` 后**重跑 `mvn -o verify` → BUILD SUCCESS**。
**结论**：该失败是环境/进程锁导致的假失败，不是代码回归；文档里"先停进程再 verify"的操作纪律应保持。

---

## 1. Group A —— 关键断言真伪（全部实测，拒绝"自述通过"）

> 断言口径：业务失败 = `HTTP 200 + R.code`；唯一例外是"未认证"（框架层）= `HTTP 401 + R.code`。
> **绝不以 HTTP 200 当"放行"证据。**

### A1. CR-M2-007 口径（★含最重要的反向证伪）

**A1a 未携带 `Authorization` → `GET /api/v1/elders`**
```sh
curl -s --noproxy '*' -o /tmp/qa-a1a.json -w 'HTTP=%{http_code}\n' \
  'http://127.0.0.1:8080/api/v1/elders?pageNum=1&pageSize=1'
```
```
HTTP=401
BODY={"code":10002,"message":"未登录或登录已过期","traceId":"150551fa771742dfa03670680b007607","success":false}
```
判定：**HTTP 401 + 统一响应体 `R(code=10002)`**（JSON，非 Spring 默认 HTML 错误页）。✓

**A1b 无效令牌 → `GET /api/v1/elders`**
```
HTTP=401
BODY={"code":10002,"message":"未登录或登录已过期","traceId":"2b095749ef5846978347ad1e53e814c0","success":false}
```
判定：**HTTP 401 + 10002**。✓

**A1c ★反向证伪：已登录但无权（`assessor_dev` 令牌调 `POST /api/v1/security/reveal`，`data:reveal` 仅 ORG_ADMIN 持有）**
```sh
curl -s --noproxy '*' -K <assessor-token-cfg> -o /tmp/qa-a1c.json -w 'HTTP=%{http_code}\n' \
  -X POST 'http://127.0.0.1:8080/api/v1/security/reveal' \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: qa-a1c-001' \
  -d '{"bizType":"ELDER","bizId":1,"fields":["name"]}'
```
```
HTTP=200
BODY={"code":20003,"message":"角色无权执行该操作","traceId":"dc4b5441097a4b9894a1abdd7c61e226","success":false}
```
判定：**HTTP 200 + `code=20003`，不是 403**。✓✓
→ 证明**第二闸（`PermissionAspect`→`GlobalExceptionHandler`）与第一闸（入口点）两条拒绝路径没有串台**；CR-M2-007 的修复**没有把第二闸吞成 403**。
旁证：同一 `ORG_ADMIN` 令牌（持 `data:reveal`）在补齐第四闸后可正常拿到明文（见 A3、冒烟），说明 A1c 的 20003 是**真实的权限拒绝**，而非端点不可用。

### A2. 敏感字段未明文落库

建档（`assessor_dev`，`orgId=1`）后直查库：
```sql
SELECT (name_enc <> '测试甲'), (id_card_enc <> '530102199001011234'),
       (phone_enc <> '13812345678'), (address_enc <> '云南省昆明市五华区'),
       CHAR_LENGTH(name_hash), CHAR_LENGTH(id_card_hash), IFNULL(user_id,'NULL'), org_id
FROM yl_evaluation.elder WHERE id=<E1>;
```
```
1	1	1	1	64	64	NULL	1
```
补充：`(name_enc LIKE '%测试甲%')+(id_card_enc LIKE '%530102199001011234%')+(phone_enc LIKE '%13812345678%')+(address_enc LIKE '%五华区%')` → **0**
判定：四个 `*_enc` **均不等于明文**、且**不含明文字串**；`name_hash`/`id_card_hash` 长度 **= 64**。✓
（注：`audit_log.sensitive` 为 MySQL 保留字，手写 SQL 已加反引号。）

### A3. reveal 留痕 + "失败不留成功痕"

| 步骤 | 命令/动作 | 结果 |
|---|---|---|
| 基线 | `SELECT COUNT(*) FROM audit_log WHERE action='DATA_REVEAL' AND biz_type='ELDER' AND \`sensitive\`=1;` | `0` |
| **不带** `X-Second-Verify-Token` | 管理员令牌 POST reveal | `HTTP=200` + `{"code":10008,...}` |
| 复查 audit | 同上 COUNT | **`0`（未新增）** ✓ |
| 带 `X-Second-Verify-Token`（`/auth/second-verify` + dev 码 246810） | 管理员令牌 POST reveal | `{"code":0,...,"fields":{"idCardNo":"530102199001011234","address":"云南省昆明市五华区","name":"测试甲"}}` |
| 复查 audit | `SELECT action,biz_type,\`sensitive\`,second_verify ...` | `DATA_REVEAL  ELDER  1  1` ✓ |

判定：成功**留痕**（`action=DATA_REVEAL`/`biz_type=ELDER`/`sensitive=1`/`second_verify=1`）；缺令牌**业务失败且不留痕**。✓
（附带发现：二次验证凭据为**一次性**，业务失败也会被消费——见 A4 补测，属设计预期，非缺陷。）

### A4. 越界与不存在"同码"（打印完整响应体对比）

```sql
UPDATE yl_evaluation.elder SET org_id=999 WHERE id=<E1>;   -- 临时改挂他机构
```
```
# A4a 跨机构详情
HTTP=200
{"code":10004,"message":"资源不存在","traceId":"46193168d29d4061bbf450e4875e61ce","success":false}
# A4b 不存在的 elderId=999000000000000999
HTTP=200
{"code":10004,"message":"资源不存在","traceId":"ef6aaf4ab91b4f39a260b9d4bae4ea38","success":false}
```
**还原**（`UPDATE ... SET org_id=1`）后复查：
```
HTTP=200
{"code":0,...,"data":{"id":2103679410880716802,"name":"测**","idCardMask":"5301**********1234","phone":"138****5678","orgId":1,...},"success":true}
```
判定：跨机构与不存在**同为 `10004`、message 相同**（不泄露存在性）；还原后恢复正常（证明 10004 来自数据域而非"功能坏了"）。✓ **已还原并复验**

**A4 补测（额外证伪）：reveal 是否也过第三闸？** 建档(org=1)→改挂 org=999→`ORG_ADMIN` 带**新**二次验证令牌 reveal：
```
reveal 跨机构 : HTTP=200 {"code":10004,...}   ✓（第三闸在 reveal 路径同样生效）
reveal 在域内 : HTTP=200 {"code":0,...,"fields":{"name":"测试丁"}}  ✓（换新令牌）
```
判定：reveal 的明文查询确实经 Mapper 触发数据域收口。✓

### A5. `uk_elder_user` 可空唯一键不误伤

连续建档 2 条 `user_id` 均为 NULL 的档案：**两条均 `code=0`**。
```sql
SELECT id, IFNULL(user_id,'NULL'), elder_no FROM elder WHERE id IN (<E1>,<E2>);
-- 2103679410880716802  NULL  ELD20260926803224
-- 2103679411325313026  NULL  ELD20260926092532
SELECT INDEX_NAME,COLUMN_NAME,NON_UNIQUE FROM statistics WHERE table_name='elder' AND index_name='uk_elder_user';
-- uk_elder_user  user_id  0
SELECT COUNT(*) FROM elder WHERE user_id IS NULL;   -- 2
```
判定：唯一键 `uk_elder_user` 存在且 `NON_UNIQUE=0`，但**多条 NULL 共存不冲突**（MySQL 语义正确）。✓

---

## 2. Group B —— 门禁"守卫有效性"（受控突变 + 完整复原）

> 方法：临时改源码/契约 → 跑门禁**必须变红** → **完整复原** → 再跑**必须绿**。以下三者均贴出"红/复原/绿"证据。

### B1. 突变：`OpenApiContractTest.CONTROLLERS` 删掉 `DataRevealController`
```sh
mvn -o test -Dtest=OpenApiContractTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dspotless.check.skip=true -Dcheckstyle.skip=true
```
**红：**
```
Tests run: 6, Failures: 1, Errors: 0  <<< FAILURE!
AssertionFailedError: 契约 `x-status: implemented` 与控制器端点不一致（左=代码，右=契约）。
  契约缺：[]；契约多：[POST /api/v1/security/reveal] ==> expected: <[..26..]> but was: <[..25..]>
```
**复原**（恢复该行；`git diff` 该文件 = 0 行）→ **绿：**
```
Tests run: 6, Failures: 0, Errors: 0 ... BUILD SUCCESS
```
判定：守卫有区分力。✓（附带：删行后 spotless 因"未使用 import"变红——本项目"多余豁免亦阻断"的策略生效。）

### B2. 突变：把 `dataReveal` 的 `x-status: implemented` 改回 `planned`
```sh
bash scripts/verify-openapi.sh
```
**红：**
```
[5/5] 漂移 ...
  ✗ 控制器端点数 = 26（期望 25）
  ✗ 契约缺登的已实现端点（错误路径/漏登）条数 = 1（期望 0）
    契约缺登：
      · POST /api/v1/security/reveal
契约门禁结果：通过 6 项，失败 2 项   （exit 1）
```
**复原** → **绿：**
```
契约门禁结果：通过 8 项，失败 0 项（契约操作 43，其中 implemented 26）  （exit 0）
```
判定：脚本门禁与 Java 版同源、同样有区分力。✓

### B3. 突变：`ElderController.detail` 去掉 `@RequiresPermission`
```sh
mvn -o test -Dtest=ControllerAuthorizationCoverageTest -Dsurefire.failIfNoSpecifiedTests=false ...
```
**红：**
```
Tests run: 2, Failures: 1  <<< FAILURE!
AssertionFailedError: 存在未声明权限码的端点（裸奔）：[ElderController#detail] ==>
  expected: <[]> but was: <[ElderController#detail]>
```
**复原** → **绿：**
```
Tests run: 2, Failures: 0, Errors: 0 ... BUILD SUCCESS
```
判定：注解覆盖守卫有区分力。✓

**突变复原总核**（`git diff` 均为空 → 与工程师实现的原始工作树逐字节一致）：

| 文件 | 复原后 `git diff` 行数 |
|---|---|
| `.../contract/OpenApiContractTest.java` | 0 |
| `.../controller/ElderController.java` | 0 |
| `openapi/yl-api.yaml`（`dataReveal.x-status` 已回 `implemented`） | 已回填 ✓ |
| `elder` 表 org_id 改挂 | 已还原（A4/A4补测后清库，`elder` 行数=0） |

---

## 3. Group C —— 全量回归（真跑，非抄）

| # | 门禁 | 期望 | 实测 | 判定 |
|---|---|---|---|---|
| C1 | `mvn -o verify` | BUILD SUCCESS | **BUILD SUCCESS** | ✓ |
| | ├ Checkstyle | 0 | **14 模块全部 `You have 0 Checkstyle violations`** | ✓ |
| | ├ SpotBugs | 0 | **`BugInstance size is 0` / `No errors/warnings found`**（含 yl-module-archive） | ✓ |
| | └ 单测总数 | （基线 129） | **148 例 / 0 失败 / 0 错误**：yl-api 9 + account 48 + evaluation 60 + **archive 18** + bootstrap 13 | ✓ |
| C2 | `bash scripts/verify-openapi.sh` | 8/8 | **通过 8 项，失败 0 项**（操作 43 = implemented **26** / planned **17**） | ✓ |
| C3 | `verify-schema.sh` | 82/82 | **通过 82 项，失败 0 项，待批准守卫 0 项**；含 `✓ 档案自见锚列 elder.user_id（bigint，可空） = 1`、`✓ 保留字冲突数 = 1` | ✓ |
| C4 | `smoke-report-review-chain.sh` | 53/53/0 | **通过 53 项，失败 0 项，跳过 0 项** | ✓ |

**C4 档案域段原始摘要（[7/7]）——与实现记录一致：**
```
[7/7] 档案域（D-2：建档加密 / 第三闸收口 / reveal 二次验证 + 留痕 / 未认证 401）
  ✓ 建档成功（code=0） / ✓ 姓名脱敏=张* / ✓ 身份证掩码 5301**********2946
  ✓ 列表 code=0 / ✓ 详情姓名=张*
  ✓ reveal 缺凭据 = 10008 / ✓ 带令牌明文姓名=张伟 / 明文身份证 / 明文住址
  ✓ audit_log 留痕行数 = 1
  ✓ 库内姓名/身份证为密文且摘要齐备（1 1 1）
  ✓ 未携带凭据 HTTP 状态码 = 401 / ✓ 响应体 R.code = 10002
  ✓ 越界/不存在详情 = 10004 / ✓ 跨机构详情 = 10004
==============================================================
 结果：通过 53 项，失败 0 项，跳过 0 项
```
（C3 会 **DROP 并重建库**，已按提示执行；重建后 DB 由 01~04 init 脚本重新播种，HTTP 冒烟复跑通过。）

---

## 4. Group D —— 范围不越界

| 检查 | 期望 | 实测 | 判定 |
|---|---|---|---|
| `x-status: implemented` / `planned` 计数 | 26 / 17 | **26 / 17** | ✓ |
| 除这 4 个操作外无别的操作被动 | — | D-1 收尾基线 = **implemented 22 / planned 21**（见 `D1-openapi-contract-delta.md` §309/§320）；现 **26/17**，增量恰为 **+4 implemented / −4 planned**，且总操作数恒为 43 → **只有 4 个 planned 翻旗**，无增删操作 | ✓ |
| 无新增权限码 | `sys_permission` 不变 | `sys_permission` INSERT 块 **与 HEAD 逐字节无差异**；`sys_role_permission` 块同样**无差异**；`elder:archive:*`/`data:reveal` 在 **HEAD 即已存在**（id 2017/2019/2021） | ✓ |
| 无新增错误码 | `ErrorCode.java` 不改 | `git diff ErrorCode.java` **为空**；`ARCHIVE_SENSITIVE_ENCRYPT_FAILED(40001)` 在 **HEAD 已存在** | ✓ |
| `03_seed_rbac.sql` | 不改 | ⚠️ 工作树**有**改动，但**非 D-2 引入**：diff 仅 `FAMILY.data_scope 1→5` 及配套注释（**属 D-1 裁决 2**，显式引用 D-1）。D-2 未触碰该文件（`sys_permission`/`sys_role_permission` 均无差异） | ✓（见下注） |
| `ControllerAuthorizationCoverageTest` 白名单 | 仍 4 条、无新豁免 | **恰 4 条**（`AuthController#login/refresh/secondVerify`、`HealthController#health`），未新增豁免 | ✓ |

> **D 注（诚实说明）**：本仓 **stage3 全部改动尚未提交**（`git log` 顶端为 ADR-0004，工作树混有 D-1/C3/C4/ER-14 等多个子卡改动），故 **`git diff` 无法按提交粒度隔离 D-2**。我改用 **D-1 契约盘点基线（implemented 22/planned 21）** 做锚，证明 D-2 的净增量恰为 4 个操作；并逐块比对 `sys_permission`/`sys_role_permission` 与 HEAD 完全一致。任务书"`03_seed_rbac.sql` 应当无改动"在**工作树层面**字面不成立，但**该改动与 D-2 无关**（属 D-1），不构成本次放行障碍。

---

## 5. 缺陷清单

**无。** A/B/C/D 全部通过，三条突变测试全部"红→复原→绿"。

---

## 6. 未覆盖与残留（诚实声明）

以下部分**本次 QA 未验证**或**属设计已登记的残留**，不应被视为"已验证通过"：

1. **ELDER 角色真机 E2E（`elder.user_id` 绑定）** —— 设计裁决二明确"本期只加列+登记+单测，不实现写入流程（`D-2R1`）"。故 `elder.user_id` 无生产写入方，**"老人登录后看到自己档案"的端到端路径本次不可达、未验证**（当前证据仅为 `DataScopePermissionHandlerTest` 修订的 SQL 片段断言 + 静态登记）。
2. **`dataReveal.bizType=USER`** —— 已验证返回 `10001 暂不支持`（符合设计），但其**账号域真实语义未实现**（登记 `R2`）。
3. **`audit_log.biz_id` 未写入** —— 留痕行存在，但 `biz_id` 为 NULL（登记 `R3`）；本次只验了 `action/biz_type/sensitive/second_verify`。
4. **ELDER/FAMILY/SUPERVISOR 三角色在档案端点的数据域落点** —— 仅验证了 ASSESSOR(org) 与 ORG_ADMIN(org)；**ELDER(self)/FAMILY(relation)/SUPERVISOR(all) 未做真实 HTTP E2E**（依赖未落地的绑定数据）。
5. **`consent_record` 锚列缺口（`R5`）** —— D-2 四端点不查该表，未验证。
6. **`elder_no` 唯一键冲突重试路径** —— 未构造真实冲突触发 `ElderArchiveService#insertWithUniqueElderNo` 的 3 次重试；依据单测 `createRetriesElderNoOnConflict`（未在本次独立复跑）。
7. **二次验证凭据"失败也消费"语义** —— A4 补测意外观察到：跨机构 reveal 失败后同一令牌再用于在域内 reveal 得到 `10008`（令牌已被消费）。**判定为设计预期（一次性凭据在业务前校验）**，但建议设计/CR 显式确认"业务失败是否应退还凭据"。

---

## 7. 路由判定

**`NoOne`**（全部通过）—— 源码无 Bug，测试代码（含门禁）亦有真实区分力。
> 注：`Engineer`/`QA` 两条分支未触发。

---

## 附：关键命令清单（可复现）

```sh
# 构建
mvn -o clean package -DskipTests
# 全量回归（务必先停掉占用 fat jar 的 yl-server 进程！）
mvn -o verify
# 门禁脚本
bash scripts/verify-openapi.sh
MYSQL_BIN=/c/mysql/bin/mysql.exe MYSQL_PASSWORD='Mysql8@2026#Dev' bash scripts/verify-schema.sh
MYSQL_BIN=/c/mysql/bin/mysql.exe MYSQL_PASSWORD='Mysql8@2026#Dev' \
  SECOND_VERIFY_DEV_CODE=246810 bash scripts/smoke-report-review-chain.sh
```
> curl 一律加 `--noproxy '*'`；令牌/密钥不落报告。
