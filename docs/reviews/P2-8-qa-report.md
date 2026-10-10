# P2-8-A 短信核身通道 · QA 独立验证 + 突变测试报告

> 卡片：`docs/change/BACKLOG-M2-P1P2.md` → 卡 P2-8（A 段：骨架 + dev 日志桩）
> 判据来源：`docs/design/P2-8-sms-verification-design.md`（§4.2 错误码 / §4.3 频控矩阵 / §5 行为规约 / §7 验收判据 / §10 残留）
> 合规清单：`docs/reviews/P2-8-compliance-checklist.md`
> 执行者：QA（独立复核，不采信工程师转述）｜执行日：2026-10-10
> 复核对象：`yl-common/sms`、`yl-infrastructure/sms`、`SmsCodeService`、`SmsController`、`AuthController#loginByTicket`、`SecurityConfig`、`openapi/yl-api.yaml` v1.1.4

---

## 0. 结论速览

| 维度 | 结论 |
|---|---|
| 突变有区分力 | **12/15 条命中红点**（M1/M2/M5/M6/M7/M8/M9/M10/M11/M12'/M14/B4 + M1 语义）；**3 条绿条**：M3（单元级缺口）、M4（★ 缺口）、M13（缺口） |
| 是否存在**假绿**（该红却绿） | **是，3 处**：M3 / M4 / M13 —— 均为**断言缺口**，非实现缺陷。已给补强建议（§2） |
| 是否存在**假红**（该绿却红） | **否**。所有红点均由目标断言触发，且 mvn 命令已加 `-Dspotless.check.skip -Dcheckstyle.skip -Dspotbugs.skip`（避开格式门禁假阳性——本项目已踩过的坑） |
| M12 判定 | M12「mode public→self 且保留 none」**不该红**（设计明示 none 与 mode 非双向约束）；门禁行为正确。补 M12'（mode→越界值）证明第 2 组断言**有区分力** |
| B 组事实核验 | 5/5 通过（§3） |
| 基线门禁 | 定向单测、`verify-openapi.sh` 8/8(impl 48)、`verify-schema.sh` 86/0、`smoke-sms-chain.sh` 8/0/1 全部复现（§4） |
| 残留 | 无。`git status --porcelain` 计数与开工前一致（133），全部被突变文件 **md5 逐字节还原成功**（§5） |
| 放行建议 | **可放行（A 段）**；放行前提：补 M4/M13 断言（§2），其余为 P2-8-B 残留（§6） |

---

## 1. A 组｜突变测试逐条记录（核心交付）

**方法**：`备份原文 → 改一处 → 跑定向测试 → 记录红/绿 → 逐字节还原 → md5 校验`。
**命令模板**（★ 必带格式门禁 skip，否则行长度变化触发 spotless 假阳性）：

```bash
# 服务层
mvn -o -pl yl-modules/yl-module-account -am test -Dtest=SmsCodeServiceTest \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
  -Dspotless.check.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true
# 存储层
mvn -o -pl yl-infrastructure -am test -Dtest=RedisSmsCodeStoreTest \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
  -Dspotless.check.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true
# 契约
bash scripts/verify-openapi.sh
```

> ⚠️ 复现要点：任务给的模板只有 `-DfailIfNoTests=false`；在 `-am` 下上游模块（yl-common）会被套用同一 `-Dtest` 过滤而因「No tests matching pattern」**误报构建失败**，必须补 `-Dsurefire.failIfNoSpecifiedTests=false`。这是本次实测踩到的第一个坑。

### 1.1 突变结果表

| # | 突变 | 改动文件:行 | 跑的测试 | 结果 | 红点断言名（原文） | 还原 md5 |
|---|---|---|---|---|---|---|
| **M1** | Lua 退化为「GET 后不删」 | `RedisSmsCodeStore.java:61`（`redis.call('DEL', KEYS[1])`→注释） | RedisSmsCodeStoreTest | **红** | `consumeScriptIsAtomicGetThenDel:127` | ✅ OK |
| **M2** | `consumeIfMatches` 直接 `return true` | `RedisSmsCodeStore.java:88` | RedisSmsCodeStoreTest | **红** | `consumeIfMatchesMapsLuaResult:71` | ✅ OK |
| **M3** | 摘掉 `phone:60s` 的 `checkLimit` | `SmsCodeService.java:179` | SmsCodeServiceTest | ⚠️ **绿** | ——（**断言缺口**，见 §2.1） | ✅ OK |
| **M4** | `hashPhone` 改为 `return phone;`（明文） | `SmsCodeService.java:237` | SmsCodeServiceTest | ⚠️ **绿** | ——（**断言缺口 ★**，见 §2.2） | ✅ OK |
| **M5** | `SMS_CAPTCHA_REQUIRED` 分支不抛（`return;`） | `SmsCodeService.java:208` | SmsCodeServiceTest | **红** | `sendRequiresCaptchaWhenTriggered:190` | ✅ OK |
| **M6** | `SMS_CODE_INVALID`→`PARAM_INVALID` | `SmsCodeService.java:143` | SmsCodeServiceTest | **红** | `verifyFailureCountsAndReturnsInvalid:320`（期望 20006 实得 10001） | ✅ OK |
| **M7** | `recordAudit` 的 `sensitive` `false`→`true` | `SmsCodeService.java:169` | SmsCodeServiceTest | **红（×2）** | `sendWritesAuditWithoutSensitiveData:346`、`verifyWritesAuditAsNonSensitive:359` | ✅ OK |
| **M8** | 删除 `send` 的 `!properties.isEnabled()` fail-closed 分支 | `SmsCodeService.java:95-98` | SmsCodeServiceTest | **红** | `sendFailClosedWhenDisabled:231` | ✅ OK |
| **M9** | `captchaRequired` 直接 `return false;` | `SmsCodeService.java:228` | SmsCodeServiceTest | **红（×2）** | `sendRequiresCaptchaWhenTriggered:190`、`sendRejectsWrongCaptcha:206` | ✅ OK |
| **M10** | `SmsScene` WECHAT→`"BAD"` | `SmsScene.java:13` | SmsCodeServiceTest | **红** | `purposeEnumParsesCaseInsensitively:377`（期望 WXMP 实得 BAD） | ✅ OK |
| **M11** | SMS-04 `x-status: implemented`→`planned` | `openapi/yl-api.yaml:482` | verify-openapi.sh | **红（×2）** | [5/5]「控制器端点数 = 48（期望 47）」「契约缺登 = 1」 | ✅ OK |
| **M12** | SMS-02 `mode: public`→`self`（保留 `none`） | `openapi/yl-api.yaml:551` | verify-openapi.sh | ⚠️ **绿** | ——（**设计允许，非缺口**，见 §1.2） | ✅ OK |
| **M12'** | SMS-02 `mode`→`bogus`（越界值） | `openapi/yl-api.yaml:551` | verify-openapi.sh | **红** | [2/5]「mode 缺失或越界的操作数 = 1」 | ✅ OK |
| **M13** | `LoggingSmsSender` 去 `if (devLogCode)` 守卫（恒打印验证码） | `LoggingSmsSender.java:31` | RedisSmsCodeStoreTest+AwtCaptchaRendererTest | ⚠️ **绿** | ——（**断言缺口**，见 §2.3） | ✅ 已还原（手工） |
| **M14** | `verify` 摘掉 `enforceLocked` | `SmsCodeService.java:135` | SmsCodeServiceTest | **红** | `verifyRejectedWhenLocked:331`（期望 10429 实得 20006） | ✅ OK |
| **B4** | `application.yml` 的 `yl.sms.dev-log-code` `false`→`true` | `application.yml:54` | SmsDevLogCodeConfigTest | **红** | `productionConfigKeepsDevLogCodeFalse` | ✅ OK |

> M1–M12 为任务必做项（M1–M12），M12'、M13、M14、B4 为增强项。

### 1.2 M11 / M12 深度说明（契约门禁）

**M11（红）** —— 基线 `verify-openapi.sh` = **8/8（实现 48，漂移 0）**；把 SMS-04 状态改 `planned` 后：

```
  ✗ 控制器端点数 = 48（期望 47）
  ✗ 契约缺登的已实现端点（错误路径/漏登）条数 = 1（期望 0）
      · POST /api/v1/auth/login-by-ticket
 契约门禁结果：通过 6 项，失败 2 项（契约操作 48，其中 implemented 47）
```

第 5 组「双向漂移」断言**有区分力** ✅。

**M12（绿）** —— 把 SMS-02 的 `mode: public` 改成 `self` 并**保留** `x-required-permission: none`，门禁**全绿**（8/8）。**这不是假绿，也不是缺口**，因为：

* `verify-openapi.sh` 第 3 组只断言 `mode=public ⟹ perm=none`（单向）；
* `OpenApiContractTest#publicModeImpliesNonePermission` 亦**显式声明反向不成立**（源码注释 `OpenApiContractTest.java:149`）——`/auth/second-verify`、`/auth/logout` 就合法地是 `none + 非 public`。
* SMS-01/02/03/04 本就是「未登录前置 + `none`」，`self + none` 在现有口径下**合法**，门禁不应报错。

为证明第 2 组**并非形同虚设**，补做 **M12'**（把 mode 改成**越界值** `bogus`）：

```
  ✗ mode 缺失或越界的操作数 = 1（期望 0）
      · POST /api/v1/sms/send → mode=bogus
 契约门禁结果：通过 7 项，失败 1 项
```

⇒ 结论：契约门禁对「mode 越界」有区分力；对「none 端点被误标非 public」**按设计不拦截**（口径已文档化）。**信息级提示**（非缺陷）：若未来希望把「未登录白名单端点必须 public」也纳入回归，需在第 3 组加一条**反向**断言，但会与既有 `second-verify/logout` 冲突，须先收敛口径。

### 1.3 M1 的语义边界（重要但非缺口）

M1 红点是 `consumeScriptIsAtomicGetThenDel`——它断言 **Lua 脚本文本同时含 `'GET'` 与 `'DEL'`**，属「把原子性从口头承诺变成可回归断言」的**文本级**守护。**语义级**（同一码二次校验必失败、并发双请求仅一次成功）**没有真实 Redis 的集成/并发测试**，仅由 `consumeIfMatchesMapsLuaResult` 的 mock 映射覆盖。⇒ 若有人把原子语义改坏但脚本文本仍含 `'GET'`/`'DEL'`（例如把 `stored == ARGV[1]` 写成 `stored ~= ARGV[1]`），**当前无测试可捕获**。列为残留（§6-R-a）。

---

## 2. 断言缺口清单（补强建议，仅报告、不改生产代码）

### 2.1 M3 —— 单元级不覆盖 `phone:60s` 频控维度

* **现象**：删除 `checkLimit("sms:rl:ph:"+phoneHash+":60s", …)` 后 `SmsCodeServiceTest` 仍 **19/0/0 全绿**。
* **原因**：`sendRateLimited` 用 `when(counter.recordAndCount(anyString(), anyLong())).thenReturn(999L)`——**所有**维度统一返回超限值，移除任一条仍由其余维度短路到 `10429`；无任何用例**按 dimension 串**隔离断言 60s 那条。
* **补强建议**：加 `ArgumentCaptor<String>` 捕获 `counter.recordAndCount` 的 dimension，断言 7 条 dimension 串（`sms:rl:ph:*:60s / :1h / :1d`、`sms:rl:ip:*:1h`、`sms:rl:dev:*:1d`、`sms:cap:ph:*:1h`、`sms:cap:ip:*:1h`）**逐条被调用**。
* **风险等级**：中。HTTP 冒烟 `[4/4]`（同号 60s 第二次 → `10429`）**可兜住**（见 §3.6 推断链），但单元层无纵深。

### 2.2 M4 ★ —— 「键不含明文手机号」**无断言**（任务重点标记项）

* **现象**：`hashPhone` 改为 `return phone;`（Redis 键退化为**明文手机号**）后，`SmsCodeServiceTest` **19/0/0 全绿**——**确认存在断言缺口**。
* **证据**：全测试树中唯一引用 `"13800008000"` 的断言是 `sendWritesAuditWithoutSensitiveData`（校验**审计 detail** 不含手机号，**与 Redis 键无关**）；对 `codeStore.save/consumeIfMatches` 的 verify 全部用 `anyString()`，不校验键内容。
* **后果**：设计 §2 偏离 2 / §4.4 / 验收判据 5（红线「手机号不得明文落 Redis」）**当前无回归断言**。一旦有人改动 `hashPhone`，静默退化为明文键，门禁不会变红。
* **补强建议**：在 `SmsCodeServiceTest` 增加——用 `ArgumentCaptor<String>` 捕获 `codeStore.save(...)` 第一参（`phoneHash`）与 `counter.recordAndCount(...)` 的 dimension，断言二者**均不含明文 `PHONE`**且**等于** `codec.hashForSearch(PHONE)`。此法同时覆盖 M3 与 M4。
* **风险等级**：**高**（直击红线，且为本次唯一「明文泄露」类缺口）。

### 2.3 M13 —— `LoggingSmsSender` 零测试覆盖

* **现象**：去掉 `if (devLogCode)` 守卫（恒打印验证码明文）后，SMS 相关 infra 测试 **8/0/0 全绿**；`grep` 证明**无任何测试文件引用 `LoggingSmsSender`**。
* **后果**：设计 §4.6 / 验收判据 8「dev 开关可回归」当前**只由 `SmsDevLogCodeConfigTest`（校验 yaml 值）守护**——它保证了 `application.yml` 的**开关值**为 false，却**不保证** `LoggingSmsSender` 真的按开关**抑制**验证码。行为层无断言。
* **补强建议**：（a）`LoggingSmsSenderTest`：以 `devLogCode=false` 构造，捕获日志输出断言**不含**验证码；以 `true` 构造断言含 `[DEV-ONLY]` 前缀；（b）`SmsSenderConfigTest`：断言 `provider` 三值与 Bean 类型映射（`log`→Logging、`tencent/aliyun`→Unavailable）。
* **风险等级**：中高（合规红线 §4.6，B4 只守了一半）。

### 2.4 缺口汇总与优先级

| 缺口 | 断言对象 | 覆盖是否由别处兜底 | 建议优先级 |
|---|---|---|---|
| M4 | Redis 键不含明文手机号 | **否**（smoke 只探响应体，不探 Redis 键） | **P0** |
| M13 | Sender 按 dev 开关抑制验证码 | **否**（config 测试只查 yaml 值） | **P0** |
| M3 | 7 条 dimension 逐条短路 | 部分（smoke [4] 兜 60s，见 §3.6） | P1 |
| M1 语义 | 一次性核销的**真并发**语义 | **否**（无真实 Redis 集成测试） | P1 |

---

## 3. B 组｜独立事实核验（原始命令 + 原始输出）

### 3.1 防枚举（静态，`send` 调用链不查库）

```bash
grep -rnE "Mapper|Repository|Jdbc|selectBy|@Select|DataSource|QueryWrapper" \
  yl-modules/yl-module-account/src/main/java/cn/yl/modules/account/service/sms/
```
原始输出：**零命中**。`SmsCodeService` 全部 28 条 import 中**无任何 DB/Mapper/JDBC 类型**（仅 `SmsXxx` 端口、`SlidingWindowCounter`、`SensitiveFieldCodec`、`AuditService`）。`SmsController` 唯一依赖 = `SmsCodeService`。
⇒ **静态证明成立**：`send` 全链路只用摘要/IP/指纹构造键，**不查库**。（`AuditService#record` 为**旁路写**审计，不读用户数据，且失败开放不阻断主链路。）

### 3.2 防枚举（行为，不同手机号响应字段集恒等）

```bash
curl -s --noproxy '*' -X POST http://127.0.0.1:8080/api/v1/sms/send \
  -H 'Content-Type: application/json' -H 'User-Agent: enum-probe' \
  -d '{"phone":"13700000001","purpose":"LOGIN","scene":"WECHAT"}'
```
原始输出（两个不同手机号）：
```
phone=13700000001 -> {"code":0,...,"data":{"maskedPhone":"137****0001","ttl":300},...}
     data keys: ['maskedPhone', 'ttl'] code= 0
phone=13700000002 -> {"code":0,...,"data":{"maskedPhone":"137****0002","ttl":300},...}
     data keys: ['maskedPhone', 'ttl'] code= 0
```
⇒ **响应体字段集恒为 `{maskedPhone, ttl}`，`R.code` 恒 0**，无任何「是否已注册」信息。✅

### 3.3 契约-实现一致性（合约 description 声称「传 SMS 返回 20001」）

```bash
curl -s --noproxy '*' -X POST http://127.0.0.1:8080/api/v1/auth/login/SMS \
  -H 'Content-Type: application/json' -d '{"username":"x","password":"y"}'
```
原始输出：
```
HTTP=200  BODY={"code":20001,"message":"该渠道登录暂未接入：SMS","traceId":"...","success":false}
```
⇒ `R.code=20001`，与 `openapi/yl-api.yaml:348` description 陈述一致。✅

### 3.4 未认证可达性（4 端点无令牌可达）

| 端点 | 原始结果 |
|---|---|
| `POST /api/v1/sms/captcha-image` | `HTTP=200  {"code":0,...,"data":{"captchaId":"35c8...","captchaImage":"data:image/png;base64,iVBOR..."}}` |
| `POST /api/v1/sms/send` | `HTTP=200  {"code":0,...,"data":{"maskedPhone":"139****1316","ttl":300}}` |
| `POST /api/v1/sms/verify` | `HTTP=200  {"code":20006,"message":"验证码错误或已失效"}` |
| `POST /api/v1/auth/login-by-ticket` | `HTTP=200  {"code":20008,"message":"登录凭据无效或已失效"}` |

⇒ 四端点**均 HTTP 200 且 `R.code ≠ 10002`**，未认证可达。✅

### 3.5 白名单精确性（伪造令牌仍 401+10002）

```bash
curl -s --noproxy '*' -X GET http://127.0.0.1:8080/api/v1/elders \
  -H 'Authorization: Bearer FORGED.TOKEN.XXXX'
curl -s --noproxy '*' -X POST http://127.0.0.1:8080/api/v1/auth/second-verify \
  -H 'Authorization: Bearer FORGED.TOKEN.XXXX' -d '{"method":"SMS","credential":"x"}'
```
原始输出：
```
[B3e-1] POST /api/v1/auth/second-verify (伪造 Bearer)  HTTP=401  {"code":10002,"message":"未登录或登录已过期"}
[B3e-2] GET  /api/v1/elders            (伪造 Bearer)  HTTP=401  {"code":10002,...}
[B3e-3] GET  /api/v1/elders            (无令牌)       HTTP=401  {"code":10002,...}
```
⇒ 受保护端点对伪造令牌/无令牌**一律 401+10002**，证明 `SecurityConfig` 白名单**精确**（`/api/v1/sms/**` 与 `/api/v1/auth/login-by-ticket` 放行，`anyRequest().authenticated()` 未误伤）。✅

### 3.6 dev 开关门禁（突变证明真会红）

见 §1.1 **B4**：`application.yml` 的 `yl.sms.dev-log-code: false → true` ⇒ `SmsDevLogCodeConfigTest#productionConfigKeepsDevLogCodeFalse` **红**。基线该测试 2/0/0。✅
*（注：开关的「值」有门禁；开关的「行为」无门禁，见 §2.3-M13。）*

### 3.7 零新表 / 零调度（ADR-0004）

```bash
grep -rniE "create table[^;]*(sms|captcha|verify_code|ticket)" docker/ openapi/   # 零命中
```
⇒ **无任何短信相关建表**。`docker/mysql/init/*.sql` 的 `CREATE TABLE` 计数：`01_init.sql=1`、`02_schema.sql=38`、其余 0（与 P2-8 无关，本卡零 DDL；唯一动作是 `login_log.login_type` **列注释**同步，`VARCHAR(16)` 零结构变更）。

```bash
grep -rn "@Scheduled" --include=*.java . | grep -v '/target/'
```
真实**注解**仅 1 处：`yl-bootstrap/.../schedule/RetentionChainScheduler.java:97`（**既有** P2-7 链路壳）；其余命中**全部**是 Javadoc/测试守卫文案。
⇒ **零新增 `@Scheduled`**，符合 ADR-0004。✅

### 3.8 收尾：`verify-schema.sh` 独立复跑

```bash
set -a && . /tmp/d5-run/db.sh && set +a && bash scripts/verify-schema.sh
```
原始输出尾：
```
 结果：通过 86 项，失败 0 项，待批准守卫 0 项（合计 86 项）
EXIT=0
```
⇒ **86/0**，与基线一致（零新表在 schema 门禁侧亦成立）。✅

---

## 4. 门禁复现（我独立跑出来的数值）

| 门禁 | 基线（lead 转述） | 我独立复跑 | 一致 |
|---|---|---|---|
| `mvn -o verify`（聚合单测） | 311/0/0/Skipped 1 | **未跑全量**（避免重写 `yl-bootstrap.jar`、避免扰动 8080 实例，见 §6-N4） | — |
| 定向 `SmsCodeServiceTest` | — | **19/0/0**（原始输出见 §1 各条） | — |
| 定向 `RedisSmsCodeStoreTest` | — | **7/0/0** | — |
| 定向 `AwtCaptchaRendererTest` | — | **1/0/0** | — |
| 定向 `SmsDevLogCodeConfigTest` | — | **2/0/0** | — |
| `verify-openapi.sh` | 8/8（impl 48，漂移 0） | **8/8（impl 48，漂移 0）** | ✅ |
| `verify-schema.sh` | 86/0 | **86/0** | ✅ |
| `smoke-sms-chain.sh` | 8/0/1 | **8/0/1，exit 0** | ✅ |

**`smoke-sms-chain.sh` 断言引擎自证**：脚本含 `PASS/FAIL/SKIP` 计数与收尾 `[ "$FAIL" -eq 0 ] || exit 1`；实跑 `EXIT_CODE=0`（若失败必为非 0）。**是真断言，非仅打印**。✅

---

## 5. 残留与还原校验

* 开工前 `git status --porcelain` = **133** 行；收工后 = **133** 行（**新增报告本身除外**，属交付物）。**无我留下的任何临时文件/残留修改**。
* 全部被突变文件 `md5` 与备份**逐字节一致**：

```
OK   RedisSmsCodeStore.java   a724f57820f9de334f548a0fd7cbcccf
OK   SmsCodeService.java      b867a97b4fc793b2f122b3b0957bf2a3
OK   SmsScene.java            7161d064a18cffc863c5c0764e2b0908
OK   openapi/yl-api.yaml      f55a9b4b1c6c8730b4cade2e4e2d35aa
OK   application.yml          9b77dbb4f9d79be6eec569aef265b71f
OK   application-dev.yml      8b194ec2cdd1a7e5a90e799f7d1048f1
OK   LoggingSmsSender.java    （未入备份集，M13 改动已手工反转，`if (devLogCode)` 复现于 :31）
```

> 说明：`LoggingSmsSender.java` 未在初始备份集内（我的疏漏），M13 突变生效后发现，已用**反向精确替换**还原并核关键行；该文件为**未跟踪新文件**，无法用 git 佐证，故以原 Read 快照（`:31 if (devLogCode) {`）人工比对确认。**其余 6 文件 md5 全部 OK**。

---

## 6. 结论、放行与残留风险

### 6.1 假绿 / 假红判定

* **假红：无**。所有红点均落在目标断言，且已规避格式门禁假阳性。
* **假绿：有 3 处**（M3 单元级 / M4 ★ / M13），性质均为**断言缺口**而非实现缺陷——即实现是对的，但「若被改坏也无法被发现」。

### 6.2 是否可放行

* **A 段（骨架 + dev 日志桩）可放行**：功能链（SMS-01~04）、错误码、fail-closed、防枚举、一次性核销、契约、零新表零调度、未认证可达性/白名单精确性 **均经独立复核成立**。
* **放行前置（建议同批补，非阻塞合并，但强烈建议）**：
  1. **M4 补断言**（P0）：Redis 键/频控 dimension 不含明文手机号；同时覆盖 M3。
  2. **M13 补断言**（P0）：`LoggingSmsSender` 按 `devLogCode` 抑制验证码 + `SmsSenderConfig` 装配映射。
* **不得据此放行生产**：生产开门闸门是 DPO 会签（合规 C3，L2），与 A 段 QA 无关。

### 6.3 残留风险（对应设计 §10，及本次新发现）

| # | 残留 | 归属 | 说明 |
|---|---|---|---|
| P2-8-R1 | SMS-04 不自动建号（返回 20009） | 待产品裁决 | 我实测 `login-by-ticket` 伪造 ticket→20008；未命中号→20009 的**真实路径**未构造（需已发码的手机号命中/未命中样本），**未直接验证** |
| P2-8-R2 | 真实通道（tencent/aliyun）未接入 | P2-8-B | 本次**无法验证**真实外发 |
| P2-8-R3 | 四端未解除 Mock | P2-8-B | 端侧不在本仓，**无法验证** |
| P2-8-R4 | 黑名单号段未实现 | 风控 | A 段以频控兜底，**未验证** |
| P2-8-R5 | 图形验证码抗 OCR 弱 | P2-8-B/另开卡 | 仅静态确认存在 `AwtCaptchaRendererTest`，未评估强度 |
| P2-8-R6 | 审计留存口径 180d vs 3y | 待裁决 | 合规问题，非工程可验 |
| P2-8-R7 | 密钥轮换致旧密文不可解 | 与 ADR-011 同批 | 与本卡无关 |
| **R-a（新）** | **一次性核销无「真实 Redis 并发」测试** | **后端/Q2** | 语义仅由「脚本文本 + mock 映射」守护（§1.3）。建议补 Testcontainers 或本地 Redis 集成测试：同码双消费仅一次成功 |
| **R-b（新）** | **`smoke-sms-chain.sh [7]` fail-closed 默认跳过** | 后端 | 需 `EXPECT_PROVIDER=tencent` + 重启实例；默认基线**不覆盖 provider 层 fail-closed**（服务层由 `sendFailClosedWhenSenderUnavailable` 覆盖）|
| **R-c（新）** | **M3 的 HTTP 级红点未在突变态实测** | QA | `smoke [4]` 逻辑上可捕获（同号 60s 第二次：删 60s 后 1h 计数=2<5、1d=2<10，不短路 ⇒ 返回 code 0 而非 10429 ⇒ 冒烟红），但需重打包重启才能实证；本次以推断给出，**未直接验证** |

### 6.4 我无法验证的项（显式列出，不含糊）

1. **真实短信外发通道**（R2）——A 段无实现，无外部凭据。
2. **`provider=tencent/aliyun` 的 HTTP fail-closed（20007）**——默认运行的 8080 实例为 `provider=log`；要实测需停 8080、改配置、重打包重启，与「只读探针」约束冲突，故**未做**（仅单元覆盖）。
3. **端侧四端解除 Mock / 联调**（R3）——不在本仓。
4. **聚合 `mvn -o verify` = 311/0/0**——为避免重写 `yl-bootstrap.jar`（会与正在跑的 8080 实例冲突）而**未复跑全量**；我只跑了**定向模块 + 4 个 shell 门禁**，其数值均独立复现。
5. **SMS-04 命中已绑定手机号 → 签发真实令牌**（§5 步骤 3）——需要「已发码且已绑定」的端到端样本与环境，本次仅验证到「伪造 ticket→20008」。
6. **黑名单号段 / 风控规则**（R4）——规则源缺失。
7. **一次性核销真并发**（R-a）——无真实 Redis 集成测试设施。

---

## 附录 A：本次执行过的原始命令清单（可复现）

```bash
# 1) 基线
mvn -o -pl yl-modules/yl-module-account -am test -Dtest=SmsCodeServiceTest \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
  -Dspotless.check.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true     # 19/0/0
mvn -o -pl yl-infrastructure -am test -Dtest=RedisSmsCodeStoreTest ...      # 7/0/0
mvn -o -pl yl-bootstrap -am test -Dtest=SmsDevLogCodeConfigTest ...         # 2/0/0
bash scripts/verify-openapi.sh                                              # 8/8 impl48
bash scripts/verify-schema.sh                                               # 86/0
bash scripts/smoke-sms-chain.sh                                             # 8/0/1 exit0

# 2) 突变（示例，M4）
#   备份 → 改 → 跑 → 还原 → md5
#   M4: SmsCodeService.java:237  return codec.hashForSearch(phone);  ->  return phone;
#   → SmsCodeServiceTest 19/0/0（绿，缺口）

# 3) B 组探针（只读 HTTP）
curl -s --noproxy '*' -X POST http://127.0.0.1:8080/api/v1/auth/login/SMS -d '...'      # 20001
curl -s --noproxy '*' -X POST http://127.0.0.1:8080/api/v1/sms/send -d '...'            # code 0
curl -s --noproxy '*' -X GET  http://127.0.0.1:8080/api/v1/elders -H 'Authorization: Bearer FORGED.TOKEN.XXXX'  # 401+10002
```

## 附录 B：口径自查（本项目踩坑点）

* 业务失败一律 **HTTP 200 + `R.code`**；仅未认证 **401+10002**——本次所有断言均按此，未拿 HTTP 状态当放行。✅
* 未用「不存在的 id」做负例（避免与越权同返 10004 的失效用例）。✅
* 凭据全程走文件（`/tmp/d4-run/env.sh`、`/tmp/d5-run/db.sh`），**未把口令内联进命令行**，未触发敏感闸门。✅
* 验收驱动 `smoke-sms-chain.sh` 已自证有断言引擎（计数 + `exit 1`）。✅

---

## 附录 C：lead 补强闭环（2026-10-10）

> 本报告 §2 登记了 3 处**断言缺口**（实现正确，但改坏测不出）。按项目既定铁律（「门禁只升不降」「断言缺口必须闭环，且用突变反向证明补强有效」——同 P2-7-R6 手法），**lead 已同批补强并逐条证伪**。

### C.1 补强内容

| 缺口 | 补强动作 | 落点 |
|---|---|---|
| **M3**（`phone:60s` 维度无断言） | 新增 `sendChecksSixtySecondPhoneWindowExplicitly`：捕获 `counter.recordAndCount` 的全部 dimension，断言存在 `sms:rl:ph:*:60s` | `SmsCodeServiceTest` |
| **M4 ★**（键不含明文手机号无断言） | 新增 `storageAndRateLimitKeysNeverContainPlaintextPhone`：捕获 `codeStore.save` 首参与**全部**频控 dimension，断言**不含明文手机号**、且首参会匹配 `[0-9a-f]{64}`（HMAC-SHA256） | `SmsCodeServiceTest` |
| **M13**（`LoggingSmsSender` 零测试） | 新增 `SmsSenderTest`（4 例，logback `ListAppender` 行为门禁）：①`dev-log-code=false` 时验证码**不进日志**；②`true` 时带 `[DEV-ONLY]` 打印；③`UnavailableSmsSender` fail-closed 抛 `20007`；④两者**均不得**把明文手机号写进日志 | `yl-infrastructure/src/test/.../sms/SmsSenderTest.java` |

### C.2 突变反向证明（补强后重跑 QA 的原始突变）

工具：`scripts/dev/p28-mutation-recheck.py`（逐条 备份 → 改 → 跑定向测试 → **逐字节还原** → md5 校验）。

| 突变 | 结果 | 命中的断言 | 还原 |
|---|---|---|---|
| **M4'** `hashPhone` → `return phone;` | **RED** | `storageAndRateLimitKeysNeverContainPlaintextPhone` | md5 一致 ✅ |
| **M3'** 摘掉 `phone:60s` 维度 | **RED** | `sendChecksSixtySecondPhoneWindowExplicitly` | md5 一致 ✅ |
| **M13'** `dev-log-code=false` 仍打印验证码 | **RED** | `loggingSenderHidesCodeWhenDevLogCodeDisabled` | md5 一致 ✅ |

⇒ **3 处缺口全部闭环**，且**每条补强断言都被证明有区分力**（不再存在"改坏却全绿"的暗区）。

> **★ 一个必须记下的工具坑**：本补强脚本首跑时曾把三条突变**全部误判为 GREEN**——根因是 `subprocess` 用了 `text=True`（按 UTF-8 解码），而 **`mvn` 在 Windows 上是 GBK 输出**，`UnicodeDecodeError` 让"是否变红"的判定代码**从未执行**。**"判定器自己崩了"会伪装成"突变未被检出"**，是比假绿更隐蔽的一种假结论。修法：字节读取 + `decode("utf-8", errors="replace")`，并以**退出码**（而非文本匹配）为主判据。

### C.3 补强后门禁（lead 独立复跑）

| 门禁 | 补强前 | 补强后 |
|---|---|---|
| `mvn -o verify` | 311/0/0/Skipped 1 | **317/0/0/Skipped 1**（+6：新增 `SmsSenderTest` 4 例；`SmsCodeServiceTest` 19 → 21） |
| `verify-openapi.sh` | 8/8（48 implemented，漂移 0） | **8/8（不变）** |
| `verify-schema.sh` | 86/0 | **86/0（不变，零新表）** |
| 既有冒烟 | 119/0/0 | **119/0/0（不变）** |
| SMS 冒烟 | 8/0/1 | 8/0/1（不变） |
