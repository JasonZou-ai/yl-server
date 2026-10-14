# 银龄守护 · 权限点清单（RBAC Permission Catalog）

> 编号 **YL-M2-RBAC-CATALOG-v1.2**｜编制 2026-09-22｜v1.1 更新 2026-09-24（D-1 契约冻结 + 数据域扩档）｜**v1.2 更新 2026-10-12（D4-R7 R8 补授 `care:plan:edit:suggest` → 老人，授权 40→41）**｜产出人：研发侧｜状态：**✅ 已落地（五处对齐）**
> 口径真源（唯一权威，按优先级）：`docker/mysql/init/03_seed_rbac.sql` → `docs/change/CR-M2-001-permission-matrix-gap.md` → PRD v1.2 §2.2
> 代码孪生：`PermissionCode`（22 常量）+ `SensitivePermissions`（4 敏感点）+ `ArchiveAccessRateGuard`（行为告警）
> 契约孪生：`openapi/yl-api.yaml` **v1.1.0**（43 操作 `x-required-permission` + `x-data-scope`，0 处 `pending-cr`）
> **v1.1 变更（D-1）**：`FAMILY.data_scope` 1 → **5（关系级）**；契约补登 9 个已实现端点并新增扩展键；
> 新增门禁「契约 ↔ 实现零漂移」与「全端点必挂 `@RequiresPermission`」

---

## 1 TL;DR

| 项 | 数值 | 相比 CR-M2-001 之前 |
|---|---:|---|
| 角色 | **5** | 不变（ELDER / ASSESSOR / FAMILY / ORG_ADMIN / SUPERVISOR，不得增删） |
| 权限点 | **22** | 16 → 22（+6，id 2017–2022） |
| 授权（角色×权限点） | **41** | 23 → 40 → **41**（+17 id 3024–3040，**+1 id 3041 = D4-R7 R8 补授**） |
| 敏感权限点（`need_second_verify=1`） | **4** | 3 → 4（+`data:reveal`） |
| 契约权限点明确率 | **100%**（43/43 操作带鉴权口径） | 68%（15/22，7 处 `pending-cr`） |
| 敏感数据域（`data_scope`） | 5 档：1/2/**5**/2/4 | 1/2/1/2/4（FAMILY 为 1，家属恒查不到数据） |

**本轮（#2 + #4 + #7）落地内容**：

1. **#2 代码常量** —— `PermissionCode` 增 6 常量、`SensitivePermissions` 增 `data:reveal`；
2. **#4 契约回填** —— `openapi/yl-api.yaml` 7 处 `pending-cr` → 明确权限码，明确率 100%；
3. **#7 审计配套** —— 新增 `ArchiveAccessRateGuard`：非护理角色滑动 1 小时调档案接口 > N（默认 50）→ 写审计告警 + 通知监管角色。

**改一处必须同步另外三处**（任一缺失即门禁红或运行时放行错）：种子 SQL / 代码常量 / 门禁断言 / 契约标注。

---

## 2 权限点全清单（22 个）

图例：**敏感** = `need_second_verify=1`（须过第四闸二次验证）；**归属** = 契约中承载该权限点的操作。

| # | id | `perm_code` | 名称 | module | 敏感 | 授权角色 | 契约归属 |
|---|---:|---|---|---|:--:|---|---|
| 1 | 2001 | `report:view` | 查看评估报告 | report | — | 五角色全部 | `GET /reports/{reportId}` |
| 2 | 2002 | `evaluation:order:create` | 新建评估 | evaluation | — | 评估员、机构管理员 | `POST /eval/orders` |
| 3 | 2003 | `evaluation:order:create:apply` | 发起代办评估申请（受限） | evaluation | — | 家属 | 受限态，随 `POST /eval/orders` |
| 4 | 2004 | `evaluation:item:input` | 录入评估指标 | evaluation | — | 评估员、机构管理员 | `POST /eval/orders/{orderId}/answers｜grade｜submit` |
| 5 | 2005 | `evaluation:order:review` | 复核/发布报告 | evaluation | — | 机构管理员 | `POST /eval/orders/{orderId}/review`、`POST /reports/{reportId}/generate` |
| 6 | 2006 | `evaluation:order:review:read` | 查看复核结果（只读） | evaluation | — | 监管 | 由 `POST /eval/orders/{orderId}/review` 只读态承载 |
| 7 | 2007 | `care:plan:edit` | 编辑照护方案 | care | — | 机构管理员 | 待补登（C/D 模块） |
| 8 | 2008 | `care:plan:edit:suggest` | 提交照护建议（受限） | care | — | 评估员、**老人**〔**D4-R7 R8 补授**〕 | `POST /eval/orders/{orderId}/care-plan` |
| 9 | 2009 | `account:org:manage` | 机构/人员管理 | account | — | 机构管理员 | 待补登（C/D 模块） |
| 10 | 2010 | `account:org:manage:read` | 查看机构/人员（只读） | account | — | 监管 | 待补登（C/D 模块） |
| 11 | 2011 | `supervise:report:submit` | 监管数据上报 | supervise | — | 机构管理员、监管 | `POST /supervise/report` |
| 12 | 2012 | `data:export` | 导出/批量操作 | report | **是** | 机构管理员、监管 | `POST /supervise/exports` |
| 13 | 2013 | `account:family:bind` | 亲情绑定/解绑 | account | — | 家属、机构管理员 | 待补登（C/D 模块） |
| 14 | 2014 | `account:family:bind:reject` | 拒绝亲情绑定（老人端） | account | — | 老人 | 待补登（C/D 模块） |
| 15 | 2015 | `evaluation:order:void` | 作废评估单 | evaluation | **是** | ⛔ 不常驻，运行时提权 | 待补登（C/D 模块） |
| 16 | 2016 | `account:family:unbind` | 解绑亲情关系 | account | **是** | ⛔ 不常驻，运行时提权 | 待补登（C/D 模块） |
| 17 | 2017 | `elder:archive:create` | 老人建档 | elder | — | 评估员、机构管理员 | `POST /elders` **〔本次回填〕** |
| 18 | 2018 | `elder:archive:create:apply` | 发起代办建档申请（受限） | elder | — | 家属 | 受限态，随 `POST /elders` **〔本次回填〕** |
| 19 | 2019 | `elder:archive:read` | 查看老人档案 | elder | — | 五角色全部（可见范围由 `data_scope` 控制） | `GET /elders`、`GET /elders/{elderId}` **〔本次回填〕** |
| 20 | 2020 | `evaluation:task:read` | 评估任务列表查看 | evaluation | — | 评估员、机构管理员、监管 | `GET /eval/tasks` **〔本次回填〕** |
| 21 | 2021 | `data:reveal` | 查看敏感字段明文 | security | **是** | **仅机构管理员** | `POST /security/reveal` **〔本次回填〕** |
| 22 | 2022 | `rule:view` | 国标规则只读（条款回溯） | rule | — | 五角色全部 | `GET /rule/versions`、`GET /rule/mapping` **〔本次回填〕** |

> 第 17–22 项即 **CR-M2-001 新增矩阵行 10–14** 的展开（行 10 拆出「直接建档」与「代办申请」两个权限点）。

### 2.1 按模块分布

| module | 权限点数 | 权限点 |
|---|---:|---|
| evaluation | 7 | 2002–2006、2015、2020 |
| account | 5 | 2009、2010、2013、2014、2016 |
| elder | 3 | 2017、2018、2019 |
| care | 2 | 2007、2008 |
| report | 2 | 2001、2012 |
| supervise | 1 | 2011 |
| security | 1 | 2021 |
| rule | 1 | 2022 |

---

## 3 角色 × 权限点矩阵（22 × 5）

图例：**✅** 直接授予｜**🔶** 受限态（代办/建议/可拒绝）｜**👁** 只读｜**⛔** 未授予

| `perm_code` | ELDER<br>老人 | ASSESSOR<br>评估员 | FAMILY<br>家属 | ORG_ADMIN<br>机构管理员 | SUPERVISOR<br>监管 |
|---|:--:|:--:|:--:|:--:|:--:|
| `report:view` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `evaluation:order:create` | ⛔ | ✅ | ⛔ | ✅ | ⛔ |
| `evaluation:order:create:apply` | ⛔ | ⛔ | 🔶 | ⛔ | ⛔ |
| `evaluation:item:input` | ⛔ | ✅ | ⛔ | ✅ | ⛔ |
| `evaluation:order:review` | ⛔ | ⛔ | ⛔ | ✅ | ⛔ |
| `evaluation:order:review:read` | ⛔ | ⛔ | ⛔ | ⛔ | 👁 |
| `care:plan:edit` | ⛔ | ⛔ | ⛔ | ✅ | ⛔ |
| `care:plan:edit:suggest` | 🔶〔**R8 补授**〕 | 🔶 | ⛔ | ⛔ | ⛔ |

| `account:org:manage` | ⛔ | ⛔ | ⛔ | ✅ | ⛔ |
| `account:org:manage:read` | ⛔ | ⛔ | ⛔ | ⛔ | 👁 |
| `supervise:report:submit` | ⛔ | ⛔ | ⛔ | ✅ | ✅ |
| `data:export` **〔敏感〕** | ⛔ | ⛔ | ⛔ | ✅ | ✅ |
| `account:family:bind` | ⛔ | ⛔ | ✅ | ✅ | ⛔ |
| `account:family:bind:reject` | 🔶 | ⛔ | ⛔ | ⛔ | ⛔ |
| `evaluation:order:void` **〔敏感〕** | ⛔ | ⛔ | ⛔ | ⛔ | ⛔ |
| `account:family:unbind` **〔敏感〕** | ⛔ | ⛔ | ⛔ | ⛔ | ⛔ |
| `elder:archive:create` | ⛔ | ✅ | ⛔ | ✅ | ⛔ |
| `elder:archive:create:apply` | ⛔ | ⛔ | 🔶 | ⛔ | ⛔ |
| `elder:archive:read` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `evaluation:task:read` | ⛔ | ✅ | ⛔ | ✅ | 👁 |
| `data:reveal` **〔敏感〕** | ⛔ | ⛔ | ⛔ | ✅ | ⛔ |
| `rule:view` | ✅ | ✅ | ✅ | ✅ | ✅ |
| **合计（该角色持有）** | **5** | **8** | **6** | **14** | **8** |

> 角色持有合计 **5+8+6+14+8 = 41**，与种子 `sys_role_permission` 的 **41 条记录逐一对应**（上表每个 ✅/🔶/👁 格对应 1 条授权记录；
> 受限态与只读态同样是独立授权记录）。校验方式见 §7.2。
>
> **🔶 老人持有 `care:plan:edit:suggest`（id 3041，D4-R7 R8 补授）的前提** —— **仅靠权限点不构成放行**：
> 第二闸放行后，写路径 `CarePlanService.generate(orderId, operatorId)` 内部经 `reportService.findReport(orderId)`
> 的 `reportMapper.selectOne` 已被**第三闸 `DataScopePermissionHandler`** 收口：`eval_report` 锚 `elder_id → elder.user_id`，
> 老人传入**非本人** `orderId` 时查询即落空 → `NOT_FOUND`，**无需再补服务层冗余归属校验**。
> 若第三闸锚登记回退，则该授权立刻退化为**写越权**，此二者必须**同批变更、不得单独回滚任一处**。

### 3.1 数据域（第三闸 `data_scope`）

| 角色 | `data_scope` | 含义 |
|---|---:|---|
| ELDER | 1 | 本人（**档案域锚列 `elder.user_id`，D-2 新增**） |
| ASSESSOR | 2 | 本机构 |
| FAMILY | **5** | **关系级** —— 仅经 `elder_family_bind` **生效**绑定可达的老人（含其评估单/报告/照护方案） |
| ORG_ADMIN | 2 | 本机构 |
| SUPERVISOR | 4 | 只读全局 |

> **★ FAQ：`data_scope` 的数值大小 ≠ 可见范围大小。** `5-关系级` 比 `2-本机构`**更窄**（只到"我绑定的人"）。
> 因此第三闸**必须按枚举逐值分支**判定，禁止写成 `scope >= ALL.code()` —— 那样会把 5 当成"不加限"，
> 家属立刻获得全库可见性（属跨机构泄露）。回归用例见 `DataScopePermissionHandlerTest#familyScopeIsNeverTreatedAsUnlimited`。
>
> **`scope=1`（本人）的显式语义（D-2 裁决二钉死）**：**仅对存在「用户锚列」的表按锚过滤**；档案域内无用户锚列的表
> （`elder_family_bind` 锚 `family_user_id`、`elder_authorization` 锚 `grantee_user_id`、`consent_record` 锚 `subject_type + subject_id`）
> 一律**失败即拒**（`SELF_DENY_TABLES` 恒假）—— 这是**显式裁决**，不是"未登记即放行"的暗区。
>
> **`elder` 表原无任何账号锚列**，而 `elder:archive:read` 已授予 ELDER（种子 id 3027）→ 属「**权限已授却永久不可达**」的
> **静默失效**（无任何门禁会报错）。D-2 补 `elder.user_id BIGINT NULL`（+ 可空唯一键 `uk_elder_user`）并登记 `elder → user_id`，
> 使契约 `by-role: {ELDER: self}` 成为**可实现**语义。绑定写入流程本身属残留 `D-2R1`，本期只加列与登记。
>
> **FAMILY 为何由 1 改为 5（D-1 裁决 2）**：家属的可见范围是"经绑定可达"，既非本人也非本机构，且 `scope=1` 下无锚可表达，
> 原实现只能"失败即拒"→ **家属端接口全部实现后仍查不到任何数据**（D-1 §5.1 阻断面）。关系级以 `elder_family_bind` 为唯一真源收口。
>
> ⚠ **`elder:archive:read` 五角色全授予，可见范围不靠权限点区分、由 `data_scope` 控制** —— 避免为「本人/绑定对象」再造
> 2 个权限码，与既定「不为数据范围分裂权限点」口径一致（家属端读接口同理复用 `report:view`，D-1 裁决 3）。
> 正因权限点层面的 ✅ 不再具备限速含义，才必须补 §5 的行为异常检测兜底批量拉档风险（CR-M2-001 §2.5）。

---

## 4 敏感权限点（4 个）与第四闸

| id | `perm_code` | 名称 | 授权角色 | 第四闸要求 |
|---|---|---|---:|---|
| 2012 | `data:export` | 导出/批量操作 | 机构管理员、监管 | 二次验证 + 全留痕 |
| 2015 | `evaluation:order:void` | 作废评估单 | 不常驻（运行时提权） | 二次验证 + 全留痕 |
| 2016 | `account:family:unbind` | 解绑亲情关系 | 不常驻（运行时提权） | 二次验证 + 全留痕 |
| 2021 | `data:reveal` | 查看敏感字段明文 | 仅机构管理员 | 二次验证 + 全留痕 |

- 代码登记：`SensitivePermissions.codes()`（4 个）；`PermissionAspect` 命中即转第四闸（`X-Second-Verify-Token`，Redis `GETDEL`，TTL 300s）。
- 门禁断言：`sys_permission.need_second_verify=1` 精确 = **4**。
- 设计取舍：2015 / 2016 **不常驻授权**，由机构管理员运行时经二次验证后临时提权（避免权限常驻，PRD §2.2 备注）。

---

## 5 权限点 ↔ 契约归属（22 操作）

### 5.1 已明确归属（本次回填 7 处 `pending-cr`）

| 契约操作 | 回填后权限码 |
|---|---|
| `POST /elders` | `elder:archive:create` |
| `GET /elders`、`GET /elders/{elderId}` | `elder:archive:read` |
| `POST /security/reveal` | `data:reveal`（**+ 第四闸**） |
| `GET /eval/tasks` | `evaluation:task:read` |
| `GET /rule/versions`、`GET /rule/mapping` | `rule:view` |

**契约权限点明确率 68% → 100%**（22/22，0 处 `pending-cr`）。

### 5.2 反向缺口：6 类接口尚无归属（= **8 个权限码**，不阻塞 CR-M2-001，随 C/D 模块补登）

| 权限点 | 应补接口（建议） | 敏感 |
|---|---|:--:|
| `evaluation:order:void` | `POST /eval/orders/{orderId}/void` | **是** |
| `account:family:unbind` | `POST /family/unbind` | **是** |
| `account:family:bind` / `:reject` | `POST /family/bind`、`POST /family/bind/{id}/reject` | 否 |
| `care:plan:edit` / `:suggest` | `PUT /care/plans/{planId}` | 否 |
| `account:org:manage` / `:read` | `GET /account/orgs`、`GET /account/orgs/{orgId}` | 否 |
| `evaluation:order:review:read` | 已由 `POST /eval/orders/{orderId}/review` 只读态承载（无需新增路径） | 否 |

> 补登后契约由 **21 路径 / 22 操作 → 28 路径 / 29 操作**。

> ⚠ **口径纠偏**：「6 类」是**接口维度**归类（`bind`/`reject` 同属 `/family`、`care` 两码同属 `/care/plans`、
> `org` 两码同属 `/account/orgs`）；落到**权限码维度是 8 个**。前端标注必须按 **8 个码**逐一标，否则会漏掉
> `:reject` / `:suggest` / `:read` 这三个「同路径不同码」的点。

### 5.3 前端接入须知

- 常量参考实现：`docs/design/B2-frontend-permission-codes.ts`（22 码 + `UNIMPLEMENTED_PERMS` 8 码 + `SENSITIVE_PERMS` 4 码）。
- 前端**只做入口显隐**，不做鉴权判定；真正的判定在后端四道闸。
- `UNIMPLEMENTED_PERMS` 中的码：即使登录态下发了，也只能用于**置灰/隐藏入口**，**不得发起请求**（后端无接口，调必 404）。
- `SENSITIVE_PERMS` 中的码：命中前先弹二次验证入口，避免请求被打回 `SECOND_VERIFY_REQUIRED (10008)`。
- 注：`evaluation:order:review:read` 已由现有 `POST /eval/orders/{orderId}/review` 只读态承载，**不属于未实现**。

---

## 6 行为异常检测（CR-M2-001 §2.5 · PM 补充意见）

| 项 | 口径 | 落地位置 |
|---|---|---|
| 监控对象 | **非护理角色**：ELDER / FAMILY / SUPERVISOR（ASSESSOR / ORG_ADMIN 为护理相关角色，默认不纳入） | `yl.security.archive-access-guard.monitored-roles` |
| 触发条件 | 同一账号对档案类接口（`elder:archive:read`）**滑动 1 小时**内调用 **> N**，默认 **N=50** | `threshold` / `window-minutes` |
| **处置模式** | 配置项 **`mode`**：`off` 旁路（不计数）｜`audit` 只留痕不通知（灰度期）｜**`alert` 留痕 + 通知（默认，不拦截）**｜`enforce` 留痕 + 通知 + 拒绝请求（`RATE_LIMITED 10429`，**须先变更契约**）。未知值回落 `alert`——配置写错时保持监控强度，不静默关掉护栏 | `ArchiveAccessGuardMode` |
| 统计窗口 | **滑动 1 小时（非自然小时）**——Redis ZSET + Lua 原子「修剪—写入—计数」 | `RedisSlidingWindowCounter` |
| 处置动作 | 写 `audit_log`（`action=ARCHIVE_ACCESS_ALERT`）→ 通知监管角色，同窗口内去重 | `ArchiveAccessRateGuard` |
| 失败姿态 | **失败开放**：计数/去重/通知异常只记警告，绝不阻断档案读取 | `ArchiveAccessRateGuard` |

**未实现的选项（需明示）**：CR 把「降级为单次二次验证」列为**可选**动作。档案读取是非敏感权限点，强制二次验证会使客户端在无
`X-Second-Verify-Token` 时直接失败，属契约破坏性变更，超出本 CR 授权范围，**故本期不实现**；生产如需启用，应在 C/D 模块落地时一并变更契约。

**告警响应 SLA 与处置流程**：见 `docs/design/B2-archive-access-alert-sop.md`（v1.0）——告警分级 S1/S2/S3、确认 T+15~30min、
闭环 T+2h~1 工作日、超时升级、灰度路径 G1 `audit` → G2 `alert` → G3 `enforce`（阻塞于契约变更）。值班人选待 PM 指派，
未回填前 SLA 时限不生效。

---

## 7 对齐与校验

### 7.1 四处对齐点

| 位置 | 文件 | 现状 |
|---|---|---|
| 种子（权威） | `docker/mysql/init/03_seed_rbac.sql` §2 / §4 / §6 | 22 权限点 + **41** 授权（含 D4-R7 R8 补授 id 3041） |
| 代码常量 | `PermissionCode` / `SensitivePermissions` | 22 常量 + 4 敏感点 |
| 门禁断言 | `scripts/verify-schema.sh` | 权限点 = 22、授权 = **41**、敏感点 = 4、五角色 = 5、矩阵红线 8 条 |
| 契约标注 | `openapi/yl-api.yaml` | 22 操作 100% `x-required-permission`，0 处 `pending-cr` |

### 7.2 自检 SQL（与种子 §5 一致）

```sql
SELECT (SELECT COUNT(*) FROM sys_role)                                    AS roles_expected_5,
       (SELECT COUNT(*) FROM sys_permission WHERE deleted = 0)            AS perms_expected_22,
       (SELECT COUNT(*) FROM sys_role_permission)                         AS grants_expected_41,
       (SELECT COUNT(*) FROM sys_permission WHERE need_second_verify = 1) AS sensitive_expected_4;
```

### 7.3 门禁命令

```bash
MYSQL_BIN=<mysql> bash scripts/verify-schema.sh   # 期望：通过 63 项 / 失败 0 项 / 待批准守卫 0 项
mvn -Pfast verify                                 # 期望：格式 + 编译 + 单测全绿
```

---

## 8 变更记录

| 日期 | 动作 | 结果 | 责任人 |
|---|---|---|---|
| 2026-09-21 | CR-M2-001 CCB 会签 + PM 签发通过 | ✅ 6 权限点 + 17 授权入库；8 条红线断言转真 | CCB / PM |
| 2026-09-21 | 种子 + 断言落地（提交 `0a356f9`→rebase `418082b`） | ✅ 权限点 16→22、授权 23→40、敏感点 3→4 | 研发侧 |
| 2026-09-22 | **#2 代码常量同步** | ✅ `PermissionCode` 22 常量、`SensitivePermissions` 4 敏感点 | 研发侧 |
| 2026-09-22 | **#4 契约回填** | ✅ 7 处 `pending-cr` 清零，明确率 100% | 研发侧 |
| 2026-09-22 | **#7 审计配套** | ✅ `ArchiveAccessRateGuard` + 滑动窗口计数 + 告警通知 | 研发侧 |
| 2026-09-22 | **#6 文档同步** | ✅ 矩阵升 v2（缺口清零）、设计 §2/§5/§7/§9 同步 | 研发侧 |
| 2026-09-22 | **告警 `mode` 配置项** | ✅ `ArchiveAccessGuardMode`（off/audit/alert/enforce，默认 alert），12 例单测 | 研发侧 |
| 2026-09-22 | **告警响应 SOP v1.0** | ✅ `docs/design/B2-archive-access-alert-sop.md`（分级 + SLA + 灰度路径） | 研发侧 |
| 2026-09-22 | **前端权限码常量** | ✅ `docs/design/B2-frontend-permission-codes.ts`（22 码 + 未实现 8 码 + 敏感 4 码） | 研发侧 |
| — | 落地清单 #5（回写 PRD §2.2 + 修订记录） | ✅ **已落地（2026-10-14，提前于原限 2026-10-17）**：CR-M2-001 回写稿已**实质并入** `PRD_YL-PRD-v1.2.md` §2.2 正文（9 行 → **15 行** + `perm_code` 列 + §2.2.1 数据域 / §2.2.2 行为异常检测 / §2.2.3 敏感权限点），横幅改「已追认」并留痕，补遗件转归档；**归档提交 SHA = `a0bf97f0187074c82c5c36049c72856e2d53ecf9`**（CR-M2-009 会签通过，会签栏记录签署日 2026-10-15）。历史经过：横幅原限期 2026-09-24 届满未获追认，2026-10-10 顺延至 2026-10-17；盘上 md/html/补遗三件已于 2026-10-11 重推。**盘上旧件为「回写前版本」，不得再作现行口径**（README 权威位声明，PM 拍板 A）。执行记录：`docs/change/CR-M2-009-prd-section-2.2-merge.md` | 产品 |
| 2026-10-12 | **D4-R7 R8 写路径补授（PM 裁决①选项 A）** | ✅ 种子新增 `(3041, 1001, 2008)`；授权 40→**41**；本清单 L46 / §3 矩阵 / §7.1 / §7.2 / `verify-schema.sh` 五处同步。**须与第三闸 `eval_report` 锚同批改，禁止单独回滚** | 研发侧（PM 裁决） |
| — | 反向缺口 6 类补登契约 | ⏳ 待办（随 C/D 模块，不阻塞本 CR） | 研发 |
