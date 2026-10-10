# P2-8-A 设计 · 短信核身通道（骨架 + dev 日志桩）

> 工序：设计（**契约先行**）。裁决日：2026-10-10。
> 卡片：`docs/change/BACKLOG-M2-P1P2.md` → 卡 P2-8
> 地基核查：`docs/reviews/P2-8-foundation-check.md`｜合规清单：`docs/reviews/P2-8-compliance-checklist.md`
> 外部方案：`2026-09-08-13-00-34/stage3_开发/15_短信验证码通道技术方案.md`
> **本卡分两段**：**P2-8-A**（本文档，骨架 + dev 日志桩，零外部依赖）→ **P2-8-B**（真实通道接入 + 四端解除 Mock + 合规上线）

---

## 1. 裁决输入（已拍板）

| # | 裁决 | 结论 | 对设计的影响 |
|---|---|---|---|
| **E1** | 本卡范围 | **A**：先做 P2-8-A（骨架 + dev 日志桩） | 真实服务商 SDK / 四端解除 Mock / 合规上线**均不在本段** |
| **E2** | `LoginChannel` | **新增 `SMS` 枚举值** | 改 `login_log.login_type` 取值口径 + 契约 `POST /auth/login/{channel}` 枚举面 |
| **E3** | 验证码存储 | **Redis TTL** | **零新表、零调度**，契合 ADR-0004（不得新增 `@Scheduled`） |
| **E4** | 合规前置 | 先梳理清单 | 见 `P2-8-compliance-checklist.md`；**生产开启闸门 = C3 DPO 会签** |

---

## 2. ★ 与本卡/方案文档的 6 处偏离（实施前必读）

> 沿用项目铁律：**设计也会错，且错法有规律**。以下每一处都是"卡面/方案假设 ↔ 实读/裁决"冲突后的显式裁定，**不照抄**。

| # | 卡面 / 方案原文 | 实读 / 裁决 | 处置 |
|---|---|---|---|
| **偏离 1** | 卡面：「验证码表（`ADR-0003` 口径：雪花 id / 0 外键 / `DATETIME`）」 | 裁决 **E3 = Redis TTL** | **不建表**。⇒ 本卡 **零 DDL**（除 §5 一处注释口径同步），`verify-schema.sh` 断言数**保持 86**。ADR-0003 的建表规范**不适用**（无表可建） |
| **偏离 2** | 方案 §4.2：Redis 键 `sms:code:{purpose}:{phone}`（**明文手机号**） | 项目红线：手机号**不得明文落库/入日志**；Redis 是**外部介质**，同受约束 | 键改为 `yl:sms:code:{purpose}:{**phoneHash**}`（`SensitiveFieldCodec` 同算法 HMAC 摘要）。**频控/锁定/ticket 键同理** |
| **偏离 3** | 方案 §2.3：字符串业务码 `SMS_RATE_LIMIT_PHONE` / `SMS_CODE_WRONG`… | 项目铁律：**业务失败一律 HTTP 200 + `R.code`（int）**，仅未认证 401+10002 | 全部映射到 `ErrorCode`（§4.2）；**不引入字符串码** |
| **偏离 4** | 方案 §2.5：SMS-04「查询/创建用户（按手机号）」 | **自动建号的角色归属是产品决策**（新账号是 `ELDER` 还是 `FAMILY`？）—— 未裁决 | A 段**不自动建号**：命中→发令牌；未命中→`SMS_ACCOUNT_NOT_BOUND`（新码）并**登记为待裁决残留 R1** |
| **偏离 5** | 方案 §4.2 值 `{code}:{attemptCount}`（**明文 code**） | 红线「验证码不得落明文」 | 值改为 `{**sha256(code)**}:{attemptCount}`（JDK `MessageDigest`，零依赖）——防 Redis 快照/误读泄露；**不承担抗离线爆破**（码空间 10⁶ + TTL 300s，攻击者须先取得 Redis 访问权），此取舍写入 Javadoc |
| **偏离 6 ★新发现** | 契约 `POST /auth/login/{channel}` 的 `enum: [WECHAT, DOUYIN, PASSWORD, APPLE, FACE]` | 实现 `LoginChannel` = `WECHAT/DOUYIN/IOS/ANDROID/FACE` ⇒ **`PASSWORD`/`APPLE` 会被实现拒为 `10001`**（`LoginChannel.of` 逐值比对 `name()`） | **契约-实现漂移**（`verify-openapi.sh` 只查路径集，查不出）⇒ 本卡**一并订正**为实际 5 值 + `SMS` |

**★ 偏离 6 的性质**：这不是本卡引入的缺陷，而是**既有漂移**在"要动同一枚举面"时被暴露。**爆炸半径可控**（契约文本修改，零实现变更），且若继续放任，E2 新增 `SMS` 会把这个错的枚举面**再放大一次**。故必须同批订正。

| # | 卡面 / 方案原文 | 实读 / 裁决 | 处置 |
|---|---|---|---|
| **偏离 7** | `E2` 裁决括注「新增 `LoginChannel.SMS`（会改 `POST /auth/login/{channel}` 的枚举面）」 | 该路径实现为 `LoginChannelRegistry.require(SMS)` ⇒ 抛 `LOGIN_FAILED(20001)`（渠道未接入）。**短信登录的真实入口是 `POST /api/v1/auth/login-by-ticket`** | 保留 `LoginChannel.SMS`（`login_log.login_type` 口径统一，E2 实质）与契约枚举面（遵 E2 括注）；**在契约 `description` 显式声明**「`SMS` 仅用于登录日志口径，短信登录请走 `login-by-ticket`」，把语义写清而非留暗区 |

---

## 3. 契约先行（`openapi/yl-api.yaml` v1.1.3 → v1.1.4）

### 3.1 新增 4 个操作

| 编号 | 方法 · 路径 | operationId | `x-required-permission` | `x-data-scope` | `x-status` |
|---|---|---|---|---|---|
| **SMS-01** | `POST /api/v1/sms/captcha-image` | `smsCaptchaImage` | `none` | `{ mode: public }` | `implemented` |
| **SMS-02** | `POST /api/v1/sms/send` | `smsSend` | `none` | `{ mode: public }` | `implemented` |
| **SMS-03** | `POST /api/v1/sms/verify` | `smsVerify` | `none` | `{ mode: public }` | `implemented` |
| **SMS-04** | `POST /api/v1/auth/login-by-ticket` | `authLoginByTicket` | `none` | `{ mode: public }` | `implemented` |

> **四端点全为 `none` + `public`**：它们是**未登录前置**端点，安全边界是**渠道凭据校验本身**（同 `authLogin`）。`verify-openapi.sh` 断言 ③「`mode=public` 必须配 `none`」**天然满足**。**均不标 `x-second-verify`**（敏感点仅 4 个，短信核身不在此列）。

### 3.2 新增 components

```yaml
    SmsCaptchaImageResponse:      # SMS-01
      captchaId, captchaImage(data URI), ttl
    SmsSendResponse:              # SMS-02
      maskedPhone, ttl            # ★ 不含任何"是否已注册"信息（防枚举）
    SmsTicketResponse:            # SMS-03
      ticket, ticketTtl
    # SMS-04 复用既有 TokenPair
```

### 3.3 订正既有枚举面（偏离 6）

```yaml
- schema: { type: string, enum: [WECHAT, DOUYIN, PASSWORD, APPLE, FACE] }   # 旧（漂移）
+ schema: { type: string, enum: [WECHAT, DOUYIN, IOS, ANDROID, FACE, SMS] } # 新（对齐实现）
```
并同步 `description`：`channel ∈ {WECHAT, DOUYIN, IOS, ANDROID, FACE, SMS}`。

### 3.4 门禁影响预判

| 门禁 | 变化 | 依据 |
|---|---|---|
| `verify-openapi.sh` | **8/8 不变**（断言组数固定）；`implemented` **44 → 48** | 新增 4 个 `implemented` 操作 ⇒ 与控制器端点集**双向漂移仍为 0** |
| `OpenApiContractTest` | 随 `mvn verify` 跑，SnakeYAML 真解析 | — |
| `ControllerAuthorizationCoverageTest` | `CONTROLLERS` **+1**（`SmsController`）；`NO_PERMISSION_WHITELIST` **+4**；端点下限 `≥22 → ≥26` | 新增 4 个无权限码端点，必须显式登记 |

---

## 4. 结构设计

### 4.1 分层（沿用项目端口/实现范式）

```
yl-common（跨模块契约，无 Spring）
  cn.yl.common.sms.SmsPurpose           枚举 LOGIN / BIND / CHANGE_PHONE
  cn.yl.common.sms.SmsScene             枚举 WECHAT / DOUYIN / IOS / ANDROID（→ deviceType 映射）
  cn.yl.common.sms.SmsSender            端口：void send(String phone, SmsPurpose, String code)
  cn.yl.common.sms.SmsCodeStore         端口：验证码 + 失败计数（5 方法）
  cn.yl.common.sms.SmsCaptchaStore      端口：图形验证码答案
  cn.yl.common.sms.SmsTicketStore       端口：ticket（一次性）

yl-infrastructure（Spring Bean 实现）
  cn.yl.infrastructure.sms.LoggingSmsSender        dev 日志桩（受 dev-log-code 开关约束）
  cn.yl.infrastructure.sms.UnavailableSmsSender   fail-closed（provider=真实但未配置 ⇒ 抛 SMS_SEND_FAILED）
  cn.yl.infrastructure.sms.RedisSmsCodeStore      Lua 原子「比对并焚毁」+ INCR 失败计数
  cn.yl.infrastructure.sms.RedisSmsCaptchaStore   GETDEL 一次性
  cn.yl.infrastructure.sms.RedisSmsTicketStore    GETDEL 一次性
  cn.yl.infrastructure.sms.AwtCaptchaRenderer     JDK AWT 生成 PNG（零新依赖）

yl-modules/yl-module-account
  controller.SmsController                        SMS-01/02/03（3 端点，全白名单）
  controller.AuthController                       +loginByTicket（SMS-04）
  service.sms.SmsCodeService                      下发/校验/频控/一次性核销 编排
  service.AuthService                             +loginByTicket(ticket, scene, ip, ua)
  domain.LoginChannel                             +SMS("SMS","WEB",false)
```

**★ 放置理由**：`SmsSender` / `SmsCodeStore` 等端口放 `yl-common` 与既有 `SecondVerifyStore`（在 `yl-common`）+ `RedisSecondVerifyStore`（在 `yl-infrastructure`）**完全同构**；`SmsCodeService` 与 `AuthService` 同模块（SMS-04 需复用 `AuthService` 的令牌签发私有逻辑，跨模块会违 ADR-0004）。

### 4.2 错误码（`ErrorCode` 新增，2xxxx 账号段）

| 码 | 常量 | 消息 | 用途 | 复用？ |
|---|---|---|---|---|
| 10001 | `PARAM_INVALID` | 参数校验失败 | 手机号格式 / purpose / scene 非法 | **复用** |
| 10429 | `RATE_LIMITED` | 请求过于频繁 | 频控超限 **+ 错误锁定** | **复用** |
| **20004** | `SMS_CAPTCHA_REQUIRED` | 请先完成图形验证 | 触发防刷需图形验证但未携带 | 新增 |
| **20005** | `SMS_CAPTCHA_INVALID` | 图形验证码错误或已失效 | 图形验证码不通过 | 新增 |
| **20006** | `SMS_CODE_INVALID` | 验证码错误或已失效 | ★ **错误与过期同码**（防探测） | 新增 |
| **20007** | `SMS_SEND_FAILED` | 验证码发送失败，请稍后重试 | ★ **通道不可用 fail-closed** | 新增 |
| **20008** | `SMS_TICKET_INVALID` | 登录凭据无效或已失效 | ticket 缺失/过期/重放 | 新增 |
| **20009** | `SMS_ACCOUNT_NOT_BOUND` | 该手机号尚未绑定账号 | SMS-04 未命中（见偏离 4 / 残留 R1） | 新增 |

**★ 为什么 `SMS_CODE_INVALID` 要合并"错误"与"过期"**：若分开返回，攻击者可用「错误 vs 过期」的差异**探测验证码状态机**（如判断某手机号是否刚发过码）。同码是"不泄露存在性"哲学（同 `10004`）在验证码上的延伸。

**★ 为什么不用 `10999` 兜 `SMS_SEND_FAILED`**：`10999` 是"未预期异常"的兜底；通道未配置是**已预期的显式状态**，必须有**独立可断言**的码，否则"fail-closed 且可观测"无法被门禁证明（红线 §5）。

### 4.3 频控矩阵（复用既有 `SlidingWindowCounter` → `yl:sw:` 前缀）

| 维度 | 限制 | dimension 串 |
|---|---|---|
| 同手机号 60s | ≤1 | `sms:rl:ph:{phoneHash}:60s` |
| 同手机号 1h | ≤5 | `sms:rl:ph:{phoneHash}:1h` |
| 同手机号 1d | ≤10 | `sms:rl:ph:{phoneHash}:1d` |
| 同 IP 1h | ≤30 | `sms:rl:ip:{ip}:1h` |
| 同设备指纹 1d | ≤20 | `sms:rl:dev:{fp}:1d` |
| **图形验证触发**（同手机号 1h） | ≥3 | `sms:cap:ph:{phoneHash}:1h` |
| **图形验证触发**（同 IP 1h） | ≥10 | `sms:cap:ip:{ip}:1h` |

**★ 零新基建**：`SlidingWindowCounter#recordAndCount` 已是**Lua 原子**（修剪+写入+过期+计数），直接复用；仅新增 dimension 前缀。

**★ 防枚举天然满足**：频控维度**全部不查库**（只用摘要/IP/指纹）⇒ SMS-02 对"已注册/未注册"手机号的**返回与耗时不构成区分**（红线 §4 判据 4）。

### 4.4 Redis 键结构（**全摘要，零明文手机号**）

| 键 | 值 | TTL | 语义 |
|---|---|---|---|
| `yl:sms:code:{purpose}:{phoneHash}` | `{sha256(code)}:{attemptCount}` | 300s | 验证码 + 失败计数 |
| `yl:sms:err:{purpose}:{phoneHash}` | `{attemptCount}` | 900s | 错误锁定计数（≥5 → 锁 15 分钟） |
| `yl:sms:captcha:{captchaId}` | `{answer}` | 120s | 图形验证码答案（GETDEL） |
| `yl:sms:ticket:{ticket}` | `{phoneHash}:{scene}:{purpose}` | 300s | 登录凭据（GETDEL） |
| `yl:sw:sms:rl:*` / `yl:sw:sms:cap:*` | ZSET（既有滑动窗口格式） | 窗口长度 | 频控 |

### 4.5 通道端口与 fail-closed（红线 §5）

```java
public interface SmsSender {
    /**
     * 下发短信。★ 实现**不得**将 phone / code 写入日志（dev 桩受 yl.sms.dev-log-code 开关约束）。
     * @throws BizException SMS_SEND_FAILED —— 通道不可用时**必须显式失败**，绝不静默假装成功
     */
    void send(String phone, SmsPurpose purpose, String code);
}
```

| `yl.sms.provider` | 装配的 Bean | 行为 |
|---|---|---|
| `log`（dev 默认） | `LoggingSmsSender` | 恒"成功"；`dev-log-code=true` 时打印**掩码手机号 + 验证码**（联调必需），否则仅打印掩码手机号 |
| `tencent` / `aliyun` | `UnavailableSmsSender`（A 段） | **抛 `SMS_SEND_FAILED`**（fail-closed，可观测） |
| 生产（P2-8-B） | `TencentSmsSender` / `AliyunSmsSender` | 真实下发 |

**★ 为什么 A 段就要有 `UnavailableSmsSender`**：若 `provider=tencent` 时**没有** Bean，Spring 会因缺依赖**启动失败**或静默降级。前者让"配置错误"变成"服务不可用"（爆炸半径过大），后者是**红线 §5 明令禁止的"静默假装成功"**。显式 fail-closed 是唯一正确解。

### 4.6 dev 验证码打印开关（与红线 §2 的调和）

**冲突**：dev 联调**必须**能拿到验证码（否则无法跑通 SMS-03/04）；但红线 §2 明令"验证码不得进日志"。

**裁定**：`yl.sms.dev-log-code`（默认 `false`）；**仅 `application-dev.yml` 置 `true`**；打印内容**掩码手机号 + 验证码**并带 `[DEV-ONLY]` 前缀。**门禁新增断言**：`application.yml`（非 dev）中该键**不得为 `true`**——把"生产不得打印验证码"从**口头承诺**变成**可回归断言**。

---

## 5. 行为规约（SMS-01 ~ SMS-04）

### SMS-01 图形验证码
```
入：{ scene: "sms_send" }
出：{ captchaId: uuid, captchaImage: "data:image/png;base64,…", ttl: 120 }
实现：AwtCaptchaRenderer 生成 4 位数字（含干扰线/噪点）→ 答案存 yl:sms:captcha:{id}（TTL 120）
```

### SMS-02 发送
```
顺序（★ 顺序即安全性）：
 1. 参数校验（phone ^1[3-9]\d{9}$ / purpose / scene）  → 不合法：10001
 2. 计算 phoneHash = codec.hashForSearch(phone)        → ★ 此后不再用明文 phone 做键
 3. 频控（7 条 dimension，§4.3）                        → 超限：10429 ★ 此处不查库
 4. 锁定检查（yl:sms:err ≥5）                            → 锁定：10429
 5. 图形验证触发判定（同手机号 1h≥3 或 同 IP 1h≥10）
      需图形验证 && 未携带 captchaId            → 20004
      需图形验证 && 图形验证码不通过            → 20005
 6. 生成 6 位码 → 存 {sha256(code)}:0（TTL 300）
 7. sender.send(phone, purpose, code)                  → 通道不可用：20007（fail-closed）
 8. 出：{ maskedPhone: "138****8000", ttl: 300 }        ★ 响应体零"是否已注册"信息
 9. 审计留痕 audit_log（action=SMS_SEND, biz_type=SMS, sensitive=false, detail={purpose,scene}）
```

> **★ 审计口径（方案文档 §7.1 要求「SMS-02/03/04 全量留痕」）**：`audit_log` 的 IP/UA 由 `AuditService` 自动采集；`detail` **只写 `purpose`/`scene` 两个枚举，绝不写手机号或验证码**。`sensitive=false` —— 本端点**无第四闸**语义，置 `true` 会谎报「二次验证已通过」。留痕**失败开放**（只记警告，不阻断主链路，同 `AuthService#writeLoginLog`）。
> SMS-04 的留痕即既有 `login_log`（`login_type=SMS`），不重复写 `audit_log`。

### SMS-03 校验
```
 1. 参数校验                                           → 10001
 2. 锁定检查（≥5 次失败）                              → 10429
 3. Lua 原子「比对并焚毁」consumeIfMatches(phoneHash, purpose, code)
      不存在/不匹配 → INCR 失败计数（≥5 置锁 900s）→ 20006 ★ 失败不退
      匹配          → 已 DEL（一次性核销）→ ticket = UUID（存 phoneHash/scene/purpose，TTL 300）
 4. 出：{ ticket, ticketTtl: 300 }
 5. 审计留痕 audit_log（action=SMS_VERIFY, biz_type=SMS, sensitive=false, detail={purpose,scene}）
```

### SMS-04 ticket 兑换登录态
```
 1. GETDEL yl:sms:ticket:{ticket}                      → 缺失/过期/重放：20008 ★ 一次性天然防重放
 2. 按 phoneHash 查 sys_user（SysUserMapper.selectByPhoneHash）
      未命中 → 20009（★ 不自动建号，见偏离 4 / R1）
 3. 命中 → AuthService.loginByTicket(userId, scene, ip, ua)
      · 复用既有 issueTokens（双令牌）
      · 写 login_log：login_type=SMS（★ 新增取值）、device_type=scene 映射（WXMP/DYMP/IOS/ANDROID，兜底 WEB）
      · touchLastLogin
 4. 出：TokenPair
```

---

## 6. 配置项（`application.yml` 新增 `yl.sms.*`）

```yaml
yl:
  sms:
    enabled: true
    provider: log            # log | tencent | aliyun（A 段仅 log 可用；tencent/aliyun → UnavailableSmsSender）
    dev-log-code: false      # ★ 仅 dev profile 置 true；门禁断言生产不得为 true
    code:    { ttl-seconds: 300, length: 6 }
    ticket:  { ttl-seconds: 300 }
    captcha: { ttl-seconds: 120, length: 4 }
    limits:
      phone-60s: 1
      phone-1h: 5
      phone-1d: 10
      ip-1h: 30
      device-1d: 20
      captcha-phone-1h: 3
      captcha-ip-1h: 10
      err-lock: 5
      err-lock-ttl-seconds: 900
```

**★ 应急停发开关（合规清单 F4）**：`yl.sms.enabled=false` 时 SSE-02 直接 `20007`（fail-closed），**不静默成功**——这就是"紧急下线"的可执行形态。

---

## 7. 验收判据（交 QA 独立复核）

1. **验证码不落明文**：Redis 值为 `sha256(code)`，非明文；断言 Redis 原始值 ≠ 明文码。
2. **一次性核销**：同一码二次校验必失败（Lua 原子；并发双请求仅一次成功）。
3. **失败不退**：输错一次即计数；累计 ≥5 → 锁 900s → `10429`。
4. **频控可断言**：7 条 dimension 逐条短路（同手机号 1h 第 6 次 → `10429`）。
5. **★ 防枚举**：未注册 / 已注册手机号，SMS-02 的**响应体 + `R.code` 完全一致**（且实现路径不查库）。
6. **★ fail-closed**：`provider=tencent`（未配置）→ SMS-02 返回 `20007`，**不得**返回成功；`yl.sms.enabled=false` → 同样 `20007`。
7. **ticket 一次性**：同一 ticket 二次兑换 → `20008`。
8. **dev 开关可回归**：非 dev profile 下 `yl.sms.dev-log-code` 不得为 `true`（门禁断言）。
9. **★ 审计留痕**：SMS-02/03 成功路径各写一条 `audit_log`（`SMS_SEND` / `SMS_VERIFY`，`sensitive=false`），且 `detail` **不含手机号与验证码**；留痕失败**不阻断**主链路。
10. **四门禁只升不降**：单测 / `verify-openapi.sh` 8-8(implemented 44→48) / `verify-schema.sh` 86 / 冒烟。

---

## 8. 门禁与任务分解

### 8.1 门禁实测（2026-10-10 收口，lead 独立复跑）

| 门禁 | 基线（P2-7 后） | P2-8-A 实测 | 说明 |
|---|---|---|---|
| `mvn -o verify` 单测 | 282/0 | **317/0/0/Skipped 1** | 只升不降；唯一 skip = P2-7 既有 `OrphanFileSweepTaskTest` |
| `verify-openapi.sh` | 8/8（44 implemented / 漂移 0） | **8/8（48 implemented / 漂移 0）** | 新增 4 操作，双向漂移仍为 0 |
| `verify-schema.sh` | 86/0 | **86/0（不变）** | ★ **零新表**，印证偏离 1 |
| 既有冒烟 | 119/0/0 | **119/0/0（不变，无回归）** | 设 `EXPORT_DIR` 后与基线逐项一致 |
| SMS 冒烟（新增脚本） | — | **8/0/1 跳过** | 跳过项 = fail-closed（需 `provider=tencent`），已由单测覆盖 |

**★ QA 独立验证（`docs/reviews/P2-8-qa-report.md`）**：15 条突变，**无假红**；初测暴露 **3 处断言缺口**（M3 / M4 ★ / M13），已由 lead 同批补强并用突变**反向证明**（见该报告附录 C）——补强后 `SmsCodeServiceTest` 19→21、新增 `SmsSenderTest` 4 例。

**★ 一处工具级教训（已入长期记忆）**：突变判定脚本若用 `text=True` 读 `mvn` 输出，会因 **Windows GBK** 触发 `UnicodeDecodeError` 使判定器**静默失效**，把三条突变**全部误判为 GREEN** —— **"判定器自己崩了"会伪装成"突变未被检出"**。判定主据须用**退出码**而非文本匹配。

### 8.2 任务分解（串行）

| # | 任务 | 产出 |
|---|---|---|
| **T1** | 契约先行：4 操作 + 3 schema + 枚举订正 | `yl-api.yaml` v1.1.4 |
| **T2** | `ErrorCode` 新增 6 码 + `LoginChannel.SMS` | 枚举 |
| **T3** | `yl-common` 端口 4 个 + 枚举 2 个 | 端口层 |
| **T4** | `yl-infrastructure` Redis 实现 3 + sender 2 + captcha 1 | 实现层 |
| **T5** | `SmsCodeService` + `AuthService.loginByTicket` + `SmsController` | 业务层 |
| **T6** | `SecurityConfig` 白名单 + 覆盖测试登记 + 配置项 + 单测 + 冒烟 | 门禁层 |

---

## 9. ADR / 边界

- **ADR-0003（雪花 id / 0 外键 / DATETIME）**：本卡**零新表** ⇒ 不适用（偏离 1）。**唯一 DDL 动作**：`login_log.login_type` **列注释**同步加 `SMS`（`VARCHAR(16)` 容得下，**零结构变更**，`02_schema.sql` 内联修改即生效）。
- **ADR-0004（唯一 `@Scheduled`）**：**不新增调度**。验证码过期由 **Redis TTL 天然完成**，**零动作** ⇒ 无需第 3 次复评。
- **鉴权四闸**：SMS-01/02/03/04 为**未登录前置**端点 ⇒ ①进 `SecurityConfig` 白名单；②`ControllerAuthorizationCoverageTest` 的 `CONTROLLERS` +1、`NO_PERMISSION_WHITELIST` +4；③第三闸（无 `currentUser()` ⇒ no-op）；④无二次验证。
- **数据域**：四端点 `x-data-scope.mode = public`（未登录），**不涉归属列** ⇒ 无暗区。

---

## 10. 残留登记（本段有意不做）

| # | 残留 | 归属 | 理由 |
|---|---|---|---|
| **P2-8-R1** | SMS-04 **不自动建号**（返回 `20009`） | **待产品裁决** | 新账号角色归属（ELDER / FAMILY）是产品决策，工程不得代替（偏离 4） |
| **P2-8-R2** | **真实通道未接入**（tencent / aliyun） | **P2-8-B** | 需服务商账号 + 签名/模板报备（合规清单 B1–B3） |
| **P2-8-R3** | 四端**未解除 Mock**（端侧仍固定码 `123456`） | **P2-8-B** | 契约先行可达，但端侧联调需真实通道 |
| **P2-8-R4** | **黑名单号段**未实现（风控规则源缺失） | 风控 | 需风控团队提供号段表；A 段以频控兜底 |
| **P2-8-R5** | 图形验证码为 **JDK AWT 文字点选**，抗 OCR 强度有限 | P2-8-B / 另开卡 | 方案 §R-4 已认领；A 段是"有"而非"强" |
| **P2-8-R6** | 审计落 `audit_log`（**3 年**），与方案文档的 ES **180 天**口径不同。★ **留痕动作已实施**（SMS-02/03 写 `audit_log`，SMS-04 走 `login_log`）；**仅"留存期限口径"待裁决** | **待裁决（合规清单 Q4）** | 本工程无 ES；两套口径不能同时为真 |
| **P2-8-R7** | 生产**密钥轮换**导致旧密文不可解（`phone_enc` 读路径 `40001`） | 与 **ADR-011** 同批 | 既有残留（D4-R8），本卡不扩 |
| **P2-8-R8** | 契约 `POST /auth/login/{channel}` 的枚举含 `SMS`，但该路径传 `SMS` 恒 `20001`（短信登录真实入口是 `login-by-ticket`） | 遵 `E2` 括注保留 | 已在契约 `description` 显式声明（偏离 7），**不留暗区**；若后续认为须收紧，删除枚举值即可（一行改动） |
