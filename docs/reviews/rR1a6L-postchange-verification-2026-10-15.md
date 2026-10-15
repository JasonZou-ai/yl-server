# rR1a6L 四端第一批改造 · 改造后独立复核报告（2026-10-15）

> 授权：用户 2026-10-15 明确「**同意按此继续**」（群内 00:32 亦回复「同意按建议执行」，闭环判定 `APPROVE`）⇒ 授权推进 `rR1a6L` 端侧改造。
> 前置核查：[`rR1a6L-precheck-verification-2026-10-14.md`](./rR1a6L-precheck-verification-2026-10-14.md)（改造前 A/B/C 组 8 项未通过）
> 验收判据：[`CR-M2-009-rR1a6L-acceptance-checklist.md`](./CR-M2-009-rR1a6L-acceptance-checklist.md)（§二 + §六 S-1~S-5）
> **方法**：全部由 Lead **亲自复算**（不采信交付方自报）；交付方自报与实测不符处**逐条订正**。

---

## 一、交付物

| 项 | 位置 / 说明 |
|---|---|
| 交付目录 | `2026-09-08-13-00-34/stage3_开发/_改造-rR1a6L/` |
| 改前快照 | `backup/`（**147 个文件**，四个端侧 app 目录结构，仅供字节级回滚） |
| 分层补丁 | `patches/{A-角色枚举, B-兜底路由, C-老人读面, D-安卓原生}/` 共 **43 个** `.diff`（UTF-8 无 BOM、LF、`--unified=3`，新文件走 `/dev/null`） |
| 台账与自检 | `README.md`(31,007B) · `md5-table.tsv` · `_baseline-md5.tsv` · `_delivered-md5.tsv` · `self-test.ps1` · `self-test-result.tsv` · `apply-test.js` |
| 约束 | **后端仓库零改动**（`git status` 干净）；**未开/未改任何权限**（2008 保持冻结）；未改 `.env` / `openapi/` / 种子；未新增依赖 |

---

## 二、Lead 独立复核（8 项，全部亲跑）

| # | 复核项 | 我的方法 | 结果 |
|:--:|---|---|:--:|
| 1 | **三端共享文件一致性** | 亲自算 md5（8 个共享文件 × 3 端） | ✅ 每个文件 **unique=1**：`role.ts 734d89a2…` · `auth.ts 793e50c5…`（**改造前 3 端互不相同**，本次主动统一）· `real-api.ts d33b60d2…` · `mock-api.ts b6169724…` · `elder-home.vue 4dde8102…`（新增）· `family-reports.vue d8e3af99…` · `archive.vue bf94cf10…` · `build-flag.ts 5a35c83b…`（**B4 未回退**，与改造前一致） |
| 2 | **S-1 兜底穷尽替换** | 四根 `src` grep `role === 'FAMILY' ?` | ✅ 命中 **9 处全部是注释**（`auth.ts:13`、`role.ts:14/:20` 各端 3 处，均为旧实现说明/验收判据引述）⇒ **代码层 0** |
| 3 | **Kotlin 兜底已删** | grep `else ROLE_ASSESSOR` / `ROLE_ELDER` | ✅ `else ROLE_ASSESSOR` 仅 1 处（`MockApiClient.kt:29` 旧实现注释）；`ROLE_ELDER` **5 处**（`FamilyUtils.kt` 常量+判定、`ApiClient.kt` 注释、`LoginScreen.kt`、`ElderHome.kt`） |
| 4 | **C-2 不得直读子表** | grep `report_dimension_score` | ✅ 命中 **4 处全为禁止性注释**（`elder-home.vue:34` ×3端、`ElderHome.kt:164`）⇒ 无实际调用 |
| 5 | **角色分发行为断言** | 亲跑 `node apply-test.js <端>`（**node 全路径**） | ✅ **三端各 32 PASS / 0 FAIL / exit 0**（共 96 项）：`ELDER→elderHome`、`FAMILY→familyHome`、`ASSESSOR→assessorHome`、**`''`/`ORG_ADMIN`/`SUPERVISOR`/`elder`(小写)/`undefined` → login**；`displayNameOf(未知)→'用户'`、`avatarOf→'·'`、`tabsOf(未知)→[]`、`tabsOf('ELDER') ≠ ASSESSOR_TABS/FAMILY_TABS`、ELDER tabs 无评估/档案入口。<br>★ **订正交付方自报**：其称「三端 36 项」，实测为 **每端 32 项**（脚本默认仅测 weixin-app，需传参才测另两端；我已补跑三端） |
| 6 | **补丁链可应用/可回滚** | **Lead 自写 PowerShell 独立实现**（不跑其 `self-test.ps1`）：临时副本 ← `backup/` → 按 `A→B→C→D` 目录升序应用 43 个补丁 → 逐文件 md5 对比交付态 → 反序 `-R` 43 个 → 对比 `backup/` 基线 | ✅ **应用 43/43 成功（0 失败）· 与交付态差异 0**；**回滚 43/43 成功（0 失败）· 与基线差异 0**；含 **4 个新增文件**（三端 `elder-home.vue` + `ElderHome.kt`）⇒ **PATCH-CHAIN-OK** |
| 7 | **产物级（三端生产构建）** | 亲自 grep `dist/build/{mp-weixin, mp-toutiao, app}` | ✅ `内测`=**0** · `facePassed`=**0** · `400-000-0000/0871/0891`=**0** · 手机号正则 weixin/douyin=**0**、iOS **3 处**经逐字节核对为 vendor 常量 `9007199254740991` 的 `19925474099` 子串（**非号码**）· **`elder-home` 已入三端产物**（weixin/douyin 各 3 文件、iOS 2 文件）· `utils/build-flag.js` = `exports.IS_DEMO_BUILD=!1`（**生产替换生效**） |
| 8 | **安卓编译** | 亲跑 `gradlew :app:compileDebugKotlin --offline`（JDK 17 / Gradle 8.9 / SDK 齐备） | ✅ **BUILD SUCCESSFUL**（交付方另跑 `assembleDebug` 得 APK 10,869,886B） |

### 补充：C-4 不变式的回归证据（Lead 亲跑，非端侧改动）

```
mvn -o -B -pl yl-modules/yl-module-evaluation,yl-modules/yl-module-account -am test \
  -Dtest=AssessmentReportServiceTest,AccountAggregateServiceTest,DataScopePermissionHandlerTest \
  -Dsurefire.failIfNoSpecifiedTests=false        ⇒ BUILD SUCCESS
```
`DataScopePermissionHandlerTest` **26/0** · `AccountAggregateServiceTest` **6/0**（锁定「`scope=1` ⇔ 纯 ELDER」不变式）· `AssessmentReportServiceTest` **9/0**（M3 状态闸）⇒ **机构管理员/评估员仍可读 `status=0/2`，未被误伤**。

---

## 三、逐项判定（Lead 视角）

| 组 | 项 | Lead 判定 | 依据 |
|:--:|:--:|:--:|---|
| A | A-1 / A-2 / A-3 | ✅ **源码+产物通过** | 复核 1/2/3/5/7 |
| B | B-1（含 S-1 五处穷尽）/ B-2 | ✅ **源码+产物通过** | 复核 2/3/5 |
| C | C-1 / C-2 / C-3 | ✅ **源码+产物通过** | 复核 4/5/7 |
| C | C-4（M3 状态闸回归） | ✅ **机制未动 + 回归全绿** | §二补充 |
| D | D-1 / D-2 / D-3（实机） | ⏸ **不可执行** | 无真机/真实账号；且后端未供给「本人报告列表」端点。**D-3 的源码级负例已断言通过**（未知/空角色 → `/pages/login/login`） |

> ⚠ **本报告不等于验收通过**：按清单 §四/§五，**验收结论仍由验收人出具**（真机负例、端侧联调未闭环）⇒ **2008 写权限保持冻结**，完整生产包继续阻断。

---

## 四、残留与未闭环（诚实登记）

| # | 项 | 影响与处置 |
|:--:|---|---|
| 1 | **后端未供给「本人报告列表」端点**，且契约 `/family/bindings*` 的 `by-role` 未标 `ELDER` | 端侧 `getMyReportsAsElder()` **显式抛错**，页面渲染「报告列表暂不可用 + 联系机构」，**绝不造数、不静默降级**（符合红线）。后端补齐后只需替换该函数实现，**UI 零改动**。已登记 **B5-后端** 同批残留（`BACKLOG-M2-P1P2.md`） |
| 2 | 小程序页面**静态注册不拒载** | 靠**页面内守卫**（`home.vue`/`archive.vue`/`care-plan.vue`/`elder-home.vue`/`profile.vue` 分支）拦截；`home.vue` **未加渲染期兜底**（其数据源为本地静态语料 ⇒ 仅存在一帧渲染窗口），已如实登记 |
| 3 | PDF 导出 | 端侧仅**显式说明按钮**，文件能力未接入（老人域 PDF 源在报告详情） |
| 4 | 真机项 D-1/D-2/D-3 | 未跑（无设备/账号） |
| 5 | 平台特有文件 md5 三端不同 | **属预期**（`pages.json`/`login.vue`/`profile.vue`/`home.vue`/`care-plan.vue` 各自实现），已按「同行为」而非「同字节」验收 |
| 6 | 未跑 h5 构建 | 三端已跑各自平台形态（`mp-weixin`/`mp-toutiao`/`app`），h5 未跑（不影响本轮判据） |
| 7 | 交付方自检脚本默认只测一端 | 已在 §二#5 订正；Lead 已补跑三端 |

---

## 五、回滚方法

```bash
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.autocrlf GIT_CONFIG_VALUE_0=false   # 本机 autocrlf=true，必须关
cd 2026-09-08-13-00-34/stage3_开发
# 回滚（反序，逐目录倒序 + 文件名倒序；任一层可在其上层已应用时单独 -R）
git apply -p1 --unidiff-zero --ignore-whitespace -R _改造-rR1a6L/patches/D-安卓原生/<最后一个>.diff
# …依此 B→A 反向逐个 -R
# 字节级兜底（不依赖 git）
Copy-Item '.\_改造-rR1a6L\backup\*' '.' -Recurse -Force
```
> 判据：回滚后与 `_baseline-md5.tsv` 逐文件一致；Lead 已独立验证「全量回滚 = 基线」（差异 0）。

---

## 六、结论

**A/B/C 三组改造在源码、产物、行为断言、补丁链、安卓编译五个层面均通过 Lead 独立复核；D 组实机项不可执行；C-4 不变式未触碰且回归全绿。**
⇒ 交付物可提交验收人复验（真机 + 联调）；**在验收结论出具前，2008 写权限继续冻结、完整生产包继续阻断**。
