# C4 报告域补齐设计：PDF 渲染 / 照护建议 / 复核发布状态机

- 日期：2026-09-23
- 范围：`yl-module-evaluation`（报告域）
- 依据：PRD §1.4（分级口径）、§4.3（异常分支）、§4.4（评估单状态机）、P02（报告详情页）、ADR-0006（PDF 渲染选型）
- 关联：C3 报告规则绑定（`docs/design/C3-report-rule-binding-DDL-Patch-Proposal.sql`）

## 1. 复核发布状态机（PRD §4.4）

状态：`DRAFT → SUBMITTED → PUBLISHED / REJECTED → VOID`（`ARCHIVED` 由保留期任务后续补）。

| 动作 | 迁移 | 前置校验 | 副作用 |
|---|---|---|---|
| `SUBMIT` | DRAFT / REJECTED → SUBMITTED | ER-06 必答完整性 | 调引擎判定、回写等级三要素、写日志 |
| `PUBLISH` | SUBMITTED → PUBLISHED | ① 复核人 ≠ 录入人；② 报告已生成 | 报告 `status=1`（有效）、写 `published_at`、写日志 |
| `REJECT` | SUBMITTED → REJECTED | ① 复核人 ≠ 录入人；② **意见必填** | **作废旧报告**（`deleted=1`，重提后重建）、写日志 |
| `VOID` | SUBMITTED / PUBLISHED / REJECTED → VOID | ① 复核人 ≠ 录入人；② 意见必填；③ 非 DRAFT | 报告 `status=2`（已作废）、写日志 |

三条硬约束（缺一即拒绝，单测与真实冒烟均覆盖）：

1. **录入与复核互斥**（PRD §2.2）：`operatorId == eval_order.assessor_id` 一律拒绝；
2. **驳回必须留意见**（PRD §4.3）：退回录入人须保留意见，否则无法据以修改重提；
3. **发布必须有报告**（流程 A ⑤⑥）：复核通过的对象是报告，报告未生成不得发布。

审计：每次流转写 `eval_review_log(action, from_status, to_status, operator_id, opinion)`。

## 2. 照护建议（PRD P02）

- 数据源：`care_template`（DDL 播种 19 条，覆盖 5 个等级），按 `grade_code` 精确匹配、`sort_no` 升序；
- **降级**：无匹配模板时输出 3 条通用建议（生活照料/防跌倒/健康随访），`source=GENERAL`——绝不返回空建议；
- 落点：`care_plan.items_json`（模板 + 个性化条目），与报告一对一（`uk_report` + `active_uk`）；
- **双人闭环**：`suggest_by`（评估员）→ `confirmed_by`（机构管理员），确认人必须 ≠ 建议人，`status` 0-待确认 / 1-已确认。

## 3. 报告 PDF（ADR-0006）

- 渲染：Apache PDFBox 2.0.30（Apache-2.0），矢量文本；内容依次为 标题 / 等级判定三要素 / 4 维度得分（含拒答数）/ 照护建议 / 验真信息（规则版本 + 规则指纹 + 内容摘要 + 验真链接）；
- 字体：`yl.report.pdf.font-path` 优先，其次系统候选；未命中则**降级渲染**（中文占位、`chineseEmbedded=false`），不阻断业务；
- 存储：MVP 本地目录 `yl.report.pdf.dir`，键名 `report/<reportNo>.pdf`（与 COS 形态一致）；下载接口对报告编号做 `[A-Za-z0-9_-]{1,64}` 白名单校验，防目录穿越；
- 回填：`eval_report.pdf_cos_key` / `qr_content`（验真二维码内容）。

## 4. 数据落点新增

| 表 | 用途 |
|---|---|
| `report_dimension_score` | 4 维度得分 + 拒答数（P02 雷达图数据源），满分取自规则集维度定义（合计 90） |
| `care_template` | 照护建议模板库（本次播种 19 条，覆盖 5 等级） |
| `care_plan` | 照护方案（条目 JSON + 建议人/确认人 + 状态） |

## 5. 接口清单

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/evaluation/assessments/{orderId}/report` | 生成报告（幂等）+ 落维度得分 |
| GET | `/api/v1/evaluation/assessments/{orderId}/report` | 报告详情（含维度得分/PDF 键/验真内容） |
| POST | `/api/v1/evaluation/assessments/{orderId}/report/pdf` | 生成 PDF 并回填键与二维码 |
| GET | `/api/v1/evaluation/reports/{reportNo}/pdf` | 下载 PDF |
| POST | `/api/v1/evaluation/assessments/{orderId}/care-plan` | 生成照护方案 |
| POST | `/api/v1/evaluation/assessments/{orderId}/care-plan/confirm` | 管理员确认 |
| GET | `/api/v1/evaluation/assessments/{orderId}/care-plan` | 查询照护方案 |
| POST | `/api/v1/evaluation/assessments/{orderId}/review/publish ｜ reject ｜ void` | 状态机三动作 |
| GET | `/api/v1/evaluation/assessments/{orderId}/review-logs` | 流转日志（审计链） |

## 6. 验证

- 门禁：`scripts/verify-schema.sh` 断言 69 → **71**（新增照护模板 5 等级覆盖 + 启用条目数），真实 MySQL **71/71**；
- 单测：37 例（新增状态机 9、照护 8、PDF 4），Checkstyle 0、SpotBugs 0；
- 冒烟：`scripts/smoke-report-review-chain.sh` 真实 HTTP **24/24**（含互斥、意见必填、驳回重提、作废、PDF 魔数与中文字体嵌入）。

## 7. 已知待办

1. 复核/发布尚未按 RBAC 权限码 `evaluation:order:review` 接入鉴权（当前由调用方传 `operatorId`），登录态就绪后需收紧为「从 token 取用户 + 校验角色」；
2. `ARCHIVED`（超保留期归档）未实现，待并入保留期调度任务（ADR-0004）；
3. 无字体环境（Linux 容器缺中文字体）下 PDF 降级渲染，需在镜像内内置字体并配置 `yl.report.pdf.font-path`。
