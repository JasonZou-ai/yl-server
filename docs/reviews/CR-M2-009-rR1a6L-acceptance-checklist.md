# rR1a6L 四端第一批改造验收核对清单

> 用途：`CR-M2-009` 四端**附条件同意的解除依据**，也是 **2008 写权限（`care:plan:edit:suggest`）唯一的剩余上线前置**。
> **验收人（PM 2026-10-16 正式确权）：测试负责人** —— 唯一有权勾选本清单、出具验收结论者；**研发实施方禁止代填、代勾**（PM 签发 §一.1）。
> **验收硬限：2026-10-20 24:00**（原 10-17；PM 签发 §二.2 场景 2 **显性重约**，禁止隐性顺延）。
> ⚠〔2026-10-17 判定窗口收口，2026-10-18 登记〕**原限 2026-10-17 已逾期且验收结论未出具** —— §二 12 个勾选框仍全部**未勾选（空框）**，**验收日期／验收人栏位保持留空**（勾选权归测试负责人，实施方禁止代填代勾）。逾期三要素登记见 **§四.4**；同批同步至 `docs/change/CR-M2-009-ccb-ratification-2008-prd-writeback.md` §四 备注 2 与 `docs/reviews/CR-M2-009-release-memo.md` §一 条件 3。
> **证据包**：`docs/reviews/rR1a6L-acceptance-evidence-package.md`（材料清单 + 复现步骤 + 已知残留）。
> 关联：`docs/reviews/CR-M2-009-release-memo.md`（上线/运维管控）、`docs/change/CR-M2-009-ccb-ratification-2008-prd-writeback.md`（会签主文档）
> 现状核查：**2026-10-14 实测**（下文所有 `file:line` 均为**改造前**真实锚点，用于验收时逐项对照）
> 端侧根：`2026-09-08-13-00-34/stage3_开发/{weixin-app, ios-app, douyin-app, android-native}`

## 一、改造前现状（三项缺口均已实测命中）

### A. 角色枚举缺 ELDER

| 端 | 位置 | 实证 |
|---|---|---|
| TS（三端同源） | `weixin-app/src/utils/role.ts:13` | `export type Role = 'ASSESSOR' \| 'FAMILY' \| ''` —— **无 ELDER** |
| TS | `weixin-app/src/stores/auth.ts:12` | `role: (…) as 'ASSESSOR' \| 'FAMILY' \| ''` |
| TS | `weixin-app/src/stores/auth.ts:76` | `session.value.role = (r.role === 'FAMILY' ? 'FAMILY' : 'ASSESSOR')` —— **未知角色一律归 ASSESSOR** |
| TS | `weixin-app/src/pages/login/login.vue:16` | `ref<'assessor' \| 'family' \| ''>('')` —— 登录页无「老人」选项 |
| Kotlin | `android-native/…/util/FamilyUtils.kt:8-9` | 仅 `ROLE_FAMILY` / `ROLE_ASSESSOR` 两个常量，**无 `ROLE_ELDER`** |
| Kotlin | `android-native/…/data/api/ApiClient.kt:16` | 注释 `role: ADMIN / ASSESSOR / FAMILY` |

### B. 兜底路由落入评估员工作台（★ 本次必须修掉的缺陷）

| 端 | 位置 | 实证 |
|---|---|---|
| TS | `weixin-app/src/utils/role.ts:41-43` | `return role === 'FAMILY' ? ROUTE.familyHome : ROUTE.assessorHome` —— **非 FAMILY 全落评估员工作台**（含未知/空角色） |
| TS | `weixin-app/src/utils/role.ts:15-34` | `ROUTE` 常量无老人首页、无 elder 专属页 |
| 调用点 | `pages/splash/splash.vue:12`、`components/YlNavBar.vue:15` | 冷启动与 TabBar「首页」均走 `homeRouteOf(role)`（同源，改一处即生效） |
| Kotlin | `android-native/…/data/api/MockApiClient.kt:28` | `val finalRole = if (role == ROLE_FAMILY) ROLE_FAMILY else ROLE_ASSESSOR` |
| Kotlin | `android-native/…/data/api/MockApiClient.kt:40` | 默认返回 `ROLE_ASSESSOR` |

> 该文件头注释（role.ts:1-11）已记录同源缺陷的**前一次**修复（家属被送进评估员工作台）；本次为**同一类缺陷的第二次发生**，说明兜底写法必须改为白名单 + 显式拒绝，而不是继续加三元分支。

### C. 老人读面未收敛

| 项 | 位置 | 实证 |
|---|---|---|
| 无老人首页路由 | `role.ts:15-34` | 无 `elderHome`，老人登录后无专属落地页 |
| 报告详情数据来源 | `pages/report/report.vue:102/171/224` | 维度得分取自本地 `rec.dims`，**未见直读子表的接口调用**（改真实接口时需守住：不得新增 `report_dimension_score` 直读） |

## 二、验收项与判据（逐项勾选，全部通过方可解除附条件）

| # | 验收项 | 验收判据（须给出改造后 `file:line` + 构建/实测结果） | 状态 |
|:--:|---|---|:--:|
| A-1 | TS 侧新增 ELDER 角色枚举 | `utils/role.ts` 的 `Role` 类型含 `'ELDER'`；`stores/auth.ts` 存储类型与归一化分支同步；`pages/login/login.vue` 提供老人选项 | ☐ |
| A-2 | Kotlin 侧新增 `ROLE_ELDER` | `util/FamilyUtils.kt` 新增常量；`ApiClient.kt` 注释与解析同步 | ☐ |
| A-3 | 三端同源一致 | ★ **weixin-app 改一份 → md5 同步 ios-app / douyin-app**；验收时比对三端改动文件 md5 相同 | ☐ |
| B-1 | TS 兜底路由：未知/未识别角色 → **登录页** | `homeRouteOf()` 改为白名单分发（ELDER/FAMILY/ASSESSOR 各自落地页），**默认分支 = `ROUTE.login`**；禁止保留 `else → assessorHome` | ☐ |
| B-2 | Kotlin 兜底同样收敛 | `MockApiClient.kt:28/:40` 去掉 `else ROLE_ASSESSOR` 默认 | ☐ |
| C-1 | 老人读面仅「报告详情 + PDF」 | 老人域可见页面白名单：仅详情与 PDF 导出；无档案列表、无评估工作台入口 | ☐ |
| C-2 | 维度得分走 **D1-c 聚合接口** | 端侧**不得**直读 `report_dimension_score` 子表（后端 `eval_answer` 维持判死、子表不开放）；改为消费聚合视图 | ☐ |
| C-3 | 老人域无评估员入口（双向验证） | TabBar 与路由表双向核对：老人角色下不出现评估员 tab / 路由不可达 | ☐ |
| C-4 | **M3 状态闸回归（不得误伤）** | 机构管理员**仍可读取** `status=2` 作废报告；状态闸只拦截纯 ELDER 本人域，**不得改为全局拦截**作废报告 | ☐ |
| D-1 | 端侧实机：**纯老人账号** | 跑通绑定 / 报告阅读链路；不得出现评估员首页、待评估列表、全量档案 | ☐ |
| D-2 | 端侧实机：**混合角色账号** | 角色切换后读面随角色收敛，无跨角色残留缓存 | ☐ |
| D-3 | 端侧实机：**未知角色账号** | 后端返回未识别/空角色 → **落到登录页**，不得落到任何业务页（含评估员工作台） | ☐ |

## 三、防假绿口径（验收人必读）

1. **给出 `file:line`**：每项须附改造后源码位置，不接受「已支持 ELDER」的口头结论。
2. **负例必须实测**：
   - 以 ELDER 登录 → 不得出现评估员首页 / 待评估列表 / 老人档案全量列表；
   - 构造**未知角色**（如后端返回 `ORG_ADMIN` 或空值）→ **必须落到登录页**，不得落到任何业务页；
   - 老人读面 → 请求维度得分时不得出现子表接口调用（抓包/日志核对）。
3. **三端 md5 一致性**：A-3 须实际比对三端改动文件 md5，不能只比对文件内容「看起来一样」。
4. **构建证据**：三端各自构建通过（uni-app h5 + mp-weixin/mp-toutiao、Android assembleDebug）并附产物时间。

## 四、验收后动作

1. **解除四端附条件** → `rmJHoA` 前置条件集齐 → **2008 写权限可随版本开启**（开关 B）。
2. 回填本清单「状态」列与验收日期、验收人、三端构建产物时间，并同步 `docs/reviews/CR-M2-009-release-memo.md` §一 条件 3。
3. 若验收在 **10-17 之后**完成：按 10-17 判定口径登记逾期记录后再开启，不得先开后补。

### 四.4 逾期登记（2026-10-17 判定窗口收口｜登记日 2026-10-18｜分支 `docs/rR1a6L-overdue-1017`）

> 性质：**登记**，不是验收结论。本节不勾选、不代填 §二 任何一项，不出具任何验收结论。

| 要素 | 登记内容 |
|---|---|
| **① 原限已逾期** | 原限 **2026-10-17**（源自 CR-M2-009 四端「附条件同意」+ `rpAkOA` 收口 §四）。至该日 24:00 结束，**验收结论未出具**：§二 A-1/A-2/A-3/B-1/B-2/C-1/C-2/C-3/C-4/D-1/D-2/D-3 共 **12 个勾选框仍全部未勾选（空框）**，验收日期／验收人栏位为空。 |
| **② 做了什么** | · 实施侧交付与自证**已完成**：Lead 独立复核 8 项全绿（三端共享 8 文件 md5 unique=1、`role==='FAMILY'` 代码层 0 命中、Kotlin `ROLE_ELDER` 5 处落地且兜底已删、C-2 不直读维度分表、角色分发断言三端各 32 PASS、补丁链 43/43 应用+回滚 PATCH-CHAIN-OK、三端生产产物零命中、`compileDebugKotlin` BUILD SUCCESSFUL）；后端 `GET /api/v1/evaluation/reports` 已供给且端侧 `getMyReportsAsElder()` 已接通；离线验收测试套件 **63 PASS / 0 FAIL / 0 BLOCKED**。<br>· **未完成的是验收动作本身**：勾选权归**测试负责人**（PM 2026-10-16 确权），实施方不得代行；◆ 复核绿 ≠ 验收通过，不得以自证顶替独立验收（PM 已否决 Q-3）。<br>· 已按「**不得先开后补**」口径**未启用 2008 写权限**（开关 B 保持冻结）、**未放行完整对外生产包**。 |
| **③ 新限期** | **2026-10-20 24:00** —— PM **2026-10-16 正式签发**（**显性重约**，禁止隐性顺延）。逾期后 RBAC 种子 `(3041,1001,2008)` **保留在库**，不执行种子回滚（PM §二.2 场景 2）。 |

> 红线声明：本节登记**不解除** §五 的冻结口径 —— 2008 写权限继续冻结；**读路径（开关 A）不受影响**，继续可用。

## 五、未通过处置

- **2008 写权限保持冻结**（开关 B 关闭）；
- **读路径（开关 A）不受影响**，继续可用 —— 二者解耦，不得因四端未验收而阻塞读路径上线；
- 在 `docs/change/CR-M2-009-ccb-ratification-2008-prd-writeback.md` 登记逾期记录与重新约定的验收期限。

---

## 六、补充判据（2026-10-14 核查新增，**源自实测发现，验收时须一并核**）

> 依据：[`rR1a6L-precheck-verification-2026-10-14.md`](./rR1a6L-precheck-verification-2026-10-14.md)

| # | 补充项 | 可执行判据 |
|:--:|---|---|
| S-1 | **B-1 覆盖面扩充（★ 原判据不足）** | `role.ts` 内 `role === 'FAMILY' ? … : …` 形式的三元**共 5 处**（`:42` `homeRouteOf`、`:54` `displayNameOf`、`:61` `avatarOf`、`:130` `tabsOf`、`:134`）必须**全部替换为白名单分发**；判据：该文件 grep 该模式 = **0 命中**，且未知/空角色 → `ROUTE.login`。**只改 `homeRouteOf` 会让老人显示为「评估员」、拿到评估员 TabBar（半成品）。** |
| S-2 | **与 `rqZ0a3` 的写冲突纪律** | `login.vue`（B5 热线 vs A-1 老人选项）、`MockApiClient.kt`（B2 PII vs B-2 兜底角色）**两卡同时触及** ⇒ **不得并行改同一文件**；须串行或同批，并在同批后**重新比对三端 md5**。 |
| S-3 | **锚点订正** | Kotlin 实际包路径为 `android-native/app/src/main/java/com/yl/shouhu/util/FamilyUtils.kt`（清单省略了 `com/yl/shouhu`）；`ApiClient.kt:16` 注释 `ADMIN` 应订正为 `ORG_ADMIN`（实现无 ADMIN 角色码）。 |
| S-4 | **C-4 不变式不得被破坏** | M3 状态闸依赖「`data_scope=1` ⇔ 纯 ELDER 单角色」（`LoginUser#isSelfScope` + `AccountAggregateService` `max()` + `AccountAggregateServiceTest` 锁定）。**任何把 ASSESSOR 记为 scope=1 的改动都会使评估员读不到 `status=0/2` 报告（欠权）** ⇒ 回归用例必须保留；同步见 PRD §2.2.1 订正注（`data_scope` 权威值 **1/2/5/2/4**）。 |
| S-5 | **激活前置顺序** | 先做 A 组（角色枚举）再做 D 组实机；**无 ELDER 则 D-1/D-2 前置条件无法构造**，不得以源码检查代替实机实测。 |

> ⚠ 本清单**改造前核查结论（2026-10-14）：未通过**（A/B/C 组 8 项未通过、C-4 机制已具备、D 组不可执行）⇒ **2008 继续冻结**；改造完成后须**重跑全部判据**（含改造后 `file:line` + 三端 md5），本清单不得据「源码看起来对」勾选。

---

## 七、改造已交付（2026-10-15）+ Lead 独立复核结果

> 授权：用户 2026-10-15「**同意按此继续**」（闭环判定 `APPROVE`）⇒ 授权推进 `rR1a6L` 端侧改造。
> 交付目录：`2026-09-08-13-00-34/stage3_开发/_改造-rR1a6L/`（`backup/` 147 文件 · `patches/` **43 个**分层补丁 · `README.md` · `md5-table.tsv` · `apply-test.js` / `self-test.ps1`）。
> **复核报告全文：[`rR1a6L-postchange-verification-2026-10-15.md`](./rR1a6L-postchange-verification-2026-10-15.md)**

**Lead 独立复核（8 项，全部亲跑；不采信交付方自报）**：

| # | 项 | 结果 |
|:--:|---|:--:|
| 1 | 三端共享 8 文件 md5 | ✅ 逐个 **unique=1**（`auth.ts` 由「三端互不相同」主动统一为 `793e50c5…`；`build-flag.ts` 未回退） |
| 2 | `role === 'FAMILY' ?` 穷尽替换（S-1） | ✅ 命中 9 处**全为注释** ⇒ 代码层 **0** |
| 3 | Kotlin `ROLE_ELDER` / 兜底删除 | ✅ `else ROLE_ASSESSOR` 仅剩注释；`ROLE_ELDER` 5 处落地 |
| 4 | C-2 不直读 `report_dimension_score` | ✅ 命中 4 处**全为禁止性注释** |
| 5 | 角色分发行为断言 | ✅ **三端各 32 PASS / 0 FAIL / exit 0**（共 96 项；★ 订正交付方自报「36 项」） |
| 6 | **补丁链**（Lead 自写实现） | ✅ 43/43 应用 → 与交付态**差异 0**；43/43 回滚 → 与基线**差异 0** ⇒ **PATCH-CHAIN-OK** |
| 7 | 三端生产产物 | ✅ `内测`/`facePassed`/热线=0；手机号正则 weixin/douyin=0、iOS 3 处 = vendor `9007199254740991` 子串；**`elder-home` 已入产物**；`IS_DEMO_BUILD=!1` |
| 8 | 安卓编译 | ✅ `compileDebugKotlin` **BUILD SUCCESSFUL**（`assembleDebug` 另出 APK 10,869,886B） |
| + | **C-4 回归**（Lead 亲跑 mvn） | ✅ `DataScopePermissionHandlerTest 26/0` · `AccountAggregateServiceTest 6/0` · `AssessmentReportServiceTest 9/0` ⇒ 机构管理员/评估员未被误伤 |

**判定**：A-1 / A-2 / A-3 / B-1（含 S-1）/ B-2 / C-1 / C-2 / C-3 在**源码 + 产物 + 行为断言 + 补丁链 + 安卓编译**五层**通过**；C-4 **未触碰且回归全绿**；D-1/D-2/D-3 **不可执行**（无真机/真实账号；D-3 源码级负例已断言通过）。

**残留（不构成通过）**：① ~~后端未供给「本人报告列表」端点~~ → **✅ 已闭环（2026-10-15 第四批）**：后端已供给 `GET /api/v1/evaluation/reports`（契约 48→49，Lead 独立复核 verify 366/0/0 · 契约 8/8 · 冒烟 127/0/1）；**端侧 `getMyReportsAsElder()` 已接通并过复核**（23 补丁链 `PATCH-CHAIN-OK`、H5 三端 PASS、真实接口亲跑 ELDER/FAMILY 均 `200+code=0`、无凭据 `401+10002`、分页 `0→20`/`101→100`）⇒ 见 `rR1a6L-postchange-verification-2026-10-15.md` 第四批。〔2026-10-15 14:5x 回填：本节原记「执行中」为**文档漂移 N-1**，实际已完成〕；② 小程序页面静态注册不拒载 ⇒ 靠**页面内守卫**（`home.vue` 未加渲染期兜底，仅一帧窗口）；③ PDF 导出仅说明按钮（文件能力未接入）；④ 真机 D-1~D-3 未跑；⑤ 平台特有文件 md5 三端不同**属预期**；⑥ 机构联系方式端点仍未供给（端侧留空占位）。

> ⚠ **§二 的勾选框仍由验收人填写**：本清单**不得**由实施方自勾通过；**验收结论未出具前，2008 写权限继续冻结、完整生产包继续阻断**（§五）。
