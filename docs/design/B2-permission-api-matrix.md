# B2 权限点 ↔ API 对照矩阵（逐接口回填）

> 依据：PRD **v1.2** §2.1 角色定义 / §2.2 权限矩阵、`openapi/yl-api.yaml`（**v1.1.0**）、`docs/design/B2-account-rbac-design.md` §3
> 口径：**不得自造角色或简并权限点**——PRD §2.2 未覆盖的接口一律标记 `pending-cr`，不臆造权限码
> 版本：**v3（2026-09-24）**｜产出人：研发侧｜状态：**✅ 契约↔实现↔库 三方对齐（漂移门禁已启用）**
> v2（2026-09-22）→ v3 变更：随 **D-1 契约冻结**（v1.1.0）完成路径对齐与 C 类端点补登；§2 表按实现路径重写；
> §3 反向缺口状态回填；§4 数据域扩到 5 档；§5 两条门禁**已实际启用**（此前仅"具备条件"）。
> v1（2026-09-21）→ v2 变更：CR-M2-001 经 CCB 会签 + PM 签发，7 处 `pending-cr` 全部回填为明确权限码（落地清单 #4）

## 1 TL;DR

1. 契约已升 **v1.1.0：43 个操作**（`implemented 22` + `planned 21`），**100% 带 `x-required-permission` 与 `x-data-scope`**，
   `pending-cr` 仍为 **0**。
2. **「契约 = 实现镜像」属性已修复并上锁**：v1.0.0 时 22 个操作里只有 3 个与实现对齐（9 项路径漂移、10 项契约写了没实现、
   9 项实现了没登记）。现由 **`scripts/verify-openapi.sh` + `OpenApiContractTest`** 双向核对，漂移即红。
3. 权限点仍为 **22 个**（D-1 裁决 3/5 **未新增任何权限码**）：家属端读接口复用 `report:view`，老人侧绑定只保留 `:reject`。
4. **数据域扩到 5 档**：新增 `5-关系级`（家属）—— `FAMILY.data_scope` 由 1 改 5，第三闸按 `elder_family_bind` 生效绑定收口
   （D-1 裁决 2；零 DDL）。`1/2/3/4` 语义不变。
5. `planned` 21 项分布：B1~B10（10，D-2/D-3/D-4）+ 组织管理 R6（2，D-3）+ 家属端 F1~F9（9，D-4）。

## 2 逐接口对照

图例：**③** = 数据域闸（`data_scope` 行级过滤）｜**④** = 二次验证闸（`need_second_verify=1`）
「实现」列：✅ 已落地代码 ｜ ⏳ 契约已登记、待子卡实现（**与契约 `x-status` 字段同源**）

| # | 接口（**v1.1.0 路径**） | 方法 | 所需权限点 | 放行角色（PRD §2.2） | ③ | ④ | 实现 |
|---|---|---|---|---|:--:|:--:|:--:|
| 1 | `/auth/login/{channel}` | POST | `none` | 全部（免鉴权） | — | — | ✅ |
| 2 | `/auth/refresh` | POST | `none` | 全部（免鉴权） | self | — | ✅ |
| 3 | `/auth/second-verify` | POST | `none` | 已登录用户 | self | 入口 | ✅ |
| 4 | `/auth/logout` | POST | `none` | 已登录用户 | self | — | ⏳ |
| 5 | `/elders` | POST | `elder:archive:create` | 评估员 ✅ / 机构管理员 ✅；家属 🔶 走 `:apply` | org | — | ✅ |
| 6 | `/elders` | GET | `elder:archive:read` | 五角色全 ✅（可见范围由 ③ 控制） | **relation** | — | ✅ |
| 7 | `/elders/{elderId}` | GET | `elder:archive:read` | 同上 | **relation** | — | ✅ |
| 8 | `/security/reveal` | POST | `data:reveal` | **仅机构管理员** | org | **是** | ✅ |
| 9 | `/evaluation/tasks` | GET | `evaluation:task:read` | 评估员 ✅ / 机构管理员 ✅；监管 👁 | org | — | ✅ |
| 10 | `/evaluation/assessments` | POST | `evaluation:order:create` | 评估员 ✅ / 机构管理员 ✅；家属 🔶 走 `:apply` | org | — | ✅ |
| 11 | `/evaluation/assessments/{orderId}/answers` | PUT | `evaluation:item:input` | 评估员 ✅ / 机构管理员 ✅ | org | — | ✅ |
| 12 | `/evaluation/grade` | POST | `evaluation:item:input` | 同上（无状态试算，不落库） | org | — | ✅ |
| 13 | `/evaluation/assessments/{orderId}/submit` | POST | `evaluation:item:input` | 同上（提交人=录入人） | org | — | ✅ |
| 14 | `/evaluation/assessments/{orderId}` | GET | `evaluation:task:read` **|** `report:view` | 读类「码从宽、域从严」 | org | — | ✅ |
| 15 | `/evaluation/assessments/{orderId}/review/publish` | POST | `evaluation:order:review` | 机构管理员 ✅；监管 👁 走 `:read` | org | — | ✅ |
| 16 | `/evaluation/assessments/{orderId}/review/reject` | POST | `evaluation:order:review` | 同上 | org | — | ✅ |
| 17 | `/evaluation/assessments/{orderId}/review/void` | POST | `evaluation:order:review` **|** `evaluation:order:void` | 机构管理员（OR 语义，见下注） | org | **是** | ✅ |
| 18 | `/evaluation/assessments/{orderId}/review-logs` | GET | `evaluation:order:review:read` **|** `:review` **|** `evaluation:task:read` | 监管 / 机构管理员 / 录入评估员 | org | — | ✅ |
| 19 | `/evaluation/items` | GET | `evaluation:task:read` **|** `evaluation:item:input` | 录入方与任务查看方 | all | — | ✅ |
| 20 | `/evaluation/rule-meta` | GET | `rule:view` | 五角色全 ✅（登录即可读） | all | — | ✅ |
| 21 | `/evaluation/assessments/{orderId}/report` | POST | `evaluation:item:input` | 评估员 / 机构管理员（生成） | org | — | ✅ |
| 22 | `/evaluation/assessments/{orderId}/report` | GET | `report:view` | 五角色全 ✅（老人/家属仅**已发布**版） | org | — | ✅ |
| 23 | `/evaluation/assessments/{orderId}/report/pdf` | POST | `report:view` | 评估员 / 机构管理员 / 监管 | org | — | ✅ |
| 24 | `/evaluation/reports/{reportNo}/pdf` | GET | `report:view` | 五角色全 ✅（范围由 ③ 控制） | **relation** | — | ✅ |
| 25 | `/evaluation/assessments/{orderId}/care-plan` | POST | `care:plan:edit` **|** `care:plan:edit:suggest` | 机构管理员 / 评估员🔶 / **老人🔶〔D4-R7 R8 补授 id 3041；写范围由 ③ `self` 兜底〕** | **self**/org | — | ✅ |
| 26 | `/evaluation/assessments/{orderId}/care-plan` | GET | `report:view` **|** `care:plan:edit` **|** `:suggest` | 同上 + 只读方（**老人持 `report:view` 早已过 ②，本轮补 ③ `self` 后方真正可读**） | self/org/relation/all | — | ✅ |
| 27 | `/evaluation/assessments/{orderId}/care-plan/confirm` | POST | `care:plan:edit` | 机构管理员（须 ≠ 建议人） | org | — | ✅ |
| 28 | `/rule/mapping` | GET | `rule:view` | 五角色全 ✅ | all | — | ✅ |
| 29 | `/reports/verify` | GET | `none` | 公开（扫码验真，需限频） | public | — | ✅ |
| 30 | `/supervise/exports` | POST | `data:export` | 机构管理员 ✅ / 监管 ✅ | org | **是** | ✅ |
| 31 | `/supervise/report` | POST | `supervise:report:submit` | 机构管理员 ✅ / 监管 ✅ | org | — | ✅ |
| 32 | `/account/orgs` | GET | `account:org:manage` **|** `:read` | 机构管理员 / 监管👁 | org | — | ✅ |
| 33 | `/account/orgs/{orgId}` | GET | `account:org:manage` **|** `:read` | 同上 | org | — | ✅ |
| 34 | `/family/bindings` | GET | `account:family:bind` | 家属 ✅ / 机构管理员 ✅ | **relation** | — | ⏳ |
| 35 | `/family/bindings` | POST | `account:family:bind` | 家属 ✅ | **relation** | — | ⏳ |
| 36 | `/family/bindings/{id}/approve` | POST | `account:family:bind` | 机构管理员（PRD §4.2 ④ 机构审核） | org | — | ⏳ |
| 37 | `/family/bindings/{id}/reject` | POST | `account:family:bind:reject` | 老人 🔶（**仅拒绝**，放行靠机构审核） | **relation** | — | ⏳ |
| 38 | `/family/bindings/{id}/unbind` | POST | `account:family:unbind` | 运行时二次验证提权（不常驻授权） | **relation** | **是** | ⏳ |
| 39 | `/family/bindings/{relationId}/reports` | GET | `report:view` | 家属（仅**已发布**） | **relation** | — | ⏳ |
| 40 | `/family/bindings/{relationId}/reports/{reportId}` | GET | `report:view` | 同上 | **relation** | — | ⏳ |
| 41 | `/family/bindings/{relationId}/trend` | GET | `report:view` | 同上 | **relation** | — | ⏳ |
| 42 | `/family/bindings/{relationId}/care-plan` | GET | `report:view` | 家属（仅**已确认**方案） | **relation** | — | ⏳ |
| 43 | `/system/health` | GET | `none` | 公开（探针） | public | — | ✅ |

> 第 17 行 **OR 语义**：`evaluation:order:void` 为敏感点且种子**不授予任何角色**（设计为运行时经二次验证临时提权）。
> 若按"必须持有该码"校验，作废会变成谁都调不了死路（"全量 403 假性收紧"）。故取
> `evaluation:order:review` **或** `evaluation:order:void` —— 只要码表**含**敏感码，`PermissionAspect` 即联动第四闸强制二次验证，
> 最终效力与设计裁决一致（机构管理员 + 二次验证方可作废）。详见 `AssessmentController.voidOrder` 注释。
> 第 3 行「入口」：二次验证本身是第四闸的凭据签发口，故不叠加第四闸校验。
> 第 6/7/24/34~42 行的 **relation** 档即 D-1 裁决 2 的关系级；第 39~42 行复用 `report:view`（不新增家属专用码，裁决 3）。

### 2.1 硬约束落位（不可只靠 DDL）

| 约束 | 落点 | 说明 |
|---|---|---|
| 录入 / 复核人互斥 | 服务层 `AssessmentReviewService` | `eval_order.reviewer_id` 仅注释「必须≠录入人」，**无 DB 约束**；权限矩阵已由种子保证 ASSESSOR 不持复核权限（CI 红线断言） |
| 敏感操作二次验证 | `PermissionAspect` → `SensitivePermissions` | 权限码命中敏感集合时自动转第四闸（4 个敏感点），接口侧无需重复编码 |
| 数据域行级过滤 | `DataScopePermissionHandler` | `1→本人 / 2→本机构 / 3→全量 / 4→只读全局 / 5→关系级`；**按枚举逐值分支**（不可写成 `scope >= ALL`：5 比 2 更窄，数值序不代表宽严序）；未登记表默认不加限（随各模块补全） |
| **档案批量拉取异常检测** | `ArchiveAccessRateGuard`（CR-M2-001 §2.5） | 因 `elder:archive:read` 五角色全授予、限速含义由权限点转移至行为检测：非护理角色滑动 1 小时调用 > N（默认 50）→ 写 `audit_log` 告警 + 通知监管角色。**只告警、不拦截、失败开放** |
| **鉴权覆盖（每个端点必挂码）** | `ControllerAuthorizationCoverageTest`（CR-M2-006 新增） | 反射遍历全部控制器的处理端点，未声明 `@RequiresPermission` 即红；白名单仅 4 条会话入口/探针，且**禁止冗余豁免** |

## 3 覆盖度缺口 —— ✅ 已闭环（原 7 处 `pending-cr`）

| 原缺口接口 | 场景 | 闭环结果 |
|---|---|---|
| `/elders`（POST/GET）、`/elders/{elderId}` | 老人**建档与档案查看** | 新增矩阵行 10/11，派生 `elder:archive:create` / `:create:apply` / `elder:archive:read` |
| `/security/reveal` | 查看敏感字段**明文** | 新增 `data:reveal` 并置 `need_second_verify=1`（敏感点 3 → 4） |
| `/evaluation/tasks` | 评估任务列表 | 新增 `evaluation:task:read` |
| `/evaluation/rule-meta`、`/rule/mapping` | 国标规则**只读**（含条款号追溯） | 新增 `rule:view`（登录即可读） |

> 处置流程与既定口径一致：权限点增补属 PRD §2.2 变更 → 走 **CR-M2-001 + CCB 会签（5 角色）+ PM 签发** →
> 同步 `03_seed_rbac.sql`、`PermissionCode`、`SensitivePermissions`、`openapi/yl-api.yaml` 与
> `verify-schema.sh` 断言（现为断言总数 **81** 项全绿）。

## 4 反向缺口：权限点「无归属接口」—— ✅ 已全部登记（v3 更新）

v2 遗留的 6 类"接口面遗漏"**已随 D-1 契约冻结全部登记进 `yl-api.yaml` v1.1.0**，状态如下：

| 权限点 | 登记结果（v1.1.0 实际路径） | 敏感 | 状态 |
|---|---|:--:|---|
| `evaluation:order:void` | `POST /evaluation/assessments/{orderId}/review/void` | **是** | ✅ 已实现（C2 类盘点发现**早已实现**，只是契约未登） |
| `account:family:unbind` | `POST /family/bindings/{id}/unbind` | **是** | 📌 登记为 `planned`（D-4） |
| `account:family:bind` / `:reject` | `POST /family/bindings`、`POST /family/bindings/{id}/approve`、`{id}/reject` | 否 | 📌 `planned`（D-4） |
| `care:plan:edit` / `:suggest` | `POST .../care-plan`（生成）、`.../care-plan/confirm`（确认）、`GET .../care-plan` | 否 | ✅ 已实现（契约按实现改判为"按 orderId 生成 + 确认"；v2 设想的 `PUT /care/plans/{planId}` **未实现**，随后续需求评估是否保留） |
| `account:org:manage` / `:read` | `GET /account/orgs`、`GET /account/orgs/{orgId}` | 否 | ✅ 已实现（D-3，R6） |
| `evaluation:order:review:read` | 已由 `GET .../review-logs` 承载（**无需新增路径**） | 否 | ✅ 已实现 |

> **路径口径修正**：v2 §4 建议的 `/eval/orders/{orderId}/void`、`/family/unbind`、`/family/bind` 等路径**作废** ——
> D-1 裁决 1 采「存量向实现对齐」，统一为 `/evaluation/**` 与 `/family/bindings/{id|relationId}/**`（资源化、以绑定 id 为轴）。
> **补登后规模**：21 路径 / 22 操作 → **39 路径 / 43 操作**（`implemented 22` + `planned 21`）。

## 5 回填方式

- **契约层**：每个操作写 `x-required-permission`（多码用 ` | ` 表 OR）+ `x-data-scope.mode` + `x-status`；`none` 仅两类（public 模式、会话相关端点）。
- **代码层**：接口实现时在 Controller 方法标注 `@RequiresPermission(PermissionCode.XXX)`，与本文档同源；敏感点在 `PermissionAspect` 自动联动第四闸。
- **门禁（✅ v3 已实际启用，不再是"具备条件"）**：
  1. **「每个已实现 Controller 方法均有 `@RequiresPermission`」** → `ControllerAuthorizationCoverageTest`（反射，含白名单冗余自检）；
  2. **「契约 ↔ 实现路径零漂移」** → `scripts/verify-openapi.sh` + `OpenApiContractTest`（双向核对 `x-status: implemented`）；
  3. **「契约权限码必须存在于常量表与 `sys_permission`」** → 前者在 `OpenApiContractTest`，后者在 `scripts/verify-schema.sh`（连库）。
