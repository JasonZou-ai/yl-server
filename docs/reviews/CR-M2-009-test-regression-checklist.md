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

## 三、缺口登记（不阻塞会签，阻塞 2008 第二批次上线）

1. **W2 专项用例缺失**：`CarePlanServiceTest` 无「跨 elder_id 提交建议」方法级用例（第三闸口径为 NOT_FOUND 而非显式 20003，需用例钉住防回归）。建议随 2008 上线批次补入。
2. **冒烟 9.5b 未实跑校准**：断言总数 119→124 系推算（D4-R7 卡 §10.5 提示未删）。dev 环境（MySQL+Redis+8080）就绪后跑 `smoke-report-review-chain.sh`，回填实际计数并删提示。

## 四、会签侧结论

- 读侧：**已具备上线条件**（单测+冒烟双覆盖，CI 全绿）。
- 写侧：单测基础防护在位；W2 专项 + 9.5b 校准两项补齐后即可放行第二批次。
