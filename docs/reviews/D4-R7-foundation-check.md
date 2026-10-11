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
