# D4-R7 开卡：ELDER 读本人评估报告（窄开）

> 卡编号 **D4-R7**｜开卡 2026-10-11｜来源：`BACKLOG-M2-P1P2.md` 残留表 L258（D-4 设计 §6.6 ★C3 登记）｜
> 裁决：**2026-10-11 PM 拍板「方案 A 窄开」**（附 R5 防欠权断言双做裁决）｜状态：**✅ 已实施（2026-10-12，R7+R8+F2 同批，见 §9.2）· 2008 授权 CR-M2-009 ✅ 会签通过（2026-10-15，四端附条件）**——启用仅剩前置：`rR1a6L` 四端第一批改造验收（schema 断言 41 已实测通过 2026-10-14）

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

## 8. 事实订正（2026-10-11 三轮复核，原文保留不改，以本节为准）

> 上节「同批核查两发现」中关于 R8 的表述有一处**已被实证推翻**；同时本卡原列的前置项「scope=1 主体集合澄清」**已获结论**。两者均影响 PM 裁决依据，故登记于此。
> 完整实证：`docs/reviews/D4-R7-foundation-check.md` §7（v1.2）。

| 项 | 本卡原表述 | 订正后 |
|---|---|---|
| `2008 care:plan:edit:suggest` 持有方 | 「2008 仅家属」 | **持有方是评估员（ASSESSOR，role_id 1002）**；家属（1003）**不持任何 care 权限码**。补遗与种子的分歧对象为「**评估员 vs 老人**」，不是「家属 vs 老人」 |
| R8 缺口位置 | 「双层缺口」 | 补充精确口径：读端点 `GET /{orderId}/care-plan` 为 OR{2001,2007,2008}，老人持 2001 ⇒ **第二闸可过**，缺口**仅在第三闸**（`care_plan→suggest_by` 恒空）。⇒ 若 PM 选「给老人授权」，**第二闸与第三闸必须同批修**，否则得到"能提交建议、自己读不到"的半成品 |
| 前置项「scope=1 主体集合澄清」（原列为本卡前置） | 待澄清，疑含员工态 | **已澄清：不含员工态**。`AccountAggregateService` 取多角色**最宽值（max）**，账号持任一员工角色即被抬到 2/4/5，**永不落 scope=1**；落 scope=1 者必为**纯 ELDER 单角色账号** |
| 由此派生的谨慎理由 | 「改锚会破坏评估员 scope=1 操作者视图」 | **不成立**：员工永不落 scope=1，改锚对员工零影响。**新前置**改为：先加不变式单测锁定 max 语义（`ELDER 单角色 ⇒ scope=1`；`ELDER+任一员工角色 ⇒ scope≠1`）——该行为目前是实现细节、无契约保护 |

---

## 9. PM 裁决登记与实施状态（2026-10-11 裁决，2026-10-12 实施）

> 本节只登记**裁决结果**与**落地位置**，不改 §0-§8 任何原文。
> §8 的三条事实订正已生效 —— 裁决是在订正后的口径上作出的，读本节前须先读 §8。

### 9.1 五条裁决（PM 签发，红线解除）

| # | 议题 | PM 裁决 | 依据 |
|:--:|---|---|---|
| 1 | **R8 写路径口径** | **选项 A —— 补授**：给 ELDER 补授 `care:plan:edit:suggest`(2008) | §8 订正①（2008 属评估员非家属）+ 补遗「老人 🔶 仅可建议」 |
| 2 | **F2 `eval_order` 修复路径** | **方案 A —— 角色分叉**（非直改 `SELF_COLUMN`） | §8 订正④（谨慎理由换代为「max 语义未锁定」）|
| 3 | **修复范围** | **同批**：R7 本体 + R8 读 + F2 三者一起 | §7.1 / §6.2 根因同一性（同一类缺陷的三个实例）|
| 4 | **PRD 治理 G1** | **① 移植横幅至 v1.2** | `docs/prd/README.md` §3 G1 |
| 5 | **R7 本体可否开工** | **知悉确认，不受 CCB 阻塞** | R7 本体属缺陷修复，非权限矩阵变更 ⇒ 无需 CCB |

> ⚠️ 裁决 1（2008 补授）属**权限矩阵变更**，仍须随 CR-M2-001 走 CCB 追认；裁决 5 仅解除 R7 本体的开工阻塞，
> 二者不可混为一谈 —— **R7/R8/F2 的代码与第三闸可即时落地，但 2008 授权是 CCB 待追认项**（限 **2026-10-17**）。
> 〔2026-10-16 订正：该**追认环节已办结**，见紧邻下段；2008 仍因 `rR1a6L` 验收未出具而**未启用**，两件事不可混同〕
>
> ✅ **2026-10-14 状态更新**：2008 授权（`care:plan:edit:suggest` → ELDER，授权 40→41）**已随 CR-M2-009 五方会签追认通过**，且 CR-M2-001 回写稿已于同日实质并入 PRD v1.2 §2.2。**但 2008 写权限仍不得启用** —— 会签为「四端附条件同意」：**第一批四端改造（`rR1a6L`）交付验收完成前禁止启用**。故本卡关闭条件中的「CCB 追认落地」已满足，剩余仅 `rR1a6L` 验收。

### 9.2 落地位置（2026-10-12）

| 裁决 | 落地文件 | 改动 |
|---|---|---|
| ③ R7 本体 | `DataScopePermissionHandler` | `eval_report` 登记入 `SELF_VIA_PARENT`（锚 `elder_id→elder.user_id`）；单测改**正向可达断言**（原测试把「欠权」固化为正确行为，已订正） |
| ③ R8 读 | `DataScopePermissionHandler` | `care_plan` **自 `SELF_COLUMN` 迁出**（原锚 `suggest_by` 对老人恒空）→ 登记入 `SELF_VIA_PARENT`（锚 `elder_id`） |
| ② F2 角色分叉 | `DataScopePermissionHandler` | 新增 `ELDER_SELF_ANCHOR`（`eval_order` / `eval_task` 锚 `elder_id`）；`getSqlSegment` 中**先于** `SELF_COLUMN` 命中 —— 员工角色仍走操作者锚 `assessor_id`，双方互不干扰 |
| ② 不变式锁定 | `AccountAggregateServiceTest`（新建，6 例） | 锁定「多角色 `dataScope` 取 **max**」语义：纯 ELDER ⇒ 1；ELDER+任一员工角色 ⇒ ≠1；员工单角色永不落 1 |
| ① R8 写补授 | `03_seed_rbac.sql` | 新增 `(3041, 1001, 2008)`；授权总数 **40 → 41** |
| ① 四处同步 | `verify-schema.sh` / `B2-permission-point-catalog.md` / `B2-account-rbac-design.md` / `B2-permission-api-matrix.md` / `openapi/yl-api.yaml` | 授权断言 40→41；清单 L46、矩阵行 5 补「老人 🔶」；契约 care-plan POST 补 `ELDER: self` + `via: care_plan.elder_id` |
| ④ G1 横幅 | `docs/prd/PRD_YL-PRD-v1.2.md` + `README.md` + **CR-M2-008** | 文首移植三段横幅 + §1.3 追加「治理追加 · **不升版**」行；v1.2 md5 更新为 `3c416935916e31e06c3f91d3bf26f076` |

### 9.3 ★ 推翻初判：R8 写路径**不需**补服务层归属校验

初判（§7 / 征询稿口径）认为须在 `CarePlanService.generate` 补归属校验。**2026-10-12 实证推翻**：

```
CarePlanService.generate(orderId, operatorId)
  └─ reportService.findReport(orderId)
       └─ reportMapper.selectOne(QueryWrapper<EvalReport>().eq("order_id", orderId))
            └─ ★ 受第三闸 DataScopePermissionHandler 收口
                 （YlApplication @SpringBootApplication(scanBasePackages="cn.yl") 全局生效，
                   MybatisPlusConfig 注册 DataPermissionInterceptor）
```

⇒ 老人传**非本人** `orderId` 时报告查不到 → `NOT_FOUND`，**写越权边界已由第三闸保证**，补冗余校验属重复实现。

**但这构成一个强耦合约束**：该写授权 (`3041`) 与第三闸 `eval_report` 锚**必须同批变更、禁止单独回滚任一处**。
（若锚登记回退而授权保留 ⇒ 老人可给他人写建议 = 写越权。）此约束已写入 `B2-permission-point-catalog.md` §3 表下。

### 9.4 回归结果（2026-10-12）

| 作业 | 结果 |
|---|---|
| 全模块单测 | ✅ **311 例全绿**（account 92 / evaluation 74 / archive 90 / supervise 10 / report 22 / bootstrap 23） |
| Checkstyle | ✅ 通过 |
| 契约门禁 `verify-openapi.sh` | ✅ 通过 8 项 / 失败 0 项 |
| Java 权威契约测试 `OpenApiContractTest` | ✅ 6/6 |
| schema 门禁 `verify-schema.sh` | ⏳ **须连 MySQL，沙箱无 DB，待本地/CI 复核**（授权断言已同步为 41） |

> **环境噪声说明**：首次全量跑时 evaluation 报 4 例 `ReportPdfServiceTest` 失败（`UnsupportedOperation OTF fonts do not have a glyf table`）。
> 经**基线工作树（HEAD 干净代码）对照验证，失败完全一致** ⇒ 属沙箱缺中文字体的**既有环境缺陷**，与本次改动无关；
> 装 `fonts-wqy-zenhei` 后该模块 74/74 全过（测试注释亦载明 CI runner 预装该字体）。

### 9.5 开裂/关闭边界

- **本卡关闭条件**：上述六项作业全绿 + CCB 追认落地（`2026-10-17` 前）。**〔2026-10-14 更新〕**：**CCB 追认已落地**（CR-M2-009 五方会签通过 + 回写并入 §2.2）⇒ 关闭条件中的追认项**已满足**；**2008 写权限的启用仍受 `rR1a6L`（四端第一批改造验收）附条件约束**，与本卡关闭属两件事。
- **不开裂**：`care_plan` **确认**端点（须 ≠ 建议人）不在本卡范围；`report_dimension_score` 仍判死，走 D1-c 聚合接口暴露。

---

## 10. M3 状态闸补落地（2026-10-13 —— 验收 §4.2 中唯一未闭合项）

> §9 的六项作业全绿**不等于本卡验收闭合**：0005 只落了「锚」（第三闸），**状态闸未落**。本节登记补做内容。

### 10.1 缺口实证

`AssessmentReportService.getReport`（2026-10-12 落地版）无任何 `status` 过滤 ⇒ 老人可读到**本人** `status=0`（生成中）与 `status=2`（已作废）报告，直接违反 §2.3「ELDER 读面只放 status=1」。

### 10.2 落地位置

| 位置 | 改动 |
|---|---|
| `yl-api` `LoginUser#isSelfScope()` | 新增 `dataScope == 1` 判定。**为什么不判 `hasAnyRole("ELDER")`**：角色码常量在 account 模块，业务模块引用会形成跨模块依赖；且 §8 已实证「落 scope=1 者必为**纯 ELDER 单角色账号**」（多角色取 max，员工永不落 1），该不变式由 `AccountAggregateServiceTest` 锁定 ⇒ `dataScope==1` 与「老人自读」等价且有契约保护 |
| `AssessmentReportService#assertPublishedToSelf(EvalReport)` | 新增 public 方法，`getReport` 调用：本人域 + `status != 1` ⇒ `BizException(NOT_FOUND)` |
| `ReportPdfService#requireVisible` | 复用同一方法（报告详情与 PDF 下载**必须同口径**，否则「详情 10004、PDF 能下载」即为绕过） |

**为什么落在服务层**：状态是**业务语义**而非数据域语义——第三闸只表达「这行是不是我的」，无权表达「已作废的不给看」。故 §2.3 决议「锚（数据域）+ 状态闸（服务层）双保险」在此落实。

**错误码取 10004 而非 10003**：与「他人 reportNo」同码，避免用错误码差异枚举出「存在但未发布」这一事实（§4.2·M2 同码口径）。

### 10.3 ★ 反向边界：只对本人域生效

`orgAdminStillReadsVoidReport` 单测钉死「机构管理员仍可读 `status=2` 报告」——复核/作废链路依赖该读面。
状态闸若写成无条件拒绝，该例立即变红：那是把「收紧老人」误做成「收紧所有人」，属**另一方向的欠权**。

### 10.4 突变反证（QA 三连之 M3）

| 突变 | 结果 |
|---|---|
| `if (report.getStatus() != REPORT_VALID)` → `if (false)` | **正好 2 条变红**：`elderCannotReadGeneratingReport` / `elderCannotReadVoidReport`；正向可达（`elderReadsOwnPublishedReport`）与防过度收紧（`orgAdminStillReadsVoidReport`）**不受影响** ⇒ 闸承重，非"加了但没人验证" |

突变后已还原（源文件 `grep -c MUTATION` = 0）。

### 10.5 E2E 冒烟覆盖翻转（★C3 声明）

`scripts/smoke-report-review-chain.sh`：

- **删除**原声明「★C3：ELDER E2E 不覆盖报告读取（D4-R7 恒假）」——修复后该前提不再成立。
- **新增 9.5b 段 5 条断言**：本人已发布报告 `code=0` + `status=1` ／ 他人或不存在 `orderId` → `10004` ／ 本人已作废报告 → `10004`（状态闸）／ 夹具还原。
- **夹具（2026-10-13 实跑订正）**：原稿「把种子老人临时绑为 elder_dev 本人」**前提不成立**——种子仅含账号不含老人档案，dev 库 `elder` 表为空、种子老人 id 行不存在，直绑 `user_id` 匹配 0 行 → 锚子查询恒空 → 假 `10004`（SMOKE-1013 首跑实证 122/2/0，两条红正是本节断言）。已改为把报告 `elder_id` **临时改指 [7/7] 建档老人 E1**（其 `user_id` 已在 9.2 经服务端绑定为 `elder_dev` 本人），段末按**原值**还原（`eval_report.status` 与 `elder_id`）——不还原会污染下一次运行（SMOKE-R1 已实证的假红来源）。

> ✅ **已实跑校准（2026-10-13）**：dev 环境（MySQL 8.0.46 + Redis + 8080，fresh jar）实测 **124/0/0**，与推算总数一致；D4-R7 四条断言全绿，夹具还原判据 `elder_id=1`、`status=1`。日志：`/tmp/d4-run/smoke-1013-v2.log`。

---

## 11. ★ 风险备忘：CR-M2-009 追认单的不对称回滚高危约束（2026-10-13 登记）

> 来源：CR-M2-009 会签推进方案（P0-3 风险标记）｜完整口径：`docs/change/CR-M2-009-ccb-ratification-2008-prd-writeback.md` §二/§六

- **CR-M2-009 为追认类 CR**：代码/种子已落地入库，会签只补合规留痕，不改变实现。
- **高危组合禁令（任何上线/回滚操作必须遵守）**：`2008 授权 (3041,1001,2008)` 与 `eval_report` 归属锚**强耦合**——
  - 删授权、保留锚 ✅ 安全（老人只是不能提建议）；
  - **回退锚、保留授权 ❌ = 写越权漏洞**（老人可给他人报告提交照护建议）。
- ⇒ **禁止单独回退 `eval_report` 归属锚**；任何涉及锚的回退必须先删授权。预置回滚脚本：`scripts/rollback-cr-m2-009-2008.sh`（默认 dry-run，`EXECUTE=1` 才落动作）。
- **上线隔离**：本卡全部读路径修复（R7/R8/F2/M3 状态闸）**不受 CR-M2-009 会签阻塞，可先行上线**；2008 写权限会签已通过（2026-10-15，四端附条件），启用前仅剩 `rR1a6L` 四端改造验收一项前置。
