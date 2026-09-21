# 银龄守护 · 权限点清单（RBAC Permission Catalog）

> 编号 **YL-M2-RBAC-CATALOG-v1.0**｜编制 2026-09-22｜产出人：研发侧｜状态：**✅ 已落地（四处对齐）**
> 口径真源（唯一权威，按优先级）：`docker/mysql/init/03_seed_rbac.sql` → `docs/change/CR-M2-001-permission-matrix-gap.md` → PRD v1.1 §2.2
> 代码孪生：`PermissionCode`（22 常量）+ `SensitivePermissions`（4 敏感点）+ `ArchiveAccessRateGuard`（行为告警）
> 契约孪生：`openapi/yl-api.yaml`（22 操作 `x-required-permission`，0 处 `pending-cr`）

---

## 1 TL;DR

| 项 | 数值 | 相比 CR-M2-001 之前 |
|---|---:|---|
| 角色 | **5** | 不变（ELDER / ASSESSOR / FAMILY / ORG_ADMIN / SUPERVISOR，不得增删） |
| 权限点 | **22** | 16 → 22（+6，id 2017–2022） |
| 授权（角色×权限点） | **40** | 23 → 40（+17，id 3024–3040） |
| 敏感权限点（`need_second_verify=1`） | **4** | 3 → 4（+`data:reveal`） |
| 契约权限点明确率 | **100%**（22/22） | 68%（15/22，7 处 `pending-cr`） |
| 敏感数据域（`data_scope`） | 5 档：1/1/2/2/4 | 不变 |

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
| 8 | 2008 | `care:plan:edit:suggest` | 提交照护建议（受限） | care | — | 评估员 | 待补登（C/D 模块） |
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
| `care:plan:edit:suggest` | ⛔ | 🔶 | ⛔ | ⛔ | ⛔ |
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
| **合计（该角色持有）** | **4** | **8** | **6** | **14** | **8** |

> 角色持有合计 **4+8+6+14+8 = 40**，与种子 `sys_role_permission` 的 **40 条记录逐一对应**（上表每个 ✅/🔶/👁 格对应 1 条授权记录；
> 受限态与只读态同样是独立授权记录）。校验方式见 §7.2。

### 3.1 数据域（第三闸 `data_scope`）

| 角色 | `data_scope` | 含义 |
|---|---:|---|
| ELDER | 1 | 本人 |
| ASSESSOR | 2 | 本机构 |
| FAMILY | 1 | 本人（绑定对象） |
| ORG_ADMIN | 2 | 本机构 |
| SUPERVISOR | 4 | 只读全局 |

> ⚠ **`elder:archive:read` 五角色全授予，可见范围不靠权限点区分、由 `data_scope` 控制** —— 避免为「本人/绑定对象」再造
> 2 个权限码，与既定「不为数据范围分裂权限点」口径一致。正因权限点层面的 ✅ 不再具备限速含义，才必须补
> §5 的行为异常检测兜底批量拉档风险（CR-M2-001 §2.5）。

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

### 5.2 反向缺口：6 类权限点尚无归属接口（不阻塞 CR-M2-001，随 C/D 模块补登）

| 权限点 | 应补接口（建议） | 敏感 |
|---|---|:--:|
| `evaluation:order:void` | `POST /eval/orders/{orderId}/void` | **是** |
| `account:family:unbind` | `POST /family/unbind` | **是** |
| `account:family:bind` / `:reject` | `POST /family/bind`、`POST /family/bind/{id}/reject` | 否 |
| `care:plan:edit` / `:suggest` | `PUT /care/plans/{planId}` | 否 |
| `account:org:manage` / `:read` | `GET /account/orgs`、`GET /account/orgs/{orgId}` | 否 |
| `evaluation:order:review:read` | 已由 `POST /eval/orders/{orderId}/review` 只读态承载（无需新增路径） | 否 |

> 补登后契约由 **21 路径 / 22 操作 → 28 路径 / 29 操作**。

---

## 6 行为异常检测（CR-M2-001 §2.5 · PM 补充意见）

| 项 | 口径 | 落地位置 |
|---|---|---|
| 监控对象 | **非护理角色**：ELDER / FAMILY / SUPERVISOR（ASSESSOR / ORG_ADMIN 为护理相关角色，默认不纳入） | `yl.security.archive-access-guard.monitored-roles` |
| 触发条件 | 同一账号对档案类接口（`elder:archive:read`）**滑动 1 小时**内调用 **> N**，默认 **N=50** | `threshold` / `window-minutes` |
| 统计窗口 | **滑动 1 小时（非自然小时）**——Redis ZSET + Lua 原子「修剪—写入—计数」 | `RedisSlidingWindowCounter` |
| 处置动作 | 写 `audit_log`（`action=ARCHIVE_ACCESS_ALERT`）→ 通知监管角色，同窗口内去重 | `ArchiveAccessRateGuard` |
| 失败姿态 | **失败开放**：计数/去重/通知异常只记警告，绝不阻断档案读取 | `ArchiveAccessRateGuard` |

**未实现的选项（需明示）**：CR 把「降级为单次二次验证」列为**可选**动作。档案读取是非敏感权限点，强制二次验证会使客户端在无
`X-Second-Verify-Token` 时直接失败，属契约破坏性变更，超出本 CR 授权范围，**故本期不实现**；生产如需启用，应在 C/D 模块落地时一并变更契约。

---

## 7 对齐与校验

### 7.1 四处对齐点

| 位置 | 文件 | 现状 |
|---|---|---|
| 种子（权威） | `docker/mysql/init/03_seed_rbac.sql` §2 / §4 | 22 权限点 + 40 授权 |
| 代码常量 | `PermissionCode` / `SensitivePermissions` | 22 常量 + 4 敏感点 |
| 门禁断言 | `scripts/verify-schema.sh` | 权限点 = 22、授权 = 40、敏感点 = 4、五角色 = 5、矩阵红线 8 条 |
| 契约标注 | `openapi/yl-api.yaml` | 22 操作 100% `x-required-permission`，0 处 `pending-cr` |

### 7.2 自检 SQL（与种子 §5 一致）

```sql
SELECT (SELECT COUNT(*) FROM sys_role)                                    AS roles_expected_5,
       (SELECT COUNT(*) FROM sys_permission WHERE deleted = 0)            AS perms_expected_22,
       (SELECT COUNT(*) FROM sys_role_permission)                         AS grants_expected_40,
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
| — | 落地清单 #5（回写 PRD §2.2 + 修订记录） | ⏳ 待办（须 CCB 追认） | 产品 |
| — | 反向缺口 6 类补登契约 | ⏳ 待办（随 C/D 模块，不阻塞本 CR） | 研发 |
