# 合规红线守卫 · 突变反证门禁规矩（v1.0）

> 文档编号 **YL-M2-QG-REDLINE-MUTATION**｜定稿 2026-10-10｜状态：**✅ 生效**
> 触发：**同一类缺陷已第三次复发**（D5-R1 → P2-7-R6 → P2-8 M3/M4/M13），用户 2026-10-10 指令「第 3 步」。
> 关联：`scripts/verify-schema.sh`｜`scripts/verify-retention-chain.sh`｜`scripts/dev/*mutation*.py`｜`docs/quality/verify-schema-assertion-reconciliation.md`

---

## 0 TL;DR

**任何承载合规红线的行为，新增或修改时，必须随批附一条「摘掉守卫即变红」的断言，并用一次真实突变证明它确实变红。**

只写「守卫存在」的断言（字段在、注解在、配置值为 X）**不算**——它证明不了任何东西。

---

## 1 为什么立这条规矩：三次复发

| 次 | 缺陷 | 症状 | 后果 |
|---|---|---|---|
| 1 | **D5-R1** | MyBatis 手写 SQL 的 WHERE 条件（幂等/状态/`deleted` 守卫）在 mock 单测下**不可见** | 摘掉 `archived_at IS NULL` / `status = 2` / `deleted = 0`，`mvn verify` **仍 270/0 全绿** |
| 2 | **P2-7-R6** | `expire_at IS NOT NULL` 在**行为层不可区分**（`NULL < cutoff` 求值 UNKNOWN，本就排除该行） | 摘掉它门禁不变红 ⇒ 只能靠**静态守卫**（读注解文本）兜底 |
| 3 | **P2-8 M3/M4/M13** | 「Redis 键不含明文手机号」**无任何断言**；`LoggingSmsSender` **零测试**（dev 开关只有 yaml 值门禁、无行为门禁）；`phone:60s` 维度无断言 | 把 `hashPhone` 改成 `return phone;`（**明文手机号进 Redis**）门禁**全绿** |

**共性**：**守卫是真的，断言是空的。** 门禁对这类语义**零区分力**，而它们恰好都压在合规红线上。
⇒ 不立规矩，**第四次复发只是时间问题**。

---

## 2 规则（强制）

### 2.1 适用范围 —— 「合规红线行为」清单

**凡是下表任一类行为的新增或修改**，本规矩适用（不限于 retention）：

| # | 红线类 | 典型守卫 | 典型「摘掉守卫」突变 |
|---|---|---|---|
| R1 | **加密 / 密钥** | AES-GCM 封装、KMS 注入、禁明文落库 | 把 `encrypt(x)` 换成 `x` |
| R2 | **哈希 / 摘要** | `hashForSearch` 用于等值检索、键构造 | `return hash(x)` → `return x` |
| R3 | **脱敏 / 掩码** | `MaskUtils.maskPhone` 等 | 把 `maskPhone(x)` 换成 `x` |
| R4 | **日志抑制** | dev 开关、`[DEV-ONLY]`、敏感字段不落日志 | 关掉开关分支仍打印 / 去掉抑制条件 |
| R5 | **权限判定** | `@RequiresPermission`、四闸、数据域处理 | 摘注解 / 让处理器恒放行 / 未知值落最宽分支 |
| R6 | **软删 / 留存 / 归档** | `deleted = 0`、`retain_until`、`archived_at IS NULL` | 摘 WHERE 条件 / `SET deleted = 1` 表业务态 |
| R7 | **外发 / 一次性凭据** | 频控、一次性核销（GETDEL/Lua）、fail-closed | 摘频控维度 / 校验不 DEL / 通道不可用仍返成功 |
| R8 | **幂等 / 状态机** | 幂等键、状态守卫、唯一键冲突转 `10005` | 摘状态守卫 / 唯一键冲突不捕获 |

### 2.2 交付物（**缺一不可**）

1. **一条行为断言** —— 必须**直接观测行为**，不得只观测配置或结构：
   - ✅ 读**日志输出**（如 `LogCapture.messagesOf`）、捕获**方法入参**（`ArgumentCaptor`）、查**存储键/值**（Redis）、验**响应体与 `R.code`**；
   - ❌ 只断言「字段存在」「注解在」「`application.yml` 里值为 false」；
   - ★ 断言须**钉死期望值**（如 `assertEquals(codec.hashForSearch(PHONE), storedKey)`），而非「不等于明文」这类**弱否定**（弱否定可被"另一个错值"绕过）。
2. **一次突变反证** —— 摘掉/改坏守卫 ⇒ 该断言**必须变红**；并记录：突变内容、跑的命令、**红点断言名**、**还原后 md5 一致**。
3. **落盘** —— 证据写入该卡的 QA 报告或变更单（表格形式），并登记到本文 §4 的覆盖清单。

### 2.3 与既有规矩的关系

- **扩展**（不取代）：原「新增/修改 retention 类手写 SQL 必须同步补 IT 用例」→ 归入本规矩 **R6** 的一个特例；
  `RetentionChainDbIT` + `verify-retention-chain.sh` 仍是该类的落地工具。
- 本规矩**同时管**「有 UI/HTTP 面」与「纯内部实现」的红线行为。

---

## 3 执行细则（工具坑，照做）

### 3.1 突变跑测试的必备参数

```bash
mvn -o -pl <module> -am test -Dtest=<TestClass> \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dspotless.check.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true
```

| 参数 | 为什么必须 |
|---|---|
| `-Dspotless.check.skip=true` | 突变常改变行长度 ⇒ **Spotless 在 test 相位前先失败**，`BUILD FAILURE` 与断言无关（**测试压根没跑**）——已踩过 |
| `-Dcheckstyle.skip=true -Dspotbugs.skip=true` | 摘一行常产生未用 import / 多余 suppression ⇒ 让红点**只可能来自测试** |
| `-Dsurefire.failIfNoSpecifiedTests=false` | `-am` 会连带跑上游模块；无此参数上游会因「No tests matching pattern」**误报失败** |

### 3.2 自动判定器必须自证（★ 2026-10-10 新增）

自建突变判定脚本时：

- **`mvn` 在 Windows 上是 GBK 输出**；`subprocess(text=True)` 按 UTF-8 解码会抛 `UnicodeDecodeError` ⇒ **判定分支从未执行** ⇒ 把「该红的突变」全判成 GREEN。
  修法：读**字节** + `decode("utf-8", errors="replace")`。
- **判定主据用退出码**，不用文本匹配。
- **判定器必须先跑哨兵**（一个"必然红"的输入）自证判定路径被走到。
  > 教训：**「判定器自己崩了」会伪装成「缺陷不存在」**，比假绿更隐蔽——它看起来像结论，而不是报错。

### 3.3 还原纪律

- 突变前后**逐字节备份**；还原后 **md5 必须一致**（不一致 = 污染工作区，立即停下排查）。
- 跑完 `git status --porcelain` 确认**无残留修改/新增**。

### 3.4 反面清单（这些不算数）

- ❌ 断言「配置值为 false」而不验「行为确已抑制」；
- ❌ 断言「字段/注解存在」而不验「它真的在拦」；
- ❌ 断言「不等于明文」而**不**钉死期望摘要值；
- ❌ 只跑一次「全绿」就声称门禁有效（**没有突变证据 = 未验证**）；
- ❌ 突变后不还原 / 不校验 md5。

---

## 4 覆盖清单（截至 2026-10-10）

| 红线类 | 承载实现 | 断言落点 | 突变反证 | 状态 |
|---|---|---|---|---|
| R2 哈希（短信键） | `SmsCodeService#hashPhone` | `SmsCodeServiceTest#storageAndRateLimitKeysNeverContainPlaintextPhone` | M4' | ✅ |
| R3 脱敏 | `LoggingSmsSender` / `UnavailableSmsSender` | `LoggingSmsSenderTest` / `UnavailableSmsSenderTest`（logback 日志断言） | M13' / M14' | ✅ |
| R4 日志抑制 | `LoggingSmsSender`（`dev-log-code`） | `LoggingSmsSenderTest#hidesCodeWhenDevLogCodeDisabled` + `SmsDevLogCodeConfigTest`（配置值门禁） | M13' | ✅ |
| R7 频控 | `SmsCodeService#enforceRateLimits` | `SmsCodeServiceTest#sendChecksSixtySecondPhoneWindowExplicitly` / `#sendRateLimited` | M3' | ✅ |
| R7 一次性核销 | `RedisSmsCodeStore#consumeIfMatches`（Lua） | `RedisSmsCodeStoreTest` | QA M1/M2 | ✅ |
| R7 fail-closed | `UnavailableSmsSender` / `enabled=false` | `UnavailableSmsSenderTest` / `SmsCodeServiceTest#sendFailClosedWhenDisabled` | QA M8 | ✅ |
| R6 留存（SQL） | 4 个 retention Mapper | `RetentionChainDbIT` T1–T9 + `verify-retention-chain.sh` | D5 M1–M5 / P2-7 M1–M7 | ✅ |
| R5 权限判定 | `PermissionAspect` / `DataScopePermissionHandler` | `PermissionAspectTest` / `DataScopePermissionHandlerTest` / `ControllerAuthorizationCoverageTest` | D-4 M7/M10 | ✅ |
| R2 密钥（内部） | `SensitiveFieldCodec`（AES-GCM + HMAC） | **零专属测试**（`find -name SensitiveFieldCodecTest.java` 全仓 0 命中；仅被 `SmsCodeServiceTest`/`SuperviseExportServiceTest` **间接**引用） | — | 🚫 **缺失 → 已登记 `P2-8-R10`**（补法见该行：加密输出非明文/两次不同 IV/`decrypt∘encrypt==id` + 摘要逐字节等于已知向量；突变「摘 HMAC」/「`encrypt`→`return plain`」） |

> ★ 每次按本规矩交付后，**必须在本表追加一行**。表里没记 = 没做。

---

## 5 检查时机（DoD）

| 时机 | 动作 |
|---|---|
| 写/改任一 §2.1 类行为 | 同批写断言 + 跑突变 + 记录 |
| 提交前 | 确认突变已还原（md5）、`git status` 干净 |
| QA 独立验证 | 至少复核「该红是否真红」；发现空断言 ⇒ 报告为**缺口**，不得放行 |
| 卡片关账 | 在本文件 §4 覆盖清单追加行 |
