# D4-R7 开卡：ELDER 读本人评估报告（窄开）

> 卡编号 **D4-R7**｜开卡 2026-10-11｜来源：`BACKLOG-M2-P1P2.md` 残留表 L258（D-4 设计 §6.6 ★C3 登记）｜
> 裁决：**2026-10-11 PM 拍板「方案 A 窄开」**（附 R5 防欠权断言双做裁决）｜状态：**🔵 已开卡 · 待地基核查**

## 0. 裁决留痕（产品决策，PM 签发）

- **决定**：给 ELDER 开「读**本人已发布**评估报告」能力。
- **半径控制**：数据域反查锚（本人）+ 读路径状态闸（`status=1`）双保险；不开放评估域其余读面。
- **否决项**：方案 B（家属代读）——「银龄守护」典型用户含无家属绑定的独居老人，代读路线对他们等于无解；方案 C（暂缓留痕）——事实订正后残留理由不再成立。

## 1. 事实订正（2026-10-11 实核，推翻台账三条理由）

| 台账原记载（L258） | 实核结果 | 证据 |
|---|---|---|
| 「缺『评估-报告→老人本人』锚」 | **锚列存在**，只差登记反查锚，零 DDL | `02_schema.sql:477` `eval_report.elder_id BIGINT NOT NULL`；父表自见锚 `elder.user_id` D-2 已建（`02_schema.sql:202`） |
| 「修它 = 给老人开新读能力」 | **能力早已授予**，第二闸本来就通 | `03_seed_rbac.sql:70` `(3001, 1001, 2001)`，注释原文「行1 查看评估报告：老人…全部 ✅」；`report:view` 即 perm 2001（`:41`） |
| 「爆炸半径 = 评估域全部读端点对 ELDER 开放」 | **半径被高估** | `report:view` 契约挂载共 9 处（见 §3）；登记锚后可见行 = 本人（`elder.user_id = me`） |

死端点成因：`DataScopePermissionHandler.SELF_DENY_TABLES`（`yl-module-account/.../DataScopePermissionHandler.java:176`）对 scope=1 恒假 `(1=0)`。**迁移先例**：`elder`（D-2 加锚列迁出）、`elder_family_bind`（D-4 加 `SELF_VIA_PARENT` 迁出）——本卡走第三条同款路径。

## 2. 设计方向（契约先行，待地基核查确认）

1. **锚登记（零 DDL）**：`SELF_VIA_PARENT` 增加 `eval_report → RelationAnchor("elder_id", "elder")`——单跳：`eval_report.elder_id ∈ (SELECT id FROM elder WHERE user_id = 我)`。机制与 D-4 `elder_family_bind` 完全同款。
2. **★D1 决策点（地基核查项）**：`report_dimension_score`（经 `report_id → eval_report`）与 `eval_answer`（经 `order_id → eval_order`）是**两跳链**，而当前 `SELF_VIA_PARENT` 机制要求父表在 `SELF_COLUMN`（`eval_report` 无 `user_id` 列，不会进 `SELF_COLUMN`）。首增量按老人端真实读面定：
   - 老人端若只需 **PDF 下载 + 报告详情**（读 `eval_report` 单表）→ 单跳锚即可，两张子表维持 `SELF_DENY_TABLES`（fail-closed 不动）；
   - 若需维度得分/作答明细 → 须扩链式锚机制（新机制 = 新审查面，单独设计段 + 突变）。
3. **状态闸**：ELDER 读面只放 `status=1`（有效/已发布）；`0-生成中`、`2-已作废` 不外露。落服务层查询条件（数据域层不做状态语义）。
4. **契约**：相关端点补 `by-role: { ELDER: self, ... }` 标注（现有 `report:view` 端点多数已带 by-role，逐条核对补齐）。

## 3. 半径清单（`report:view` 契约挂载 9 处，ELDER 视角逐条分析）

| 端点 | ELDER 视角 |
|---|---|
| `reportGet`（报告详情） | **本卡目标**：本人报告（锚 + 状态闸） |
| `reportPdfGenerate` | 评估/发布动作语义，待设计段裁决是否对 ELDER 收紧（倾向：本卡不涉及，维持现状——ELDER 调用会因订单归属恒假自然被拒） |
| `reportPdfDownload` | **本卡目标**：本人已发布报告 PDF |
| `carePlanGet`（OR 语义） | 照护方案读，与 `eval_report` 无关，不受本卡影响 |
| `familyReportList` / `familyReportDetail` / `familyTrend` / `familyCarePlan` | 家属域 relationId 形态；ELDER 即使第二闸放行，`elder_family_bind` 在 scope=1 已自见锚（D-4），拼他人 relationId 落 `10004` |

**半径结论**：登记 `eval_report` 单跳锚后，ELDER 新增可见面 = 本人的 `eval_report` 行；其余 8 处挂载行为不变。

## 4. 验收标准（门禁基线只升不降）

1. **正向可达性断言（R5 防欠权实例）**：冒烟链 `smoke-report-review-chain.sh` 9.5 段翻转 ★C3 注释，新增「ELDER 读本人已发布报告 = 1」（登录 `elder_dev` → 本人 reportNo → `code=0`）。
2. **突变反证（QA 三连，逐条变红）**：
   - **M1** 撤 `SELF_VIA_PARENT` 登记 → ELDER 报告读取回红（恒假，证锚承重）；
   - **M2** 拼**他人** reportNo → `10004`（不泄露存在性，同码口径）；
   - **M3** `status=2` 作废报告本人读 → 不可读（证状态闸承重）。
3. **schema 门禁 86 不变**（零 DDL 是本卡硬约束，出新 DDL 即偏离设计须回裁决）。
4. 契约漂移 0（`verify-openapi.sh` 8/8）；单测/Checkstyle/SpotBugs 0 红。

## 5. 流程

地基核查（D1 链式锚机制能力 + 老人端真实读面盘点）→ 设计（契约先行）→ 裁决 → 串行实现 → 门禁三件套 → QA 突变。

## 6. 边界（本卡不碰）

- `supervise_report`：监管域报文，scope=1/5 维持显式禁（fail-closed）。
- `eval_evidence` / `consent_record` / `elder_authorization` / `org_bed` / `care_task`：老人无读面需求，维持 `SELF_DENY_TABLES`。
- 文件删除（`proof_cos_key` 生命周期）归 P2-7；PDF 渲染字体链已由 R15 收口。

## 7. 地基核查结果（2026-10-11，详 `docs/reviews/D4-R7-foundation-check.md`）

- **D1 落地口径锁定（PM 裁决 D1-a + D1-c）**：`eval_report` 走 `SELF_VIA_PARENT (elder_id, elder)`（与 `elder_family_bind` 同款，零 DDL）；`report_dimension_score` 保持 SELF_DENY、得分经报告聚合接口（D1-c）暴露；`eval_answer` **维持判死**（D3 主观作答不直陈本人）。
- **★ 同批核查两发现**：① `elder` 档案自见**已闭合**（D-2 `user_id` 锚 + 冒烟 9.5 实证），PM 疑虑解除；② **R8 新洞**：`care_plan` 双层缺口——第二闸 ELDER 不持 2007/2008（2008 仅家属），第三闸 SELF_COLUMN `suggest_by` 锚致老人可见行恒空（"建议"= 空按钮）；补遗「仅可建议」与 RBAC 现状不一致。**R8 处置待 PM 裁决**（补遗口径是否算数 + scope=1 主体集合澄清），可随本卡同批或紧后。
- **暗区盘点**：scope=1 未登记 11 表全为字典/规则表（gb_*、eval_item 等），无泄漏；测量时注意 SELF_DENY `Set.of` 串须并入"已登记"集，否则假报暗区。
