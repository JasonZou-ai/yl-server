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
