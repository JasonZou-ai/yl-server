# 变更单 CR-M2-004 · 开发期渠道降级开关（未接入平台渠道的账密回退通道）

| 项 | 内容 |
|---|---|
| 变更单编号 | **CR-M2-004** |
| 提出日期 | 2026-09-23 |
| 提出人 | 产品负责人（P1-5「其余端切真实接口」执行中发现前置阻塞） |
| 变更对象 | `AuthService.login()`；`LoginResponse`；`application.yml` / `application-dev.yml` |
| 变更依据 | 2026-09-23 实测：`POST /api/v1/auth/login/WECHAT` 与 `/DOUYIN` 返回 `20001 该渠道未完成平台配置，暂不可用` |
| 变更类别 | **一般变更**（新增**开发期**通道，不改生产认证语义） |
| 紧急度 | 中 —— 不解决则 P1-5 中微信端/抖音端的验收（"登录→…→复核发布"）**无法达成** |
| 状态 | **✅ 已实施（2026-09-23）** |

---

## 一、问题（实测证据）

`LoginChannel` 枚举把渠道分为两类：

| 渠道 | loginType | deviceType | passwordSupported | 实测登录 |
|---|---|---|---|---|
| `IOS` | PWD | IOS | **true** | ✅ 成功 |
| `ANDROID` | PWD | ANDROID | **true** | ✅ 成功 |
| `WECHAT` | WXMP | WXMP | **false** | ❌ `20001 该渠道未完成平台配置` |
| `DOUYIN` | DYMP | DYMP | **false** | ❌ `20001 该渠道未完成平台配置` |
| `FACE` | FACE | IOS | false | ❌ 未接入（生物识别需单独同意 + PIA） |

`LoginChannelRegistry.require()` 对 `configured()=false` 的渠道**明确拒绝**（安全默认，正确设计）。而 `WechatThirdPartyAuthenticator` / `DouyinThirdPartyAuthenticator` 因 app-id/app-secret 未配置，`configured()` 恒为 false。

**后果**：微信端、抖音端在平台授权渠道接入前**无法登录**，故 P1-5 卡片里"每端跑通主链"的验收项对该两端**不可达**。

---

## 二、处置：dev-only 降级通道（**不是一个"免密开关"**）

### 2.1 判定条件（四者同时成立才降级）

```
开关开启（yl.auth.channel-downgrade.enabled = true）
  AND 渠道在降级白名单内（ChannelDowngradeSwitch.ALLOWED_CHANNELS = {WECHAT, DOUYIN}）
  AND 渠道非账密渠道（!channel.passwordSupported()）
  AND 请求携带非空口令（password != null && !password.isBlank()）
```

任一不成立即**回落原路径**（`LoginChannelRegistry.require` → 明确拒绝）。

### 2.2 四条安全红线

| # | 红线 | 落实方式 |
|---|---|---|
| 1 | **默认关闭** | `application.yml` 写 `${AUTH_CHANNEL_DOWNGRADE:false}`；**只有 `application-dev.yml` 覆盖为 true**。生产不写即失效，无"忘了关"的余地 |
| 2 | **降级 ≠ 免密** | 口令比对条件由 `channel.passwordSupported()` 改为 `(channel.passwordSupported() \|\| downgrade)` —— 降级路径**仍然强制 BCrypt 比对** |
| 3 | **无绕过面** | 未携口令**不降级**，仍按"渠道未配置"拒绝 → 不存在"报渠道名即放行" |
| 4 | **渠道白名单，不得按"非账密渠道"泛化** | 仅 `{WECHAT, DOUYIN}` 可降级；**FACE 明确排除**（见 §2.2.1） |

#### 2.2.1 红线 4 的由来：四渠道对照验证实测发现的越界

**初版实现**的判定是「开关开启 + `!passwordSupported()` + 携口令」→ 降级。2026-09-23 四渠道 curl 对照验证暴露出：

```
FACE 正确口令 | ✅ 成功 downgrade=True userId=900000000000000001
```

即 **FACE 渠道被顺手打通了**。这不是"多支持了一个渠道"，而是：

- 人脸渠道的 `configured()=false` 是**合规前置未办结**（DPO 单独同意版本、PIA 增补、服务商选定三项齐备才可打开，见 `FaceChannelAdapter` 注释）；
- 按"非账密渠道"泛化降级，等于**用一个开发期开关绕过了 DPO 会签**——虽然仍强制口令校验（不是免密），但"未完成单独同意与影响评估即开放人脸关联登录"这一事实本身已越界；
- 影响面也超出需求（需求只要 WECHAT/DOUYIN）。

**修法**：引入渠道白名单 `ALLOWED_CHANNELS`，`ChannelDowngradeSwitch.allows(channel)` 取代 `enabled()` 判定；并补**格6**（FACE 与 WECHAT **同构场景对比**：同为一"已注册但未配置 + 开关开启 + 口令正确"，WECHAT 降级成功而 FACE 必须仍被拒）。

> **这条教训的可复用价值**：任何"以渠道属性推断行为"的开关，都要问一句"这个属性的取值集合里，是否混进了**因其它原因**而为该值的成员"。`passwordSupported=false` 同时包含了"暂未接入"（WECHAT/DOUYIN）与"有意不接入"（FACE）两种语义完全不同的情形。

### 2.3 为什么红线 2 是本卡最大的坑

原实现的口令比对条件是：

```java
if (channel.passwordSupported() && !passwordMatched(user, request.getPassword())) { ... }
```

平台渠道的 `passwordSupported()` 为 `false`，**若照抄该条件**，降级路径会**整段跳过口令校验** —— 任何知道 `WECHAT` 渠道名的人传任意口令即可登录任意已存在账号。这不是配置疏漏，而是**一行条件写错就成立的越权面**。

因此本卡以 **6 格单测**（`AuthServiceChannelDowngradeTest`）锁死，并做了**变异验证**：

> 注入变异（把条件改回 `channel.passwordSupported()`，模拟"跳过口令校验"）→ 格3 `wrongPasswordMustBeRejected` **立即失败**（`Tests run: 5, Failures: 1`）。
> 证明该测试确实能拦住这个坑，不是自证同义反复。

6 格清单：

| 格 | 场景 | 期望 |
|---|---|---|
| 1 | 开关关闭 + WECHAT + 带口令 | 拒绝（走原路径"渠道未配置"，**行为与改造前完全一致**） |
| 2 | 开关开启 + WECHAT + **不带口令** | 拒绝（不降级） |
| 3 | 开关开启 + WECHAT + **错误口令** | **拒绝**，且命中"口令错误"留痕 ★核心格 |
| 4 | 开关开启 + WECHAT + 正确口令 | 成功，`channelDowngrade=true` |
| 5 | 开关开启 + IOS（账密渠道） | 成功，`channelDowngrade=false`（账密渠道永不标记降级） |
| 6 | 开关开启 + **FACE** + 正确口令 | **拒绝**（不在白名单）★白名单格 |

> 口令比对面用**真实 `BCryptPasswordEncoder`** 而非 mock：若 mock 掉 `PasswordEncoder`，格3/格4 会退化为同义反复（与 CR-M2-003"Mock 掩盖真实缺陷"同类教训）。

### 2.4 审计标记

`LoginResponse` 新增 `channelDowngrade: boolean`：端侧可据此提示"当前为联调通道"，**且不得据此放松任何本地校验**。生产恒为 `false`。

`login_log` **保持渠道原值**（`login_type=WXMP`、`device_type=WXMP`），另以应用日志 WARN 补充"本次为账密回退"事实：

```
开发期渠道降级登录：channel=WECHAT username=assessor_dev（平台适配器未接入，已强制 BCrypt 口令校验）
```

**为什么不改 login_log 结构**：加列需走 DDL 补丁 + 门禁同步流程，而该通道**本就是开发期临时物**（平台渠道接入后应删除），不值得在正式审计表上留下永久的语义妥协。

---

## 三、落地清单

| # | 文件 | 改动 |
|---|---|---|
| 1 | `yl-module-account/.../config/ChannelDowngradeSwitch.java` | **新建**：独立开关组件（`@Value` 读配置，默认 false）+ **渠道白名单 `ALLOWED_CHANNELS={WECHAT,DOUYIN}`** + `allows(channel)` |
| 2 | `.../service/AuthService.java` | 加 `shouldDowngrade()` / `resolveDowngradeUsername()`；口令比对条件加 `\|\| downgrade`；响应回标记；WARN 留痕 |
| 3 | `.../dto/LoginResponse.java` | 新增 `channelDowngrade` 字段 |
| 4 | `yl-bootstrap/.../application.yml` | `yl.auth.channel-downgrade.enabled` 默认 **false** |
| 5 | `yl-bootstrap/.../application-dev.yml` | 覆盖为 **true**（含"不得出现在 prod"的显式告警注释） |
| 6 | `yl-module-account/src/test/.../AuthServiceChannelDowngradeTest.java` | **新建**：6 格单测（含 FACE 白名单格） |

**未做（有意为之）**：不改 `LoginChannel` 枚举的 `passwordSupported`（改它会把开发期权宜固化成生产语义）；不动平台适配器；不加 DDL。

---

## 四、验收

| # | 验收项 | 判定 |
|---|---|---|
| 1 | 单测 | `AuthServiceChannelDowngradeTest` **6/6**；account 模块 **39/39** |
| 2 | 变异验证 | 去掉 `\|\| downgrade` → 格3 失败（测试有效性证明） |
| 3 | 四渠道对照（重启后 curl 实测） | `ANDROID`/`IOS` 成功且 `channelDowngrade=false`；`WECHAT`/`DOUYIN` **带口令**成功且 `channelDowngrade=true`；**WECHAT 错误口令被拒**；**WECHAT 未携口令被拒**；**FACE 带正确口令仍被拒** |
| 4 | 生产安全 | `application.yml` 默认 false；prod profile 无覆盖 |

---

## 五、残留与后续（必须闭环，不得遗忘）

| # | 事项 | 说明 |
|---|---|---|
| R1 | **平台渠道接入后删除本通道** | 微信/抖音 app-id/app-secret 配置齐备、`configured()=true` 后，降级分支即成历史包袱；应连同开关与 `channelDowngrade` 字段一并移除 |
| R2 | **上线检查项** | 生产环境变量中**不得**出现 `AUTH_CHANNEL_DOWNGRADE=true` |
| R3 | `login_log` 不可区分降级登录 | 如需事后审计区分，应补列（走 DDL 补丁流程），或以应用日志检索 |
| R4 | 短信渠道 R-1 备案未办结 | 与本卡无关，但同属"渠道未接入"族，勿混淆 |

---

## 六、闭环记录

| 日期 | 事项 | 结果 | 确认人 |
|---|---|---|---|
| 2026-09-23 | 实测四渠道登录能力，暴露 WECHAT/DOUYIN 不可用 | ✅ 证据见 §一 | 研发侧 |
| 2026-09-23 | 实施 dev-only 降级 + 5 格单测 + 变异验证 | ✅ 完成 | 研发侧 |
| 2026-09-23 | 四渠道 curl 对照验证 | ✅ 通过（见 P1-5 实施记录） | 研发侧 |
| — | R1 平台渠道接入后移除本通道 | ⏳ 待办（随微信/抖音平台渠道接入） | 研发侧 |
