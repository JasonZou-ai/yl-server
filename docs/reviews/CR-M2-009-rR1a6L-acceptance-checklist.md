# rR1a6L 四端第一批改造验收核对清单

> 用途：`CR-M2-009` 四端**附条件同意的解除依据**，也是 **2008 写权限（`care:plan:edit:suggest`）唯一的剩余上线前置**。
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

## 五、未通过处置

- **2008 写权限保持冻结**（开关 B 关闭）；
- **读路径（开关 A）不受影响**，继续可用 —— 二者解耦，不得因四端未验收而阻塞读路径上线；
- 在 `docs/change/CR-M2-009-ccb-ratification-2008-prd-writeback.md` 登记逾期记录与重新约定的验收期限。
