# 变更单 CR-M2-001 · 权限矩阵缺口补齐（B2 契约回填发现）

| 项 | 内容 |
|---|---|
| 变更单编号 | **CR-M2-001** |
| 提出日期 | 2026-09-21 |
| 提出人 | 研发侧（B2 权限契约回填专项） |
| 变更对象 | 《「银龄守护」产品需求文档 PRD》`YL-PRD-v1.1` §2.2 权限矩阵（基线标签 `v1.1-frozen`） |
| 变更依据 | `docs/design/B2-permission-api-matrix.md`（22 个契约操作的逐接口回填结果） |
| 关联交付 | `docker/mysql/init/03_seed_rbac.sql`、`scripts/verify-schema.sh`、`openapi/yl-api.yaml` |
| 变更类别 | **一般变更**（权限点增补，**不触及四端功能边界**、不新增业务功能） |
| 紧急度 | 中（C/D 模块实现前必须落地，否则实现时只能"猜权限码"） |
| 审批权限 | 按《需求基线冻结说明》§4.2 —— **CCB 多数同意 + 产品经理签发**（一般变更） |
| 状态 | **✅ 已通过：CCB 会签 + PM 签发（2026-09-21）——种子与红线断言已落地** |

---

## 一、变更背景

B2（账号·角色·权限）在把 `openapi/yl-api.yaml` 的 **22 个操作逐一回填 `x-required-permission`** 时，
暴露了一处**结构性缺口**：

- PRD §2.2 权限矩阵只有 **9 行**，展开为 **16 个权限点**（含受限态 `:apply`/`:suggest`、只读态 `:read`）；
- 而契约/实现面覆盖了 **建档、敏感字段明文查看、评估任务列表、国标规则只读** 等场景，**矩阵内无对应行**。

按既定口径「**不得自造角色或简并权限点**」，这 7 个操作只能标为 `pending-cr` 并停在此处 —— 结果是：

| 指标 | 现状 |
|---|---|
| 契约操作带明确权限码 | **15 / 22 = 68%** |
| `pending-cr`（无权限码可依） | **7** |
| 后续影响 | C（核心链路）/ D（档案/报表）模块实现时，这些接口**没有权限码可标注**，只能临时自造 → 权限口径漂移 |

> 反向也发现一处：16 个权限点中有 **6 类「权限点无归属接口」**（作废、亲情绑定/解绑、照护方案、机构管理），
> 即**授权已存在但契约无对应路径**。该项不阻塞本 CR，处置见 §2.4。

---

## 二、变更内容

### 2.1 PRD §2.2 权限矩阵：新增 5 行

| 功能模块（新增行） | 老人 | 评估员 | 家属 | 机构管理员 | 监管 | 备注 |
|---|:--:|:--:|:--:|:--:|:--:|---|
| **老人建档** | ❌ | ✅ | 🔶 | ✅ | ❌ | 🔶家属仅可**发起代办建档申请** |
| **查看老人档案** | 🔶 | ✅ | 🔶 | ✅ | 👁 | 老人仅本人；家属仅**绑定对象**；可见范围由数据域闸控制 |
| **查看敏感字段明文** | ❌ | ❌ | ❌ | ✅ | ❌ | **需二次验证 + 留痕**（本次新增敏感点） |
| **评估任务列表查看** | ❌ | ✅ | ❌ | ✅ | 👁 | 任务列表/多维筛选（功能点 1） |
| **国标规则只读（条款回溯）** | ✅ | ✅ | ✅ | ✅ | ✅ | 登录即可读；用于报告条款号回溯展示 |

> 新增后 §2.2 由 **9 行 → 14 行**。图例沿用：✅ 允许 ｜ ❌ 禁止 ｜ 🔶 受限/需审批 ｜ 👁 只读。

### 2.2 新增 6 个权限点（`sys_permission` 16 → **22**）

| id | perm_code | perm_name | module | 敏感 |
|---|---|---|---|:--:|
| 2017 | `elder:archive:create` | 老人建档 | elder | 0 |
| 2018 | `elder:archive:create:apply` | 发起代办建档申请（受限） | elder | 0 |
| 2019 | `elder:archive:read` | 查看老人档案 | elder | 0 |
| 2020 | `evaluation:task:read` | 评估任务列表查看 | evaluation | 0 |
| 2021 | **`data:reveal`** | **查看敏感字段明文** | security | **1** |
| 2022 | `rule:view` | 国标规则只读 | rule | 0 |

> 命名沿用既定规范 `<module>:<resource>:<action>[:受限后缀]`；`data:reveal` 进敏感集合后，
> **敏感点由 3 → 4**（`data:export` / `evaluation:order:void` / `account:family:unbind` / **`data:reveal`**），
> 由 `PermissionAspect` + `SensitivePermissions` 自动联动第四闸，接口侧无需重复编码。

### 2.3 授权改动（`sys_role_permission` 23 → **40**，新增 17 条）

| 权限点 | 老人 | 评估员 | 家属 | 机构管理员 | 监管 |
|---|:--:|:--:|:--:|:--:|:--:|
| `elder:archive:create` | ❌ | ✅ | — | ✅ | ❌ |
| `elder:archive:create:apply` | ❌ | — | ✅ | — | ❌ |
| `elder:archive:read` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `evaluation:task:read` | ❌ | ✅ | ❌ | ✅ | ✅（👁） |
| `data:reveal` | ❌ | ❌ | ❌ | ✅ | ❌ |
| `rule:view` | ✅ | ✅ | ✅ | ✅ | ✅ |

> `elder:archive:read` 五角色全授予，**可见范围不靠权限点区分、由第三闸 `data_scope` 控制**
> （老人=1 本人 / 家属=1 本人 / 评估员·机构管理员=2 本机构 / 监管=4 只读全局）——
> 避免为「本人/绑定对象」再造 2 个权限码，与既定「不为数据范围分裂权限点」口径一致。

### 2.4 契约侧改动（`openapi/yl-api.yaml`）

**(a) 本 CR 生效后即回填（7 处 `pending-cr` → 明确权限码）**

| 契约操作 | 变更后权限码 |
|---|---|
| `POST /elders` | `elder:archive:create` |
| `GET /elders`、`GET /elders/{elderId}` | `elder:archive:read` |
| `POST /security/reveal` | `data:reveal`（**+ 第四闸**） |
| `GET /eval/tasks` | `evaluation:task:read` |
| `GET /rule/versions`、`GET /rule/mapping` | `rule:view` |

**→ 契约权限点明确率 68% → 100%。**

**(b) 反向缺口（不阻塞本 CR，随 C/D 模块实现补登）**

| 权限点 | 应补接口 | 备注 |
|---|---|---|
| `evaluation:order:void` | `POST /eval/orders/{orderId}/void` | 敏感，运行时提权 |
| `account:family:unbind` | `POST /family/unbind` | 敏感，运行时提权 |
| `account:family:bind` / `:reject` | `POST /family/bind`、`POST /family/bind/{id}/reject` | — |
| `care:plan:edit` / `:suggest` | `PUT /care/plans/{planId}` | — |
| `account:org:manage` / `:read` | `GET /account/orgs`、`GET /account/orgs/{orgId}` | — |
| `evaluation:order:review:read` | 已由 `/eval/orders/{orderId}/review` 只读态承载 | 无需新增路径 |

> 补登后契约由 **21 路径 / 22 操作 → 28 路径 / 29 操作**。属契约完整性工作，随模块实现推进，**无需本 CR 前置**。

### 2.5 审计侧配套要求（PM 补充 · 2026-09-21）

> **PM 补充意见（原文）**：
> 「`elder:archive:read` 全角色授权已通过，但需在审计侧增加异常访问告警规则——**非护理角色在单小时内调档案接口超过 N 次（建议 N=50）触发告警**。请研发落地时同步配置。」

**解读与落点**：本 CR 把 `elder:archive:read` 授予全部 5 个角色（§2.3），**可见范围改由第三闸 `data_scope` 控制**。
权限点层面的「✅」不再具备限速含义，因此**必须补一层「行为异常检测」**兜住批量拉档风险：

| 项 | 口径 |
|---|---|
| 监控对象 | **非护理角色**（PRD §2.1 中 ELDER / FAMILY / SUPERVISOR 视为非护理角色；ASSESSOR / ORG_ADMIN 为护理相关角色，**是否纳入需 PM 在会签时明确**） |
| 触发条件 | 同一账号对档案类接口（`GET /elders`、`GET /elders/{elderId}`）**单小时调用次数 > N**，**建议 N = 50** |
| 统计窗口 | 滑动 1 小时（非自然小时），基于 `audit_log` 或 Redis 计数器 |
| 处置动作 | 写入告警事件 → 通知监管角色（`SUPERVISOR`）→ 可选降级为单次二次验证 |
| 埋点归属 | 复用 ER-11 埋点字典 S1 层事件，**不新增事件名**；告警属服务端行为，**不采集端侧新属性** |
| 落地位置 | `PermissionAspect` 扩展点或独立 `ArchiveAccessRateGuard`（**建议独立**，避免鉴权链路过重） |
| 是否阻塞本 CR | **不阻塞**。属审计配套，随本 CR 落地一并配置（见 §六 落地清单 #7） |

> ✅ **2 个参数已随 CCB 会签定案（2026-09-21）**：① N = **50**；② **ASSESSOR / ORG_ADMIN 不纳入**监控对象（仅监控非护理角色
> ELDER / FAMILY / SUPERVISOR）。三项落地物（2026-09-22）：`SlidingWindowCounter` 端口 + `RedisSlidingWindowCounter`（ZSET + Lua 原子滑动窗口）、
> `ArchiveAccessRateGuard`（判定 + 去重 + 写审计告警）、`ArchiveAccessAlertNotifier`（通知监管角色，默认日志实现）。
> 全部参数外置可配（`yl.security.archive-access-guard.*`），后续调整无需改码。

---

## 三、影响面评估（CCB 流程 ②）

| 维度 | 评估 |
|---|---|
| **需求影响** | **不新增业务功能**，仅把「已在契约与实现中存在的场景」补齐为矩阵行与权限码。功能点数量不变（仍 20 项）。 |
| **工期影响** | 研发侧改动量为**种子 6 条 INSERT + 17 条授权 + 断言 8 条**，约 0.5 人日；**不占用 C/D 模块工期**。 |
| **合规影响** | **正向**：① `data:reveal` 使「查看明文敏感字段」从**无权限管控**变为**须二次验证 + 留痕**（补上 M1《脱敏规则》「查看需验证」的落点）；② 建档与查档纳入权限矩阵，满足 PIPL 最小必要与访问控制要求。 |
| **数据模型影响** | `02_schema.sql` **不新增/不修改任何表**（6 个权限点与 17 条授权均为 `03_seed_rbac.sql` 的数据行）。 |
| **测试回归范围** | 本 CR 引入的断言增量为 **+8 条 ❌ 红线**（当时 43 → 51）；**断言总数口径以 `docs/quality/verify-schema-assertion-reconciliation.md` 为唯一来源**，其后 ER-11/ER-14 上库 + R3 闭合逐步增至当前 **63 项**。本 CR 自身**无回归**（纯增量：6 权限点 + 17 授权）。 |
| **对外承诺** | 无对外承诺依赖；四端设计稿不涉及权限码，**原型无需改动**。 |
| **权限越界风险** | 8 条新 ❌ 红线已固化为 CI 断言（越界授权即阻断构建），非纸面约定。 |

**分级判定结论**：不触及四端功能边界、不新增功能 → 按《需求基线冻结说明》§4.2 判定为 **一般变更** →
**CCB 多数同意 + 产品经理签发**。

---

## 四、证据清单（可复核指纹）

| 证据 | 值 |
|---|---|
| 回填依据 | `docs/design/B2-permission-api-matrix.md`（22 操作 100% 带 `x-required-permission`） |
| 当前契约 | `openapi/yl-api.yaml`：21 路径 / 22 操作；9 有权限码 / 6 `none` / **7 `pending-cr`** |
| 当前种子 | `03_seed_rbac.sql`：5 角色 / 16 权限点 / 23 授权（敏感 3）——**CR 起草时基线**；落地后为 **22 权限点 / 40 授权（敏感 4）** |
| 当前门禁 | `scripts/verify-schema.sh` **43 项全绿 / 0 失败**（本地 MySQL 8.0.37）——**CR 起草时基线**；当前为 **63 项全绿 / 0 失败**（口径见 `docs/quality/verify-schema-assertion-reconciliation.md`） |
| 代码锚点 | `PermissionCode`（16 个常量，禁止私自增删）、`SensitivePermissions`、`PermissionAspect` |
| 待应用补丁 | `docs/change/CR-M2-001-seed-patch-proposal.sql`（**本 CR 批准前不上库**） |

---

## 五、会签事项与会签栏

| # | 会签事项 | 说明 |
|---|---|---|
| 1 | **确认新增 5 行矩阵 / 6 个权限点** | 重点确认 `data:reveal` 仅授予机构管理员且**强制二次验证** |
| 2 | **确认 `elder:archive:read` 五角色全授予、范围由数据域控制** | 若产品要求「老人不得查看档案列表」，需改为 ❌ 并另立权限点 |
| 3 | **确认受限态命名** | 家属代建档用 `elder:archive:create:apply`（沿用既有 `:apply` 约定） |
| 4 | **确认反向缺口处置** | 6 类「权限点无归属接口」随 C/D 模块补登，不阻塞本 CR |
| 5 | **确认审计侧异常访问告警口径（PM 补充 §2.5）** | ① N 是否取 **50**；② **ASSESSOR / ORG_ADMIN 是否纳入**监控对象 |

### 会签栏

| 角色 | 姓名 | 意见（同意 / 不同意 / 附条件同意） | 日期 | 签署 |
|---|---|---|---|---|
| 产品经理（签发） | — | ✅ 同意（含 §2.5 补充意见，N=50、监控对象仅 ELDER/FAMILY/SUPERVISOR，参数外置可配） | 2026-09-21 | 已签署 |
| 研发负责人（后端） | — | ✅ 同意 | 2026-09-21 | 已签署 |
| 研发负责人（四端） | — | ✅ 同意 | 2026-09-21 | 已签署 |
| 测试负责人 | — | ✅ 同意（红线 8 条已转为门禁真实断言） | 2026-09-21 | 已签署 |
| 合规专员 | — | ✅ 同意（`data:reveal` 强制二次验证 + 留痕，符合最小必要） | 2026-09-21 | 已签署 |

> 5 个角色已全部签署，CCB 多数同意 + PM 签发，本 CR **已生效**。
> 研发侧按「会签事项 #5」落地：N=50、仅监控 ELDER / FAMILY / SUPERVISOR，参数外置可配。

---

## 六、生效条件与回滚

**生效条件**：会签栏 5 个角色全部签署（CCB 多数同意 + PM 签发）。

**批准后落地清单**

| # | 动作 | 产出 | 状态 |
|---|---|---|---|
| 1 | 应用种子补丁 | `docker/mysql/init/03_seed_rbac.sql` 追加 6 权限点 + 17 授权（id 2017–2022 / **3024–3040**） | ✅ 2026-09-21 |
| 2 | 同步代码常量 | `PermissionCode` 增 6 常量；`SensitivePermissions` 增 `data:reveal` | ✅ 2026-09-22 |
| 3 | 增补断言 | `verify-schema.sh`：权限点 = 22、授权 = 40、敏感点 = 4、新增 ❌ 红线 8 条（**断言总数 43 → 63，口径见 `docs/quality/verify-schema-assertion-reconciliation.md`**） | ✅ 2026-09-21 / 09-22 |
| 4 | 回填契约 | `openapi/yl-api.yaml` 7 处 `pending-cr` → 明确权限码；`x-required-permission` 明确率 **100%（22/22）** | ✅ 2026-09-22 |
| 5 | 回写 PRD | §2.2 增 5 行、修订记录加「v1.1（同版回写 CR-M2-001）」行（**按 CR-M1-002 先例同版回写、不升版**，须 CCB 追认） | ⏳ 待办（须 CCB 追认） |
| 6 | 文档同步 | `B2-permission-api-matrix.md` 升 v2（缺口清零）；`B2-account-rbac-design.md` §2/§5/§7/§9 同步；**新增 `B2-permission-point-catalog.md`（22 点 × 5 角色全清单）** | ✅ 2026-09-22 |
| 7 | **审计配套（PM 补充 §2.5）** | 新增 `ArchiveAccessRateGuard`（滑动 1h 计数，N=50 外置可配）+ 告警写入 `audit_log` + 监管角色通知；`verify-schema.sh` **不新增断言**（属运行时行为，非结构约束） | ✅ 2026-09-22 |

**回滚方案**：改动为**纯增量**（新增行/新增授权），回滚只需删除 6 条 `sys_permission`（id 2017–2022）、
17 条 `sys_role_permission`、契约中 7 处 `x-required-permission` 改回 `pending-cr`；
**无数据迁移、无表结构变更**，回滚成本极低。

---

## 七、闭环记录

| 日期 | 事项 | 结果 | 确认人 |
|---|---|---|---|
| 2026-09-21 | B2 契约回填发现 7 处缺口 | ✅ 完成（`B2-permission-api-matrix.md` v1） | 研发侧 |
| 2026-09-21 | 起草 CR-M2-001（含种子补丁） | ✅ 完成，待会签 | 研发侧 |
| 2026-09-21 | **PM 补充 §2.5 审计侧异常访问告警要求** | ✅ 已纳入会签事项 #5 / 落地清单 #7 | 产品经理 |
| 2026-09-21 | **提交 CCB 会签** | ⏳ 已提交，**待 5 个角色签署**（PM 签发 / 后端 / 四端 / 测试 / 合规） | CCB |
| 2026-09-21 | **CCB 会签 + PM 签发通过** | ✅ 5 个角色全部同意；§2.5 告警参数定案（N=50，监控 ELDER/FAMILY/SUPERVISOR） | CCB / 产品经理 |
| 2026-09-21 | **种子落地（独立提交）** | ✅ `03_seed_rbac.sql` 增 6 权限点 + 17 授权；`verify-schema.sh` 权限点 22 / 授权 40，8 条红线转为真实断言 | 研发侧 |
| 2026-09-22 | **落地 #2 同步代码常量** | ✅ `PermissionCode` 22 常量（+6）、`SensitivePermissions` 4 敏感点（+`data:reveal`） | 研发侧 |
| 2026-09-22 | **落地 #4 回填契约** | ✅ `openapi/yl-api.yaml` 7 处 `pending-cr` → 明确权限码；明确率 **100%（22/22）** | 研发侧 |
| 2026-09-22 | **落地 #7 审计配套** | ✅ `ArchiveAccessRateGuard` + 滑动窗口计数（ZSET+Lua）+ 告警写库 + 监管通知；单测 7 例通过 | 研发侧 |
| 2026-09-22 | **落地 #6 文档同步** | ✅ 矩阵升 v2（缺口清零）、设计 §2/§5/§7/§9 同步、新增权限点清单 | 研发侧 |
| — | 落地 #5 回写 PRD | ⏳ 待办（须 CCB 追认） | 产品 |
| — | 反向缺口 6 类补登契约 | ⏳ 待办（随 C/D 模块，不阻塞本 CR） | 研发 |
