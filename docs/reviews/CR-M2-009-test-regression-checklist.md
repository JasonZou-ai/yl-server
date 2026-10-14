# CR-M2-009 测试回归清单（会签随行材料 · 测试侧同步用）

> 编制 2026-10-13｜用途：会签材料附件（上线口径「上线前必须人工回归」的执行依据）
> 范围隔离：**读路径修复（R7/R8/F2/M3）不受会签阻塞可先上线**；2008 写权限专项在会签通过后执行第二批次。

## 一、已覆盖（单元级，随 CI 38390087544 六作业全绿）

| 防护点 | 测试类.方法 | 断言内容 |
|---|---|---|
| M3 状态闸·本人已发布可读 | `AssessmentReportServiceTest.elderReadsOwnPublishedReport` | ELDER 读本人 status=1 报告 code=0 |
| M3 状态闸·生成中不可读 | `elderCannotReadGeneratingReport` | status≠1 → 拒绝 |
| M3 状态闸·作废不可读 | `elderCannotReadVoidReport` | status=2 → 拒绝（10004 口径） |
| M3 状态闸·反向边界 | `orgAdminStillReadsVoidReport` | 机构管理员读作废报告**正常**（防状态闸误改全局） |
| 2008 写权限·操作者闸 | `CarePlanServiceTest.generateRequiresOperator` 等 4 例 | 模板匹配/幂等/需报告/复核人不同 |
| 归属锚谓词 | `DataScopePermissionHandlerTest`（86fea0d +97 行） | eval_report/care_plan 锚的 scope=1 SQL 谓词 |
| E2E 冒烟 9.5b | `smoke-report-review-chain.sh` 5 条断言 | 本人已发布 code=0+status=1／他人 orderId→10004／作废→10004／夹具还原 |

## 二、上线前必测（2008 写权限第二批次，端侧实测）

| # | 用例 | 期望 |
|:--:|---|---|
| W1 | 老人给**本人**评估单提交照护建议 | code=0；建议落库 `elder_id`=本人 |
| W2 | 老人构造**他人** orderId 提交建议 | **10004**（第三闸收口：报告查不到即 NOT_FOUND，不泄露存在性）——★当前单元级**无此专项用例**，见第三节缺口 |
| W3 | 老人提交建议后，评估员/家属按既有端点可见 | 既有读面不回归 |
| W4 | 回归：机构管理员读 status=2 作废报告 | 正常（CR-M2-009 §七钉死项，单测已钉、要求端侧再测一遍） |

## 三、缺口登记（2026-10-13 晚已全部补齐）

1. ~~**W2 专项用例缺失**~~ ✅ **已补**：`CarePlanServiceTest.generateCrossElderOrderIsNotFoundAndWritesNothing`（commit 76640ed）——钉两条：① 跨 elder_id 收口必须为 `NOT_FOUND(10004)`（不泄露存在性）；② fail-closed 拒绝路径绝不落库。突变反证 ×1（NOT_FOUND→ROLE_NOT_PERMITTED 用例变红，已还原）。
2. ~~**冒烟 9.5b 未实跑校准**~~ ✅ **已实跑校准（2026-10-13）**：fresh jar 实测 **124/0/0**，与推算总数一致；D4-R7 卡 §10.5 提示已删。**首跑曾 122/2/0**——9.5b 夹具原稿假设「种子老人 id=$ELDER 存在」不成立（种子仅含账号、`elder` 表为空，直绑 `user_id` 匹配 0 行 → 假 10004）；夹具已订正为「报告 `elder_id` 临时改指 [7/7] 建档老人 E1（9.2 已绑定 elder_dev）+ 段末还原」，二跑 124/0/0。

## 四、会签侧结论（2026-10-15 会签通过后更新）

- 读侧：**已具备上线条件**（单测+冒烟双覆盖，CI 全绿，9.5b 已实跑校准）。
- 写侧：W2 专项已补（76640ed）+ 9.5b 校准完成（124/0/0，d943992），**两项测试前置全部闭环**。
- **会签结果（2026-10-15）**：五方通过（四端附条件）。2008 写权限启用前置仅剩 `rR1a6L` 第一批四端改造交付验收（schema 断言 41 已于 2026-10-14 实测通过）。上线前端侧人工回归仍按第二节 W1–W4 执行。
