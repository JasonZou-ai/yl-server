# rR1a6L 四端改造 · 离线验收测试报告

> 编制：**2026-10-16 12:45** ｜ 编制人：研发侧 ｜ 性质：**离线可验判据的机器证据**，不是验收结论
> 关联：`CR-M2-009-rR1a6L-acceptance-checklist.md`（验收清单，勾选权归测试负责人）
> 套件：`scripts/verify-rR1a6L-offline.sh`（v1.0，可复跑）
> 判定口径：三态 **PASS / FAIL / BLOCKED**，`BLOCKED` **不算通过**；退出码非 0 即失败（fail-closed，绝不半跑）

## 〇、结论

| 项 | 值 |
|---|---|
| PASS | **63** |
| FAIL | **0** |
| BLOCKED | **0** |
| 退出码 | **0** |
| 判定 | **OFFLINE-GREEN** —— 所有可离线验证的判据成立 |

**★ 但本结论不等于验收通过**：D-1 / D-2 / D-3 三项为**真机实测项，无法离线替代**，仍待测试负责人执行；本套件**不得**用于代勾验收清单的 12 个勾选框。

## 一、复现方式（一条命令）

```bash
cd stage3_开发/yl-server
bash scripts/verify-rR1a6L-offline.sh
# 默认端侧根 C:/Users/Administrator/WorkBuddy/2026-09-08-13-00-34/stage3_开发
# 可选：bash scripts/verify-rR1a6L-offline.sh <端侧根> <后端根>
```

耗时约 2 分钟（其中后端 mvn 回归约 90 秒）。**不需要** 8080 服务、**不需要**任何口令/环境变量、**不需要**真机。

## 二、覆盖范围

| 验收项 | 是否可离线 | 本套件覆盖 |
|---|:--:|:--:|
| A-1 TS 侧 ELDER 枚举 | 是 | ✅ T1 |
| A-2 Kotlin 侧 ROLE_ELDER | 是 | ✅ T2 |
| A-3 三端同源一致 | 是 | ✅ T3 |
| B-1 兜底路由（含 S-1 穷尽替换） | 是 | ✅ T4 |
| B-2 Kotlin 兜底收敛 | 是 | ✅ T5 |
| C-1 老人读面收敛 | 部分 | ✅ T7（源码+产物） |
| C-2 不直读 `report_dimension_score` | 是 | ✅ T6 |
| C-3 老人无评估员入口 | 部分 | ✅ T7（tab 分发） |
| C-4 M3 状态闸不得误伤 | 是 | ✅ T10（后端 mvn 回归） |
| **D-1 / D-2 / D-3 真机实测** | **否** | ❌ **不覆盖** |

## 三、执行结果明细

### T1 · A-1 TS 侧 ELDER 枚举（9 PASS）

| 端 | 证据 |
|---|---|
| weixin / ios / douyin | `src/utils/role.ts:34` `export type Role = 'ASSESSOR' \| 'FAMILY' \| 'ELDER' \| ''` |
| weixin / ios / douyin | `src/stores/auth.ts:30` 归一化分支含 `ELDER` |
| weixin `login.vue:144` / ios `:152` / douyin `:123` | 登录页提供「老人」选项 |

### T2 · A-2 Kotlin（2 PASS）

`FamilyUtils.kt:16` `const val ROLE_ELDER = "ELDER"`；全文件 `ROLE_ELDER` 引用 4 处。

### T3 · A-3 三端同源（10 PASS，★ 实算文件 md5，不采信表内自报）

| 文件 | 三端 md5 |
|---|---|
| `src/utils/role.ts` | `734D89A2…` |
| `src/stores/auth.ts` | `793E50C5…` |
| `src/utils/real-api.ts` | `B65AC351…` |
| `src/utils/mock-api.ts` | `B6169724…` |
| `src/pages/elder-home/elder-home.vue` | `6711ED59…` |
| `src/pages/splash/splash.vue` | `945614C9…` |
| `src/components/YlNavBar.vue` | `435F7A49…` |
| `src/components/YlTabBar.vue` | `0D0CB39F…` |
| `src/pages/family-reports/family-reports.vue` | `6CE3E2FC…` |
| `src/pages/archive/archive.vue` | `BF94CF10…` |

> 平台特有文件（`pages.json` / `login.vue` / `profile.vue` / `home.vue`）三端 md5 不同**属预期**，不在本项范围内。

### T4 · B-1 / S-1 兜底路由（6 PASS）

三端 `role.ts` 代码层 `role === 'FAMILY' ?` 三元命中 **= 0**，且均含 `ROUTE.login` 兜底。

### T5 · B-2 Kotlin 兜底（2 PASS）

`MockApiClient.kt` 代码层 `else ROLE_ASSESSOR` = 0、无任何 `ROLE_ASSESSOR` 默认兜底。

### T6 · C-2 不直读子表（3 PASS）

三端 `src/` 代码层 `report_dimension_score` 命中 **= 0**。

### T7 · C-1 / C-3 老人读面（6 PASS）

三端均存在 `src/pages/elder-home/elder-home.vue`；`role.ts` 含老人专属 tab 分发。

### T8 · 产物扫描（15 PASS）

| 端 | 扫描目录 | 内测/facePassed | 硬编码手机号 | elder-home | IS_DEMO_BUILD |
|---|---|---|---|---|---|
| weixin | `h5` `mp-weixin` | 0 | 0 | 6 文件 | 未置真 |
| ios | `.nvue` `app` `h5` | 0 | 0（vendor 子串 2 处已排除） | 5 文件 | 未置真 |
| douyin | `h5` `mp-toutiao` | 0 | 0 | 6 文件 | 未置真 |

### T9 · 行为断言（3 PASS）

三端各跑 `apply-test.js`（node + 工程自带 esbuild，**未新增依赖**）：`weixin / ios / douyin` 均 **exit 0**，各约 32 项断言（共 96）。

### T10 · C-4 后端回归（1 PASS）

| 测试类 | 结果 |
|---|---|
| `DataScopePermissionHandlerTest` | 27 / 0 失败 |
| `AccountAggregateServiceTest` | 6 / 0 失败 |
| `AssessmentReportServiceTest` | 13 / 0 失败 |

⇒ 机构管理员 / 评估员**未被状态闸误伤**，S-4 不变式保持。

## 四、★ 两处假红及其修正（防假绿记录）

套件首轮跑出 **6 FAIL**，全部经查证为**断言过宽造成的假红**，已修正；修正后为 0 FAIL。**过程留痕，避免后续误判为真缺陷。**

| # | 首轮现象 | 根因 | 修正 |
|---|---|---|---|
| 1 | 三端产物含「内测」/facePassed（22 / 9 / 13 文件） | `dist/build/` 下混有**历史快照目录**（`h5-prev-rR1a6L`、`h5_d4b_old`、`h5-pre-b1a-*`、`mp-*-prev-rR1a6L`、`*_DEMO_*`、`app-FAILED-*`、`app_dirty_*`、`app_old_*`），旧产物被计入 | 改为**当前产物目录白名单**扫描（`h5` / `mp-weixin` / `mp-toutiao` / `app` / `.nvue`），并防御性剔除含 `prev\|old\|dirty\|DEMO\|FAILED\|pre-` 的目录名 |
| 2 | iOS 产物含疑似手机号 3 处 | 正则 `1[3-9][0-9]{9}` 命中 vendor 常量 `9007199254740991`（`Number.MAX_SAFE_INTEGER`）的**内部子串** `19925474099`；原用 `grep -o` 只输出匹配片段，**丢失上下文无法排除** | 改用 `grep -rn`（输出整行）再 `grep -vF 9007199254740991` 过滤。取证：不含该常量的命中行 = **0** |

> 与既有 Lead 复核（2026-10-15）结论一致：其亦记录「iOS 3 处 = vendor `9007199254740991` 子串」—— 属**已知预期**，非缺陷。

## 五、不覆盖范围（须交测试负责人真机执行）

| # | 项 | 为何不能离线 |
|---|---|---|
| D-1 | 纯老人账号实机 | 需真实账号 + 绑定链路 |
| D-2 | 混合角色账号实机 | 需角色切换 + 真机缓存行为 |
| D-3 | 未知角色账号实机 | 需后端返回未识别角色（源码级负例已由 T9 断言覆盖） |

另有清单 §七 残留②（小程序页面静态注册不拒载，仅靠页面内守卫、存在一帧窗口）属**实机方可验证的风险**，一并交测试负责人关注。

## 六、红线声明

1. 本报告**不得**用于代勾 `CR-M2-009-rR1a6L-acceptance-checklist.md` 的 12 个勾选框 —— 勾选权唯一归**测试负责人**（PM 2026-10-16 签发 §一.1）。
2. 验收结论出具前，**2008 写权限（开关 B）继续冻结**、完整生产包继续阻断（清单 §五）。
3. 本套件只证明「可离线验证的部分成立」，D 组未执行前整体状态仍为**未验收**。
