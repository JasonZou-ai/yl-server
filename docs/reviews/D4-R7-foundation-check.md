# D4-R7 地基核查报告（v1.0）

> 编号 **YL-D4-R7-FC**｜2026-10-11｜核查人：研发侧｜状态：**✅ 完成**
> 输入：PM 裁决（方案 A 窄开 + D1-a/D1-c 组合 + `eval_answer` 维持判死 + `elder`/`care_plan` 同批核查）
> 证据：`DataScopePermissionHandler.java`（L140-205）· `03_seed_rbac.sql`（L41-119）· `02_schema.sql`（care_plan :535）· `AssessmentController.java`（:219-250）

## 1. 结论先行：R7 不是"一个洞"，是「一个洞 + 一个新洞 + 一个已闭合 + 一个维持判死」

| # | 表 | 产品意图 | 第二闸（持码） | 第三闸（scope=1 数据域） | 判定 |
|---|---|---|---|---|---|
| 1 | `eval_report` | 报告等级结论，必读 | ELDER 持 2001 `report:view` ✅ | **SELF_DENY_TABLES** → 恒假 | **R7 实锤**（直锚 `elder_id` 可修） |
| 2 | `report_dimension_score` | 四维度得分，必读 | 2001 ✅ | **SELF_DENY_TABLES** → 恒假 | **R7 实锤**（链式两跳，须走 D1-c） |
| 3 | `eval_answer` | 过程性作答，**不开放** | — | SELF_DENY → 恒假 | **维持判死 ✅**（PM 裁决，含 D3 主观描述防直陈） |
| 4 | `elder`（档案） | 档案自见 | 2019 ✅ | SELF_COLUMN `user_id`（D-2 已建） | **已闭合，非洞**（冒烟 9.5 实证 code=0）——PM 疑虑解除 |
| 5 | `care_plan` | 🔶 仅可建议 | **ELDER 不持 2007/2008**（2008 仅家属 1002）；GET 端点含 2001 可过 | SELF_COLUMN `suggest_by` → 老人行**恒空** | **R8 新洞，双层缺口**（见 §3） |

## 2. scope=1「未登记放行」暗区盘点（全库 38 表求差）

未登记于任何锚/禁表集合的 11 张：`care_template`、`eval_item`、`eval_item_option`、`gb_dimension`、`gb_grade_threshold`、`gb_rule`、`gb_rule_mapping`、`sys_permission`、`sys_role`、`sys_role_permission`、`track_event_dict`。

**全部为字典/全局规则表**（支撑 2022 `rule:view` 等），符合「论证为字典表」豁免口径，**无泄漏洞**。★ 修正一次测量误差：SELF_DENY 是 `Set.of` 字符串非 `Map.entry`，差集脚本须把两者都算"已登记"，否则 `consent_record`/`report_dimension_score` 会假报为暗区。

## 3. R8（care_plan）双层缺口——需 PM 补一次裁决

- **第二闸**：补遗写「老人 🔶 仅可建议」，但种子 `(3014, 1002, 2008)` —— `care:plan:edit:suggest` **只授了家属**，ELDER 零照护码 ⇒ 建议端点 `POST /{orderId}/care-plan`（:219，OR{2007,2008}）对老人 20003。**补遗口径与 RBAC 现状不一致**（超前或缺授，二选一）。
- **第三闸**：`care_plan` 在 SELF_COLUMN 按 `suggest_by`（评估员建议人）登记——老人从不建议 ⇒ 可见行恒空；GET（:239，OR 含 2001）老人能过第二闸但**查不到任何方案** ⇒ 即便未来授码，"建议"仍是空按钮。
- **表形状**：`care_plan.elder_id BIGINT NOT NULL` + `uk_report(report_id)`——**有直锚**，且与 `eval_report` 经 `report_id` 关联。
- **修法候补（不拍板，留设计轮）**：读路径 = `care_plan` 迁 SELF_VIA_PARENT（`elder_id→elder.user_id`）或并入 D1-c 聚合读面；写路径 = 若 PM 确认"老人可建议"，给 ELDER 授 2008（**须同步核对 scope=1 主体集合是否仅 ELDER**——SELF_COLUMN 里 `eval_order.assessor_id` 等登记暗示 scope=1 可能含员工态，改锚前必须澄清，否则爆炸半径失控）。

## 4. D1 裁决落地口径（D1-a + D1-c 组合，与 PM 意向一致）

1. **D1-a 主修**：`SELF_VIA_PARENT` 登记 `eval_report → (elder_id, elder)`——与 D-4 `elder_family_bind` 完全同款（父表 `elder` 已在 SELF_COLUMN），零 DDL，schema 门禁保持 86。
2. **D1-c 配套**：`report_dimension_score` 保持 SELF_DENY，"每题/每维得分"经**报告聚合接口**暴露结构化结论（题号+得分+维度），表不单独开——天然把 `eval_answer` 关在门外。
3. **越权回归必须带**：M1 撤锚回红 / M2 他人 reportNo → 10004（同码不泄露存在性）/ M3 作废（非 `status=1`）报告不可读；正向新增「ELDER 本人已发布报告可读 = 1」（R5 防欠权首例入卡验收）。

## 5. 遗留与移交

- R8 裁决两问（§3）待 PM：**补遗口径是否算数** + **老人建议是否真开**。建议随 D4-R7 实现同批或紧后，避免修完 R7 再开 R8 的往返。
- scope=1 主体集合澄清（是否含员工态）是 R8 改锚的前置，也是全仓数据域的一处既有模糊点，建议单独立一条核查项。
- 端侧老人端读面盘点（报告页实际调哪些端点）随实现轮做，本报告只管后端门禁面。

---

## 6. 二轮复核增补（2026-10-11，产品侧独立复核）

> 复核依据：`DataScopePermissionHandler.java` L128-146（`SELF_COLUMN`）/ L176-188（`SELF_DENY_TABLES`）/ L195-220（`RELATION_*`）· `openapi/yl-api.yaml` L624/654/696/864/1099/1139/1181 · `EvalReportResponse` · `AssessmentController.java:139-142`
> 本节不改动 §1-§5 任何结论，仅增补与订正。

### 6.1 根因的一般性结论：**ELDER 是唯一的「归属对象型」角色**

`SELF_COLUMN` 登记的"本人列"**全部是操作者语义**：`eval_order→assessor_id`（L138）、`eval_task→assessor_id`（L139）、`care_plan→suggest_by`（L141）、`export_task→operator_id`（L137）。其隐含假设是「账号 = 这一行的操作者」——对 ASSESSOR / ORG_ADMIN / SUPERVISOR 成立，**唯独 ELDER 不成立**：老人不操作这些行，他是这些行的**主体**（`elder_id`）。

对照 scope=5 关系级：其锚**全部按 `elder_id` 归属登记**（`RELATION_ELDER_COLUMN` L195-204），是正确的。**即同一张表两套语义，ELDER 落在错的那套上。**

⇒ 这解释了为什么 R7（`eval_report`）、R8（`care_plan`）与 §6.2 的 `eval_order` 是**同一类缺陷的三个实例**，而非三个独立问题。

### 6.2 增补一个新洞：**`eval_order`（契约 `ELDER: self` 未兑现）**

| 项 | 内容 |
|---|---|
| 证据 | `SELF_COLUMN` 登记 `eval_order → assessor_id`（L138）；契约 `openapi/yl-api.yaml:864` `assessmentGet` 声明 `ELDER: self` |
| 实况 | 老人查自己评估单被过滤为 `assessor_id = me` ⇒ **恒空** |
| 产品价值 | 这是评估主链上老人**唯一的进度入口**（"我的评估到哪一步了"） |

**★修法警告（撤回一轮稿的直改建议）**：**不得**直接把 `eval_order` 的 `SELF_COLUMN` 锚由 `assessor_id` 改为 `elder_id` —— 与 §3 末尾的警告同源：scope=1 的"本人"对员工角色是**作为操作者的自己**，改锚会让评估员看不到自己承接的单。修复须走**角色分叉**或新增 `SELF_VIA_PARENT`（而非改 `SELF_COLUMN`），且以 §5「scope=1 主体集合澄清」为前置。

### 6.3 支撑 D1-c 的必要实证：**只开 `eval_report` 单跳锚，报告是残缺的**

`EvalReportResponse` 含 **`dimScores`**（类注释原文"为雷达图数据源"），端点 `AssessmentController:139-142 getReport → reportService.getReport(orderId)`；该字段来自 `report_dimension_score`（仍在 `SELF_DENY_TABLES`）。

⇒ 若仅按 D1-a 开 `eval_report` 单跳锚，老人拿到的报告 **`dimScores` 恒空 = 雷达图空白**（P02 报告详情页核心展示，PRD §6）。**这是 D1-c 必须配套、不可省略的实证依据**，与 §1 第 2 行"四维度得分，必读"相互印证。

### 6.4 契约侧读面盘点（`ELDER: self` 共 7 处）

| 契约行 | 端点 | 表 | 可达性 |
|:--:|---|---|---|
| 654 | `elderList` | `elder` | ✅ 可达（`user_id`，D-2 已建） |
| 696 | `elderDetail` | `elder` | ✅ 可达 |
| **864** | **`assessmentGet`** | `eval_order` | ❌ **恒空**（§6.2） |
| **1099** | **`reportGet`** | `eval_report` + `report_dimension_score` | ❌ **恒假**（R7 本体） |
| **1139** | **`reportPdfDownload`** | `eval_report` | ❌ **恒假**（R7 本体） |
| **1181** | **`carePlanGet`** | `care_plan` | ⚠️ **恒空**（R8 第三闸） |
| 624 | `elderCreate` | `elder`（写） | 写路径；补遗 §2 老人为 ❌，契约 `ELDER: self` 语义待产品确认 |

> 端侧实际调用面仍按 §5 留实现轮；本表为**契约承诺 vs 门禁实况**的对照，7 处中 4 处不可达/失真。

### 6.5 订正

一轮稿（产品侧独立稿）曾建议"`eval_order` 锚改 `elder_id`"与"扩 `SELF_VIA_PARENT` 链式覆盖 `report_dimension_score`"——前者**按 §6.2 撤回**（员工态冲突），后者**让位于 D1-c 聚合读面口径**（本报告的 D1-a + D1-c 组合已覆盖其目标，且审查面更小）。其余结论（R7 定性 A、零 DDL、`eval_answer` 维持判死）与本报告一致。

---

## 7. 三轮复核增补（2026-10-11，产品侧）：数据域「纯老人通道」实证

> 复核依据：`AccountAggregateService.java` L44-49（`dataScope` 聚合）· `docker/mysql/init/03_seed_rbac.sql` L28-79（角色 id 与授权）·
> `DataScopePermissionHandler.java` L110-146（`ORG_COLUMN` / `SELF_COLUMN`）· `LoginUser.java` L18-22（`roles` 载荷）
>
> 本节**订正** v1.0 §3 与 v1.1 §6.2 中的两处事实表述（结论方向不变，但**理由必须换**——否则 PM 依据错误理由裁决）。

### 7.1 ★事实订正①：2008 的归属方是「评估员」，不是「家属」

| 项 | v1.0 §3 / 开卡文档原表述 | 实证（`03_seed_rbac.sql`） |
|---|---|---|
| 角色 id | 未核对，按「1002=家属」表述 | **1001 ELDER / 1002 ASSESSOR / 1003 FAMILY / 1004 ORG_ADMIN / 1005 SUPERVISOR** |
| `2007 care:plan:edit` | — | 仅 ORG_ADMIN（`(3013, 1004, 2007)`） |
| `2008 care:plan:edit:suggest` | 「**仅家属 1002**」❌ | **仅 ASSESSOR（`(3014, 1002, 2008)`）**；家属（1003）**不持任何 care 码** |

**结论方向不变**：ELDER（1001）零 care 权限码 ⇒ 写端点 `AssessmentController:219 POST /{orderId}/care-plan`（OR{2007,2008}）对老人 403。
**但表述必须改**：不是"建议权被家属独占"，而是"建议权给了评估员，补遗却说给老人"——即**补遗与种子的分歧对象从「家属 vs 老人」变成「评估员 vs 老人」**，PM 裁决 A 方案的语义也随之变为「让老人与评估员并列持有受限建议权」。

另需 PM 注意：读端点 `GET /{orderId}/care-plan`（`AssessmentController:239`）是 **OR{2001,2007,2008}**，老人持 2001 ⇒ **第二闸能过**，缺口只在第三闸（`SELF_COLUMN: care_plan→suggest_by` 恒空）。
⇒ **R8 的第二闸与第三闸必须同批修**：只补授 2008 会得到"老人能提交建议、但提交后自己读不到"，属半成品。

### 7.2 ★事实订正②：`dataScope` 取 max ⇒ scope=1 ⟺ 纯 ELDER 单角色账号

`AccountAggregateService.loadProfile` L44-49：

```java
int dataScope = roles.stream().map(RoleCode::of).mapToInt(RoleCode::dataScope).max()
        .orElse(RoleCode.ELDER.dataScope());
```

配合 `RoleCode` 的 scope（ELDER=1 / ASSESSOR=2 / ORG_ADMIN=2 / SUPERVISOR=4 / FAMILY=5）得两条不变式：

- **不变式 I**：账号只要**还持任一员工角色**，聚合值即被抬到 2/4/5 ⇒ **永远不落 scope=1**。
- **不变式 II**：落 scope=1 的账号**只能是「仅持 ELDER」的纯老人账号**（含 ELDER+FAMILY 的组合会落到 5-关系级，走 `RELATION_*` 而非 `SELF_*`）。

⇒ **本节直接回答 §5 悬置项「scope=1 主体集合是否含员工态」：不含。** `SELF_COLUMN` / `SELF_VIA_PARENT` / `SELF_DENY_TABLES` 三个集合是**纯老人专用通道**，与员工态无交集。

### 7.3 推论：`SELF_COLUMN` 中的「业务操作者列」全是死登记

`ORG_COLUMN`（L110-124）与 `SELF_COLUMN`（L128-146）交叉核对后：

| 表 | `ORG_COLUMN`（scope=2 生效） | `SELF_COLUMN`（scope=1 生效） | 员工实际走哪条 |
|---|---|---|---|
| `eval_order` | `org_id` | `assessor_id` | **ORG**（评估员不经过 SELF） |
| `eval_task` | `org_id` | `assessor_id` | **ORG** |
| `care_plan` | 无（表无 org_id 列） | `suggest_by` | 未登记 ⇒ 放行（D-4 已知） |
| `export_task` | `org_id` | `operator_id` | **ORG** |
| `eval_review_log` / `care_task_log` | 未登记 | `operator_id` | 放行（员工） |

⇒ 这几条"业务操作者"登记**从未对任何员工生效过一次有效过滤**（员工不落 scope=1），**对老人则一律恒空**（老人不是操作者）。它们是"按列名直觉登记"的产物，属**死登记**：既造成欠权（老人读不到），也从未提供过任何能力。

**这条推论把 R7 / R8 / F2 从"三个洞"进一步收敛为"一处登记口径错误"**：`SELF_COLUMN` 把「操作者」语义错配给了唯一一个「归属对象」语义的角色。

### 7.4 由此订正 v1.0 §3 与 v1.1 §6.2 的谨慎理由（方向不变，理由替换）

| | 原理由（错误） | 订正后理由（成立） |
|---|---|---|
| v1.0 §3「改锚前须澄清 scope=1 是否含员工态，否则爆炸半径失控」 | 隐含"可能含员工态，改锚会破坏评估员自见" | **不含员工态（§7.2 不变式 I）** ⇒ 改锚**不会**破坏评估员（评估员走 ORG 分支）。**真正的风险是另一条**：`max` 聚合是**实现细节而非契约**——无单测锁定"多角色取 max"，若日后改为 `min` 或引入单角色员工态，员工会掉进 scope=1，届时 `elder_id` 锚会让员工恒空（fail-closed，静默） |
| v1.1 §6.2「撤回直改 `eval_order` 锚，须走角色分叉或 `SELF_VIA_PARENT`」 | 员工态冲突 | 员工态冲突**不成立**；但**撤回结论仍然保留**，理由换成：① `max` 语义未锁定（前置不变式单测）；② 角色分叉在 scope==1 分支内是**恒真分支**（能落 scope=1 者必为 ELDER），等于没分叉——故分叉应加在**入口**（按角色选锚集合），而非分支内 |
| 「评估员自见」能力 | 假定由 `SELF_COLUMN.assessor_id` 提供 | **从来没有实现过**：评估员 scope=2 走 `ORG_COLUMN.org_id` ⇒ 看到的是**本机构全量单**，不是"我的单"。功能上够用（机构 ⊇ 本人），但契约若声明 `ASSESSOR: self` 则也未兑现，属既有失真（不阻塞本卡，登记即可） |

### 7.5 代价与影响面对照（供 PM 裁决）

**R8（`care_plan`）**

| 选项 | 改动 | 前置 | 风险 |
|---|---|---|---|
| **A 补授**：老人可建议 | ① `03_seed_rbac.sql` 加 `(3041, 1001, 2008)`；② B2 目录 L46 + 权限矩阵行 5 补「老人 🔶」；③ **必须同批**修第三闸（`care_plan` 在 scope=1 下按 `elder_id` 收口），否则"能提交、看不到" | 属权限矩阵变更 ⇒ 须走 CCB；想搭 10-17 这波须在 10-17 前提出，否则另起一轮 | 写路径放开：老人可对**自己**的单提建议，`suggest_by` 由登录态填充，须确认校验（老人只能对自己的单提） |
| **B 收回**：老人不可建议 | 补遗删「老人 🔶 仅可建议」描述，1 处文档 | 无 | 读路径仍需修（否则老人看自己的照护方案是空的）⇒ **B 不等于"R8 不用改"，只是把写路径关掉** |

**F2（`eval_order`，含 `eval_task`）**

| 选项 | 改动 | 风险 |
|---|---|---|
| **① `SELF_VIA_PARENT` 登记** `eval_order→(elder_id, elder)`、`eval_task→(elder_id, elder)` | 2 行 Map + 单测；零 DDL（父表 `elder` 已在 `SELF_COLUMN`） | 依赖 §7.2 不变式；建议先加不变式单测钉死 `max` 语义 |
| **② 入口角色分叉**（新增 `ELDER_SELF_COLUMN`，ELDER 优先命中归属列，员工仍走原操作者列） | +1 Map + 分支数行 + 单测 | 与 ① 在当前语义下**等价**（员工不落 scope=1），但**未来员工态落入 scope=1 时行为正确**，是防御性版本 |
| ~~③ 直改 `SELF_COLUMN` 为 `elder_id`~~ | 最小 | **仍不推荐**：改动把一个"当前死、未来活"的锚直接钉成归属语义，且失去回退信息；② 的成本只多几行 |

**范围**

| 选项 | 说明 |
|---|---|
| 窄开（仅 R7 本体） | 只动 `eval_report` 单表锚；R8/F2 **必须立刻新开卡登记**（否则欠权继续静默），且 R8 的 A 方案会错过 10-17 窗口 |
| 同批（R7 + R8 读路径 + F2） | 一次改锚口径，审查面 = `SELF_COLUMN` 全部业务操作者条目；R8 写路径（A/B）仍须 PM 先定 |

### 7.6 建议（产品侧，供裁决参考）

1. **R8 建议走 A + 同批改第三闸**，并**搭 10-17 这波 CCB**；错过则 R8 写路径独立排队，读路径不阻塞、可随 R7 同批。
2. **F2 建议走 ② 入口角色分叉**（与 ① 成本接近、未来语义正确），前置加不变式单测：`ELDER 单角色 ⇒ scope=1`、`ELDER+任一员工角色 ⇒ scope != 1`。
3. **范围建议同批**：R7/R8 读路径/F2 是同一处登记口径错误的三个实例，分三批改会重复走三遍门禁回归。
4. R7 本体定性（A · 实现缺陷）不受 CR-M2-001 追认阻塞，**可立即开工**——与范围裁决解耦。

### 7.7 遗留

- 不变式单测（`max` 聚合）是本轮新增的前置项，需随实现轮落地。
- `ASSESSOR: self` 契约失真（§7.4）登记为既有问题，不在本卡半径内，建议入台账。
- 端侧老人端读面盘点仍按 §5 留实现轮。
