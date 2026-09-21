# B2 权限点 ↔ API 对照矩阵（逐接口回填）

> 依据：PRD **v1.1** §2.1 角色定义 / §2.2 权限矩阵、`openapi/yl-api.yaml`、`docs/design/B2-account-rbac-design.md` §3
> 口径：**不得自造角色或简并权限点**——PRD §2.2 未覆盖的接口一律标记 `pending-cr`，不臆造权限码
> 版本：v1（2026-09-21）｜产出人：研发侧｜状态：待评审

## 1 TL;DR

1. `openapi/yl-api.yaml` 的 **22 个操作已 100% 回填** `x-required-permission` 扩展，契约层可机器校验。
2. 其中 **9 个操作有明确权限点**、**6 个为 `none`**（免鉴权或仅需登录）、**7 个为 `pending-cr`**。
3. **7 处 `pending-cr` 是真实缺口**：PRD §2.2 只有 9 行矩阵、派生 16 个权限点，而 API 面覆盖建档 / 敏感明文 / 规则只读 / 任务列表等场景，**无对应权限点**。
4. 反向也缺：实现已存在的 `POST /api/v1/auth/second-verify` 原不在契约中，**本轮已补登**；另有 6 类接口（作废、亲情绑定/解绑、照护方案、机构管理）是 16 个权限点的「归属接口」却**尚未进契约**。
5. 覆盖率结论：按 22 个契约操作计，**权限点明确率 68%（15/22）**；补 CR 后可到 100%。

## 2 逐接口对照

图例：**③** = 数据域闸（`data_scope` 行级过滤）｜**④** = 二次验证闸（`need_second_verify=1`）

| # | 接口 | 方法 | 所需权限点 | 放行角色（PRD §2.2） | ③ | ④ | 实现 |
|---|---|---|---|---|:--:|:--:|:--:|
| 1 | `/auth/login` | POST | `none` | 全部（免鉴权） | — | — | ✅ |
| 2 | `/auth/refresh` | POST | `none` | 全部（免鉴权） | — | — | ✅ |
| 3 | `/auth/second-verify` | POST | `none` | 已登录用户 | — | 入口 | ✅ |
| 4 | `/auth/logout` | POST | `none` | 已登录用户 | — | — | ⏳ |
| 5 | `/elders` | POST | `pending-cr` | 待定（建档无矩阵行） | 是 | — | ⏳ |
| 6 | `/elders` | GET | `pending-cr` | 待定 | 是 | — | ⏳ |
| 7 | `/elders/{elderId}` | GET | `pending-cr` | 待定 | 是 | — | ⏳ |
| 8 | `/security/reveal` | POST | `pending-cr` | 待定（敏感明文） | 是 | **是** | ⏳ |
| 9 | `/eval/tasks` | GET | `pending-cr` | 待定（任务列表） | 是 | — | ⏳ |
| 10 | `/eval/orders` | POST | `evaluation:order:create` | 评估员 ✅ / 机构管理员 ✅；家属 🔶 走 `:apply` | 是 | — | ⏳ |
| 11 | `/eval/orders/{orderId}/answers` | PUT | `evaluation:item:input` | 评估员 ✅ / 机构管理员 ✅ | 是 | — | ⏳ |
| 12 | `/eval/orders/{orderId}/grade` | POST | `evaluation:item:input` | 同上（分级属录入链路） | 是 | — | ⏳ |
| 13 | `/eval/orders/{orderId}/submit` | POST | `evaluation:item:input` | 同上（提交人=录入人） | 是 | — | ⏳ |
| 14 | `/eval/orders/{orderId}/review` | POST | `evaluation:order:review` | 机构管理员 ✅；监管 👁 走 `:read` | 是 | — | ⏳ |
| 15 | `/rule/versions` | GET | `pending-cr` | 待定（只读） | — | — | ⏳ |
| 16 | `/rule/mapping` | GET | `pending-cr` | 待定（只读） | — | — | ⏳ |
| 17 | `/reports/{reportId}` | GET | `report:view` | 五角色全 ✅（老人/家属仅**已发布**版） | 是 | — | ⏳ |
| 18 | `/reports/{reportId}/generate` | POST | `evaluation:order:review` | 机构管理员（复核发布链路） | 是 | — | ⏳ |
| 19 | `/reports/verify` | POST | `none` | 公开（扫码验真，需限频） | — | — | ⏳ |
| 20 | `/supervise/exports` | POST | `data:export` | 机构管理员 ✅ / 监管 ✅ | 是 | **是** | ⏳ |
| 21 | `/supervise/report` | POST | `supervise:report:submit` | 机构管理员 ✅ / 监管 ✅ | 是 | — | ⏳ |
| 22 | `/system/health` | GET | `none` | 公开（探针） | — | — | ✅ |

> 「实现 ✅」= 本轮已落地代码；「⏳」= 契约已登记、业务模块待建（C/D 任务集）。
> 第 3 行「入口」：二次验证本身是第四闸的凭据签发口，故不叠加第四闸校验。

### 2.1 硬约束落位（不可只靠 DDL）

| 约束 | 落点 | 说明 |
|---|---|---|
| 录入 / 复核人互斥 | 服务层 `EvaluationReviewPolicy` | `eval_order.reviewer_id` 仅注释「必须≠录入人」，**无 DB 约束**；权限矩阵已由种子保证 ASSESSOR 不持复核权限（CI 红线断言） |
| 敏感操作二次验证 | `PermissionAspect` → `SensitivePermissions` | 权限码命中敏感集合时自动转第四闸，接口侧无需重复编码 |
| 数据域行级过滤 | `DataScopePermissionHandler` | `data_scope` 1→本人 / 2→本机构 / 4→只读全局，未登记表默认不加限（随各模块补全） |

## 3 覆盖度缺口（7 处 `pending-cr`）——建议走 CR 补矩阵

| 缺口接口 | 场景 | 建议处置 |
|---|---|---|
| `/elders`（POST/GET）、`/elders/{elderId}` | 老人**建档与档案查看** | 新增矩阵行「老人档案管理」，派生 `elder:archive:create` / `elder:archive:read`（家属仅看绑定对象） |
| `/security/reveal` | 查看敏感字段**明文** | 新增 `data:reveal` 并置 `need_second_verify=1`（当前仅 3 个敏感点，明文查看必须进来） |
| `/eval/tasks` | 评估任务列表 | 复用 `evaluation:item:input` + `evaluation:order:review:read`（评估员/监管各取所需），或新增 `evaluation:task:read` |
| `/rule/versions`、`/rule/mapping` | 国标规则**只读**（含条款号追溯） | 新增 `rule:view`（登录即可读，用于报告条款回溯展示） |

> 处置建议**不自行实施**：权限点增补属 PRD §2.2 变更，须走 CR + CCB 会签，并同步 `03_seed_rbac.sql`
> 与 `verify-schema.sh` 断言（当前断言：五角色 5 / 权限点 16 / 授权 23 / 敏感点 ≥2 + 矩阵红线 4 条 = 0）。

## 4 反向缺口：权限点「无归属接口」

16 个权限点中，以下 6 类**尚未登记到契约**，属接口面遗漏（建议补入 `openapi/yl-api.yaml`）：

| 权限点 | 应有接口（建议） | 敏感 |
|---|---|:--:|
| `evaluation:order:void` | `POST /eval/orders/{orderId}/void` | **是** |
| `account:family:unbind` | `POST /family/unbind` | **是** |
| `account:family:bind` / `:reject` | `POST /family/bind`、`POST /family/bind/{id}/reject` | 否 |
| `care:plan:edit` / `:suggest` | `PUT /care/plans/{planId}` | 否 |
| `account:org:manage` / `:read` | `/account/orgs`、`/account/orgs/{orgId}` | 否 |
| `evaluation:order:review:read` | 已由 `/eval/orders/{orderId}/review` 的只读态承载（无需新增路径） | 否 |

> 本轮已补登 1 处契约缺失：`POST /api/v1/auth/second-verify`（含 `X-Second-Verify-Token` 头、300s 一次性语义）。

## 5 回填方式

- **契约层**：每个操作写 `x-required-permission: <perm-code>`；`none` = 免鉴权/仅需登录；`pending-cr` = 待 PRD 变更。
- **代码层**：接口实现时在 Controller 方法上标注 `@RequiresPermission(PermissionCode.XXX)`，与本文档同源；敏感点在 `PermissionAspect` 自动联动第四闸。
- **门禁建议（后续）**：CI 增加断言「`x-required-permission` 无 `pending-cr`」+「每个已实现 Controller 方法均有 `@RequiresPermission`」；在 CR 落地前前者不启用（避免红灯阻塞）。
