# rR1a6L · 2008 写权限开放前置**核查报告**（2026-10-14）

> 用途：`CR-M2-009` 四端**附条件同意的解除依据**；本报告为**改造前核查**（不放行、不改端侧代码）。
> 授权范围：用户 2026-10-14 授权 **C 项 = 只做核查与验收判据执行，不改权限本身、不改端侧代码**。
> 方法：**只读实测**（源码 `file:line` + 后端实现逐条对照）；**不采信任何「已支持 ELDER」的口头结论**。
> 清单原文：[`CR-M2-009-rR1a6L-acceptance-checklist.md`](./CR-M2-009-rR1a6L-acceptance-checklist.md)

---

## 一、核查结论（一句话）

**`rR1a6L` 未通过验收 ⇒ 2008 写权限继续冻结。** 11 项验收判据中：**1 项机制已具备（C-4）**、**7 项实测未通过**、**3 项实机项不可执行**（需真实设备/账号且依赖端侧改造）。
另发现 **两处清单未覆盖的问题**（见 §三），其中一处会直接导致改造「改一半、仍落评估员工作台」。

| 组 | 项 | 判定 |
|:--:|:--:|:--:|
| A | A-1 / A-2 / A-3 | ❌ 未通过（3/3） |
| B | B-1 / B-2 | ❌ 未通过（2/2，且**判据覆盖面不足**，见 §三.1） |
| C | C-1 / C-2 / C-3 | ❌ 未通过（3/3，其中 C-2 需在改真实接口时守住） |
| C | **C-4（M3 状态闸回归）** | ✅ **机制已具备**（见 §二.C-4） |
| D | D-1 / D-2 / D-3（实机） | ⏸ **不可执行**（无真实设备/账号；且端侧未改造，跑也必红） |

---

## 二、逐项实测证据（2026-10-14，改造前状态）

### A. 角色枚举缺 ELDER —— ❌ 未通过

| 项 | 实测位置 | 实测内容 |
|---|---|---|
| A-1a | `weixin-app/src/utils/role.ts:13` | `export type Role = 'ASSESSOR' \| 'FAMILY' \| ''` —— **仍无 `ELDER`** |
| A-1b | `weixin-app/src/stores/auth.ts:76` | `session.value.role = (r.role === 'FAMILY' ? 'FAMILY' : 'ASSESSOR')` —— **未知角色仍归 ASSESSOR** |
| A-1c | `weixin-app/src/pages/login/login.vue:16` | `const role = ref<'assessor' \| 'family' \| ''>('')` —— **登录页无「老人」选项** |
| A-2a | `android-native/app/src/main/java/com/yl/shouhu/util/FamilyUtils.kt:8-9` | 仅 `ROLE_FAMILY` / `ROLE_ASSESSOR`，**无 `ROLE_ELDER`** |
| A-2b | `android-native/…/data/api/ApiClient.kt:16` | 注释 `val role: String // ADMIN / ASSESSOR / FAMILY` —— **未含 ELDER** |
| A-3 | 三端 md5 | `role.ts`/`api.ts`/`request.ts` 三端 md5 **一致**（同步机制可用）；但**改造尚未开始** ⇒ 无「改造后一致」可验 |

### B. 兜底路由落入评估员工作台 —— ❌ 未通过（★ 覆盖面不足）

| 项 | 实测位置 | 实测内容 |
|---|---|---|
| B-1a | `weixin-app/src/utils/role.ts:41-42` | `return role === 'FAMILY' ? ROUTE.familyHome : ROUTE.assessorHome` —— **非 FAMILY 全落评估员工作台**（含未知/空角色） |
| B-1b 调用点 | `pages/splash/splash.vue:12`、`components/YlNavBar.vue:15` | 均走 `homeRouteOf(role)`（**同源**，改一处即生效 ✅ 这点清单判断正确） |
| B-2a | `MockApiClient.kt:28` | `val finalRole = if (role == ROLE_FAMILY) ROLE_FAMILY else ROLE_ASSESSOR` |
| B-2b | `MockApiClient.kt:40` | `return LoginTokens(…, ROLE_ASSESSOR)` —— 默认仍评估员 |

### C. 老人读面未收敛 —— ❌ 未通过

| 项 | 实测位置 | 实测内容 |
|---|---|---|
| C-1 | `role.ts:15-34` | `ROUTE` 常量**无 `elderHome`**、无老人专属页 ⇒ 老人无落地页 |
| C-2 | `pages/report/report.vue:102/171/224` | 维度得分仍取自本地 `rec.dims`，**未见直读 `report_dimension_score` 子表**（当前合规 ✅）⇒ **改造为真实接口时必须守住**，清单要求「走 D1-c 聚合」 |
| C-3 | 路由/TabBar | `role.ts:130` `tabsOf` 仍 `role === 'FAMILY' ? FAMILY_TABS : ASSESSOR_TABS` ⇒ 老人会被当成评估员拿 **ASSESSOR_TABS** |

### C-4 M3 状态闸回归 —— ✅ **机制已具备（本次核查的正面结论）**

| 判据 | 证据 |
|---|---|
| 状态闸只对**本人域**生效 | `yl-module-evaluation/…/AssessmentReportService.java:117-126` `assertPublishedToSelf()`：**首行即 `if (user == null \|\| !user.isSelfScope() \|\| report.getStatus() == null) return;`** ⇒ 非本人域**直接放行**，不做状态拦截 |
| `isSelfScope()` 定义 | `yl-api/…/security/LoginUser.java:75-77`：`dataScope == 1` |
| 「scope=1 ⇔ 纯 ELDER」不变式 | `LoginUser.java:70-73` 注释 + `AccountAggregateService.java:48-49` **按角色取 `max()`**（默认 ELDER 的 scope）+ `AccountAggregateServiceTest.java:58/64-67/73-77` **逐条锁定**（ELDER=1；ELDER∪ASSESSOR=2；ELDER∪ORG_ADMIN=2；ELDER∪SUPERVISOR=4；ELDER∪FAMILY=5） |
| 角色→scope 权威取值 | `yl-module-account/…/domain/RoleCode.java:13-17`：`ELDER=1 / ASSESSOR=2 / FAMILY=5 / ORG_ADMIN=2 / SUPERVISOR=4` |
| 结论 | **机构管理员（2）与评估员（2）均不受状态闸约束 ⇒ 仍可读 `status=0`（生成中）与 `status=2`（已作废）报告**，复核/作废链路不受影响；状态闸**只**收口纯 ELDER 单角色。**C-4 满足，且无需改动**（改为全局拦截才是缺陷）。 |
| ⚠ 前置依赖 | 该结论**依赖**「ASSESSOR 不在 scope=1」。同日已发现 PRD §2.2.1 并入的补遗件旧表把 **ASSESSOR 记为 1**，与实现冲突 ⇒ 已按权威清单订正为 `1/2/5/2/4` 并留痕（`docs/change/CR-M2-009-prd-section-2.2-merge.md` §四 **D-8**）。**若照旧表落地实现，C-4 会被破坏（评估员读不到作废报告 = 欠权）。** |

### D. 实机项 —— ⏸ 不可执行（诚实登记）

| 项 | 为什么现在跑不了 |
|---|---|
| D-1 纯老人账号 | 端侧 `Role`/登录页/Kotlin 常量**均无 ELDER** ⇒ 无法以老人身份登录端侧；需先完成 A 组改造 |
| D-2 混合角色账号 | 同上；且需真实后端账号（多角色聚合） |
| D-3 未知角色落登录页 | 需 A/B 改造后构造后端返回未识别角色 |

> 上述三项**不得以「源码看着对」代替实测**（清单 §三.2 已明令）。

---

## 三、本次核查的负面新发现（清单未覆盖）

### 3.1 ★★ 兜底三元不止 `role.ts:41-43`，**共 5 处**（改一半仍会落错页）

| 位置 | 代码 | 若只改 `homeRouteOf` 的后果 |
|---|---|---|
| `role.ts:42` | `homeRouteOf`：`FAMILY ? familyHome : assessorHome` | 清单已覆盖 |
| `role.ts:54` | `displayNameOf`：`FAMILY ? '家属' : '评估员'` | 老人显示为「**评估员**」 |
| `role.ts:61` | `avatarOf`：`FAMILY ? '家' : '评'` | 老人头像为「**评**」 |
| `role.ts:130` | `tabsOf`：`FAMILY ? FAMILY_TABS : ASSESSOR_TABS` | 老人拿到**评估员 TabBar**（越权入口） |
| `role.ts:134` | （角色判定分支） | 同上类兜底 |

**要求**：`rR1a6L` B-1 判据须由「改 `homeRouteOf()`」**扩充为「`role.ts` 内所有 `role === 'FAMILY' ? … : …` 三元的穷尽替换」**，并以「全文件 grep 该模式 = 0」作为可执行判据；否则改造后仍会出现「路由对了、TabBar 和称谓还是评估员」的半成品（属清单判据**覆盖面不足**）。

### 3.2 ★ 与 `rqZ0a3` 补丁的**文件冲突**（必须串行或同批复核）

`rqZ0a3`（B1–B5 止血）与 `rR1a6L`（ELDER 改造）**同时触及**：

| 文件 | `rqZ0a3` 触及 | `rR1a6L` 触及 |
|---|---|---|
| `weixin-app/src/pages/login/login.vue` | B5 客服热线硬编码 | A-1 新增「老人」登录选项 |
| `android-native/…/MockApiClient.kt` | B2 硬编码 PII | B-2 去掉 `else ROLE_ASSESSOR` |
| `android-native/…/AgreementScreen.kt` / 其它 `profile.vue` 等 | B4/B5 | 可能涉及「内测」与角色展示 |

⇒ **纪律**：两项**不得并行改同一文件**；应在 `rqZ0a3` 落地后再做 `rR1a6L`，或同批改并**重新比对 md5**，否则会出现「一方回退另一方改动」的静默覆盖。

### 3.3 🟡 清单文本与实现的**锚点小误差**（不影响判据，但影响验收效率）

| 清单写法 | 实测正确位置 |
|---|---|
| `android-native/…/util/FamilyUtils.kt` | 实际包路径为 `…/java/com/yl/shouhu/util/FamilyUtils.kt`（清单省略了 `com/yl/shouhu`） |
| `ApiClient.kt:16` 注释 `ADMIN / ASSESSOR / FAMILY` | 实测确为 `// ADMIN / ASSESSOR / FAMILY`（照旧，但**实现里并无 ADMIN 角色码**，注释本身也应订正为 `ORG_ADMIN`） |

---

## 四、处置建议（按优先级）

| # | 建议 | 说明 |
|:--:|---|---|
| 1 | **保持 2008 冻结** | 会签为四端附条件；`rR1a6L` 未通过 ⇒ **开关 B 不得开启**（读路径开关 A 不受影响） |
| 2 | 扩充 B-1 判据为「`role.ts` 5 处三元穷尽」 | 见 §三.1，否则验收会放行半成品 |
| 3 | `rR1a6L` 与 `rqZ0a3` **串行**（或同批 + 重算三端 md5） | 见 §三.2 |
| 4 | 激活 A 组后再安排 D 组实机 | 无 ELDER 则 D-1/D-2 无法构造前置条件 |
| 5 | C-4 **不需改动**，但须保留回归用例 | M3 状态闸与角色 scope 不变式是「不许被『顺手收紧』」的红线 |
| 6 | 清单锚点订正为完整包路径 | 见 §三.3（省验收人找文件的时间） |

---

## 五、本次核查**未做**的事（边界声明）

1. **未修改任何端侧代码**（C 授权仅核查）；`role.ts`/`auth.ts`/Kotlin 等文件保持原样。
2. **未修改任何权限**（未开 2008、未改种子、未改契约）。
3. **未执行 D 组实机**（无设备/账号，且端侧未改造）。
4. **未采信口头结论**：本报告所有判定均附 `file:line` 与代码原文。
5. ⚠ **改造后须重跑本报告全部判据**（含「改造后 `file:line`」与三端 md5），本报告不能作为通过依据。
