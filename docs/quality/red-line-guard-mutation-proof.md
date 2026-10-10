# 合规红线守卫 · 突变反证门禁规矩（v1.0）

> 文档编号 **YL-M2-QG-REDLINE-MUTATION**｜定稿 2026-10-10｜**v1.1 修订 2026-10-10（补第 4 种盲区形态：环节缺失）**｜**v1.2 修订 2026-10-10（补第 5 种盲区形态：本机门禁 ≠ CI 门禁）**｜**v1.3 修订 2026-10-10 晚（第 5 盲区收口补强：验证同源化 + 假 ready 真实化）**｜状态：**✅ 生效**
> 触发：**同一类缺陷已五次复发**（D5-R1 → P2-7-R6 → P2-8 M3/M4/M13 → **P2-8-R14** → **CI commit-lint/SpotBugs 红**），用户 2026-10-10 指令「第 3 步」＋ 同日指出 CI 缺「部署验证」环节，同日晚实测 main 上 CI 连续 failure 而本机四门禁全绿。
> 关联：`scripts/verify-schema.sh`｜`scripts/verify-retention-chain.sh`｜`scripts/dev/*mutation*.py`｜`docs/quality/verify-schema-assertion-reconciliation.md`｜`.githooks/commit-msg`（与 CI 复用同一脚本）｜`scripts/verify-like-ci.sh`（本机验证同源化）

---

## 0 TL;DR

**任何承载合规红线的行为，新增或修改时，必须随批附一条「摘掉守卫即变红」的断言，并用一次真实突变证明它确实变红。**

只写「守卫存在」的断言（字段在、注解在、配置值为 X）**不算**——它证明不了任何东西。

---

## 1 为什么立这条规矩：五次复发

| 次 | 缺陷 | 症状 | 后果 |
|---|---|---|---|
| 1 | **D5-R1** | MyBatis 手写 SQL 的 WHERE 条件（幂等/状态/`deleted` 守卫）在 mock 单测下**不可见** | 摘掉 `archived_at IS NULL` / `status = 2` / `deleted = 0`，`mvn verify` **仍 270/0 全绿** |
| 2 | **P2-7-R6** | `expire_at IS NOT NULL` 在**行为层不可区分**（`NULL < cutoff` 求值 UNKNOWN，本就排除该行） | 摘掉它门禁不变红 ⇒ 只能靠**静态守卫**（读注解文本）兜底 |
| 3 | **P2-8 M3/M4/M13** | 「Redis 键不含明文手机号」**无任何断言**；`LoggingSmsSender` **零测试**（dev 开关只有 yaml 值门禁、无行为门禁）；`phone:60s` 维度无断言 | 把 `hashPhone` 改成 `return phone;`（**明文手机号进 Redis**）门禁**全绿** |
| 4 | **P2-8-R14** | **整个环节不存在**：CI 5 个作业（提交信息 / 格式静态 / DDL 落地 / 构建测试 / Sonar）**没有一个启动过镜像**，而 `Dockerfile` 默认 `prod`、仓库却无 `application-prod.yml`（datasource/redis 只配在 dev） | **5/5 全绿产出的镜像根本起不来** —— 不是断言覆盖不到，是**没有任何一步在做这件事** |
| 5 | **本机门禁 ≠ CI 门禁（2026-10-10 实测）** | ① `core.hooksPath` 为空 ⇒ **本地 commit-msg 钩子从未安装**，三个逗号 scope 提交（`docs(a,b):`）本地畅通、CI commit-lint 红；② SpotBugs 同一命令、同一插件版本（4.10.4.1），**CI 报 1 bug（`AuthService` 构造器 EI_EXPOSE_REP2），本机增量与 clean 全量两次复现均 0 bug** | **17 天存量推送后 CI 一次没绿过，本机四门禁却一直全绿** —— 「本机绿灯」与「CI 绿灯」不是同一盏灯，前四种盲区的全部本机验证因此**失去预测力** |

**共性**：前三次是「**守卫是真的，断言是空的**」—— 门禁对这类语义**零区分力**；
**第四次是「环节根本不存在」** —— 它甚至不属于「断言」范畴：**流水线里没有一步在做那件事**，
所以再多的断言也救不了它。
**第五次最根本：连「本机跑过 = CI 会绿」这个前提本身都是假的** —— 两边门禁的**执行环境、钩子接线、判定结果**都可能分叉。⇒ 不立规矩，**第六次复发只是时间问题**。

### ★ 第 4 种盲区形态的修法（v1.1 新增）：「环节缺失」用「新增环节」修，不是用「新增断言」修

| | 前三种（断言缺口） | 第四种（环节缺失） |
|---|---|---|
| 缺陷本质 | 有守卫，但没有**观测它行为**的断言 | 有产物/流程，但**没有任何一步去运行/验证它** |
| 修法 | 补一条行为断言 + 突变反证 | **新增一个环节**（本例：CI `image-smoke-boot` 作业） |
| 突变反证对象 | 摘掉守卫 ⇒ 断言变红 | **摘掉环节所依赖的东西** ⇒ 新环节变红 |
| 反证手法 | 源文件突变 + md5 还原 | **产物级突变**：对构建出的 jar 副本 `jar uf` 摘掉 `application-prod.yml` 的中间件配置段 ⇒ prod 启动必须失败 |

**★ 新增门禁口径（v1.1）**：**凡流水线产出「可运行产物」（镜像 / 包 / 可执行文件），必须存在一个环节把它真的运行起来并探活，才算门禁通过。** 「构建成功」≠「能跑」；这个等价假设一旦成立，任何一处默认配置的漂移都会静默穿过全部既有断言。

### ★ 第 5 种盲区形态的修法（v1.2 新增）：「本机门禁 ≠ CI 门禁」——CI 是唯一权威绿灯

| | 前四种（断言缺口 / 环节缺失） | 第五种（本机 ≠ CI） |
|---|---|---|
| 缺陷本质 | 门禁覆盖不到某个角落 / 环节不存在 | **本机门禁与 CI 门禁的执行环境没有结构性绑定**：钩子未接（commit-lint 本地零执行）、判定分歧（SpotBugs 本机 0 bug / CI 1 bug，同一命令同一插件版本，根因未定位） |
| 修法 | 补断言 / 补环节 | **结构性接线 + 权威源唯一化**：① `git config core.hooksPath .githooks`（一行，之后本地提交不过规范就**提交不了**，钩子从「文档声称」变「物理生效」）；② **验收判据只认 CI run**，本机绿灯仅作开发期快速反馈 |
| 预防 | 突变反证 | 推送前**必跑 CI 同款命令**（`mvn -B -ntp -DskipTests compile spotbugs:check`）作预检——但必须明确：**预检绿 ≠ CI 会绿**（第 5 次实测反例），预检只过滤「确定会红」的低级失误 |

**★ v1.2 门禁口径**：**「本地四门禁全绿」永远不能写进交付结论替代 CI 结论；凡对外报告「已闭环」，必须附对应 CI run 的作业级结论（每个作业 success 或说明为何跳过）。** 状态表里写了「✅」但没有 CI 证据的，视同「没做」——本规矩 §4 的「表里没记 = 没做」同样适用于**记了但没证据**。

**★ 本例的两个具体教训**：
1. **钩子存在 ≠ 钩子生效**：`.githooks/commit-msg` 在仓库里躺着、CI 注释说「与本地复用同一份脚本」，但 `core.hooksPath` 为空 ⇒ 本地那一半从未接线。**「口径唯一」的前提是两边都真的在跑**。
2. **SpotBugs 判定分歧的诚实处置**：本机（Windows / JDK 17.0.20.1 / Maven 3.9.16）用 CI 同款命令、增量与 clean 全量两次均 **0 bug**，CI（Linux / temurin 17）报 **1 bug**（`AuthService` 12 参构造器 EI_EXPOSE_REP2）——**根因未定位**（候选：JDK patch 版本差异引起的字节码/判定分歧）。修复不依赖根因定位：按项目既有范式（`RedisTokenBlacklistStore` 等 10+ 处）以 `@RequiredArgsConstructor(onConstructor_ = @SuppressFBWarnings(value="EI_EXPOSE_REP2", justification=…))` 豁免，豁免在 CI 生效、在本机无副作用。**另注意 SpotBugs check 是模块级 fail-fast**：红在 `yl-module-account` 时其后 5 个模块从未被扫过 ⇒ 修一个红点后 CI 可能继续暴露下一个，属正常收敛过程，不是修复无效。

### ★ 立规当天就验证了自己（2026-10-10，同批补 R10）

按本规矩给 `SensitiveFieldCodec`（全仓红线等级最高、此前覆盖为零的类）补专属测试时，**立刻揪出一处真实缺陷**：

- `requireKey` 只校验「**≥**32 字节」，但 AES-256 只接受**恰好** 32 字节 ⇒ **超过 32 字节的主密钥能通过构造期校验，却到首次加解密才抛异常（fail-late）**。
  症状是"所有加密端点集体报错"，而配置检查一路绿灯 —— 与"静默降级"同族。已修（fail-fast）并登记 **P2-8-R13**。
- 更直接的证据：修完后我在**测试里**写了一个 34 字节的备用密钥，**新加的 fail-fast 当场把我自己拦下**。

⇒ 这不是"规矩有用"的修辞，而是**当天可复现的收益**：一条"摘掉守卫即变红"的断言，产出比它本身的成本高得多。

### ★ 同批第三次收益：突变必须「红在职责对得上」的用例上（红点归属）

跑 **G6**（`encrypt` 退化为明文透传）时发现红点里混进了一个 **R2 摘要组**的用例
`masterKeyDoesNotAffectDigest` —— 原因是它末尾夹带了一条 `assertThatCode(() -> b.decrypt(b.encrypt(PHONE))).doesNotThrowAnyException()`：

- **红点错位**：一条「**加密**」突变打红了一个「**摘要**」用例 ⇒ 只看红点名会误判病因。
- **弱否定**：`doesNotThrowAnyException` 正是 §2.2 明令禁止的**弱否定**（"没抛异常"可由任意路径满足）。

修法是把该断言**移入 R1 组**并**钉死期望值**（`assertThat(other.decrypt(other.encrypt(x))).isEqualTo(x)`），
R2 用例只留摘要断言。**复跑 G6 后红点 4 条全落在 R1 组、R2 组全绿**（14 → **15 例**）。

⇒ **新增门禁自检口径：跑突变时，除了「是否变红」，还要看「红在谁身上」。**
红点名与突变语义不同组时，说明用例职责被耦合了 —— 那是断言缺口，不是通过。

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
| **R1 加密 / R2 摘要** | `SensitiveFieldCodec`（AES-GCM + HMAC） | `SensitiveFieldCodecTest`（**15 例** = R1 加密/密钥 9 + R2 哈希/摘要 6：往返 / 换主密钥各自自洽 / 密文不含明文 / 每加密新 IV / 篡改必抛 / 换密钥解不开 / 密钥缺失 fail-closed / 主密钥长度 fail-fast / 摘要钉死 HMAC 向量且 ≠ 裸 SHA-256 / 摘要随 hashKey 变 / 密钥分离 / 不做静默归一化） | **G4 / G5 / G6** | ✅（2026-10-10 立规当天补齐，闭环 **P2-8-R10**） |
| **R1 密钥（启动 fail-closed）** | `DevCredentialStartupGuard`（泄露散列金丝雀 + 非 dev 禁用联调账号） | `DevCredentialStartupGuardTest`（6 例） | **G1 / G2 / G3** | ✅（闭环 **P2-8-R12**，修复 **P2-8-R9**） |

> ★ 每次按本规矩交付后，**必须在本表追加一行**。表里没记 = 没做。

---

## 5 检查时机（DoD）

| 时机 | 动作 |
|---|---|
| 写/改任一 §2.1 类行为 | 同批写断言 + 跑突变 + 记录 |
| 提交前 | 确认突变已还原（md5）、`git status` 干净 |
| QA 独立验证 | 至少复核「该红是否真红」；发现空断言 ⇒ 报告为**缺口**，不得放行 |
| 卡片关账 | 在本文件 §4 覆盖清单追加行 |

---

## 6 第 5 盲区收口补强（v1.3，2026-10-10 晚）

> 背景：v1.2 修绿了 CI，但两处「已闭环」被用户核实指认**没闭合**——① `PdfFontLocator.probe()` 仍是文件存在性判定（我们自己诊断出的假 ready 只换了字体、没换探针）；② SpotBugs 判定分歧根因未定位，第 5 盲区只是被 XML filter **掩盖**而非闭环。本节是收口动作的登记。

### 6.1 验证同源化：`scripts/verify-like-ci.sh`

**根因未定位 ≠ 无事可做。** 闭环的办法是让本机门禁与 CI 门禁**物理上是同一件事**：

```bash
bash scripts/verify-like-ci.sh static   # static-analysis 作业同款：同镜像 + fresh m2 + 同参数
bash scripts/verify-like-ci.sh full     # build-and-test 作业同款：verify（容器内自动补装 fonts-wqy-zenhei）
```

- 容器 `maven:3.9-eclipse-temurin-17`（与 CI 同 JDK 发行版）、命名卷 `yl-ci-fresh-m2` 模拟 CI 全新缓存（跨次复用，`docker volume rm` 重置）。
- **口径**：推送前跑 `static`（约 3 分钟）；凡本机绿 / CI 红的分歧，先跑本脚本再论——若本脚本也红，说明分歧在环境层可复现，诊断路径由此打通。
- 首跑实证（2026-10-10）：static 模式容器内 BUILD SUCCESS（fresh m2 首跑 4m32s，热卷 3m00s）。
- 遗留：SpotBugs 判定分歧的**根因**（本机 JDK 17.0.20.1 / Windows vs CI temurin 17 / Linux 为何对同一构造器结论相反）仍未定位；用户提示的线索（aux classpath 上 `SmsTicketStore` 的类型解析差异）**尚未走完**。在根因定位前，「本机绿」的预测力一律以本脚本为准。

### 6.2 假 ready 真实化：probe 口径升级（附突变反证 ×2）

`probe()` 从「文件存在性」升级为**全保真自检**（加载 → 绘制汉字 → `doc.save` 触发子集化，任一步失败即换下一候选；`UnsupportedOperationException`「OTF fonts do not have a glyf table」与 `IOException` 同等按不可用处理）。TTC 加载逻辑同步从 `ReportPdfService` 收敛进 `PdfFontLocator.loadFirst()`（唯一真源）。

| 突变 | 预期 | 实测 |
|---|---|---|
| M1：probe 摘掉 `renderable()`（退化回文件存在性） | `unrenderableFontNeverReportedReady` 红 | ✅ 红（`path == 垃圾文件`） |
| M2：TTC 分支短路（退化回直接 `PDType0Font.load(doc, File)`） | `realFontReportedReadyWithExactPath` 红 | ✅ 红（simsun.ttc 悄然落到 simhei.ttf——CI 38047512446 的事故形态） |

**★ 附带新口径（v1.3）**：**凡健康探针/自检宣称「就绪」，其判定必须与被宣称的能力同级**——「文件在」≠「能用」，探针必须真实行使一次被宣称的能力（加载/调用/执行），失败即如实报未就绪。同批补 `ReportPdfServiceTest` 中文文本抽取断言（PDFTextStripper 抽出文本须含标题），把「能加载」升级为「能正确渲染」。基线 341/0/0（+Skipped 1），净升 3。
