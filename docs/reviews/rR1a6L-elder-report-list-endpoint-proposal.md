# 后端提案 ·「本人报告列表」端点（解锁 rR1a6L 端侧老人读面）

> 性质：**待授权提案**（只读分析 + 方案，未落地任何代码/契约/权限）
> 背景：`rR1a6L` 端侧改造已交付（2026-10-15），但端侧 `getMyReportsAsElder()` **只有显式抛错路径**——后端无「本人报告列表」端点，老人无法在自己的页面上拿到报告清单（知道 `orderId`/`reportNo` 才能读详情/PDF）。
> 关联：`rR1a6L-postchange-verification-2026-10-15.md` · `CR-M2-009-rR1a6L-acceptance-checklist.md` §六 S-5 · `BACKLOG-M2-P1P2.md` **B5-后端**
> ⚠ 本文为 AI 辅助推演，**不含任何已生效变更**；正式口径由 PM/研发/测试/合规复核。

---

## 一、只读实测（现状证据）

| 项 | 实测 | 结论 |
|---|---|---|
| 现有报告相关路径 | `/evaluation/assessments/{orderId}/report` · `/evaluation/assessments/{orderId}/report/pdf` · `/evaluation/reports/{reportNo}/pdf` · `/reports/verify`（public） · `/family/bindings/{relationId}/reports[_/{reportId}]` | **全部需要 `orderId` / `reportNo` / `relationId` 之一** |
| 是否存在「列表」端点 | `grep '^  /api/v1/.*report.*:'` ⇒ 无 `GET /evaluation/reports`、无 `/elders/{id}/reports` | **不存在 ⇒ 端侧无法自助获取清单** |
| 数据模型是否支持 | `EvalReport.elderId`（`EvalReport.java:30`）、`EvalOrder.elderId`、`EvalTask.elderId` 均在 | ✅ 支持按 elder 过滤 |
| 数据域机制是否已备 | `D4-R7` 已登记 **`SELF_VIA_PARENT`**（`eval_report.elder_id → elder.user_id`），D-2 已建 `elder.user_id` 锚列 | ✅ **零 DDL、零新机制** |
| 权限码是否已授 | 种子 `3001: ELDER → report:view`（第二闸本就通） | ✅ 无需新增授权 |
| 状态闸口径 | `AssessmentReportService#assertPublishedToSelf`：本人域仅 `status=1` 可见 | 列表必须**同口径**（只返回 `status=1`） |
| 契约规模 | `openapi/yl-api.yaml` v1.1.4 = **48 操作**（`verify-openapi.sh` 8/8，双向漂移 0） | 方案 A 会 **48 → 49** |

---

## 二、方案对比

| 方案 | 做法 | 优点 | 缺点 | 评估 |
|:--:|---|---|---|---|
| **A（推荐）** | 新增 **`GET /api/v1/evaluation/reports`**：登录态本人域自动过滤，仅返回 `status=1`，分页 | 端侧一次请求拿清单；复用既有锚与状态闸口径；**纯新增只读端点** | 契约 +1 操作、需登记 `CONTROLLERS` 两处硬编码、补测试与冒烟 | ✅ 推荐 |
| B | 复用 `/family/bindings/{relationId}/reports` | 零新端点 | 老人**没有 relationId**（绑定关系属家属侧），语义不符 | ❌ 不可行 |
| C | 维持现状（端侧显式抛错，老人只看到「暂不可用」） | 零改动、零风险 | **老人读面不完整**，C-1 无法真正验收；D-1 实机必失败 | ⚠ 仅作过渡 |

---

## 三、方案 A 细化（待授权后实施）

**契约**

```yaml
/api/v1/evaluation/reports:
  get:
    tags: [evaluation]
    operationId: reportList
    summary: 本人/本机构报告列表（老人自读仅返回已发布）
    x-required-permission: report:view
    x-data-scope: { mode: self, by-role: { ELDER: self, ASSESSOR: org, ORG_ADMIN: org, SUPERVISOR: all } }
    x-status: implemented
    parameters: [pageNum, pageSize]
    responses: { '200': { ... R<PageResult<ReportListItem>> } }
```

**实现要点**

1. 控制器落 `yl-module-evaluation`（与既有 `AssessmentController`/`ReportController` 同模块，**不跨模块**）。
2. 查询走 **Mapper + 第三闸自动追加数据域条件**（`eval_report` 已在 `SELF_VIA_PARENT`）⇒ **不得**手写绕过第三闸的直读（参照 `CR-M2-006` 教训）。
3. 过滤：`status = 1`（与 `assertPublishedToSelf` 同口径）+ `deleted = 0`；排序 `published_at DESC, id DESC`。
4. 响应**最小字段集**：`reportNo`/`elderId`/`publishedAt`/`gradeCode`/`finalLevelCode`/`totalScore`（**不含**任何敏感字段明文；维度分仍走详情聚合，不在此端点下发）。
5. 错误语义：沿用 **HTTP 200 + `R.code`**（0 成功）；无数据返空列表（**不泄露存在性**）。

**门禁与测试清单（实施后必跑）**

| # | 项 | 判据 |
|:--:|---|---|
| 1 | 契约登记两处硬编码 `CONTROLLERS` | `verify-openapi.sh` **8/8**、双向漂移 0、操作数 48 → **49** |
| 2 | 单测 | 新增用例：ELDER 只看到本人 + 仅 `status=1`；他人 `elder_id` ⇒ 空/`10004`；`status=2`（VOID）**不出现**；`ORG_ADMIN` 结果不受影响（**C-4 不回归**） |
| 3 | 覆盖守卫 | `ControllerAuthorizationCoverageTest` 通过（新端点必须带 `@RequiresPermission`） |
| 4 | 冒烟 | `smoke-report-review-chain.sh` 增 1~2 条断言（ELDER 列表 code=0 且行数=1、VOID 不出现） |
| 5 | schema | **不变**（86/0/0，零 DDL） |
| 6 | 静态 | Checkstyle 0 / SpotBugs 0 / `mvn -o verify` 基线只升不降（现 341/0） |
| 7 | 推送 | **远端 SHA 比对**（唯一判据）；CI 六作业 |

**回滚**：纯新增只读端点 ⇒ 回滚 = 撤控制器方法 + 契约回退 + 冒烟断言回退；**无 DDL、无数据变更、不改权限授予、不动状态闸** ⇒ 低风险可逆。

**风险与红线**

- 🔴 **不得**顺带放宽 `assertPublishedToSelf`（本人域状态闸）—— 列表与详情必须同口径；
- 🔴 **不得**为此端点新增权限码或改种子（`report:view` 已授 ELDER；改授权属另一类高风险变更）；
- 🟠 新增 `x-data-scope` 必须与实现一致（`verify-openapi.sh` 查路径集，**不查 `enum` 取值** ⇒ 需人工逐值对照）。

---

## 四、真机验收执行单（D-1 / D-2 / D-3，供验收人使用）

> 前置：① 方案 A 落地并推送（否则 D-1 必失败）② 后端运行且 `report:view` 对 ELDER 生效 ③ 三端安装包（`_改造-rR1a6L` 交付态）④ 测试账号（纯 ELDER / 混合角色 / 未知角色）

| 项 | 步骤 | 判据（**须留证据**） |
|:--:|---|---|
| **D-1 纯老人账号** | 以纯 ELDER 登录 → 冷启动落地页 → 看报告列表与详情 | 落地 **老人首页**（非评估员工作台）；列表**只见本人已发布**；**无**评估员 tab / 待评估列表 / 全量档案；详情维度分来自聚合视图（抓包**不得**出现子表接口） |
| **D-2 混合角色账号** | 单账号持 ELDER + ASSESSOR（或 +ORG_ADMIN）→ 切换/重登 | 角色切换后读面随**最宽**角色收敛（后端 `max()` 口径）；**无跨角色残留缓存**（登出清理双令牌） |
| **D-3 未知/异常角色** | 构造后端返回未识别或空角色（如 `ORG_ADMIN` 直登端侧 / 空值） | **必须落到登录页**，不得落到任何业务页（端侧断言已过；此处验实机） |

**证据要求**：截图 + 抓包/日志（含 `R.code`）；每项独立登记；未通过项单独复验。
**解锁条件**：D-1~D-3 全绿 + C-1~C-3 产物级复核 ⇒ 解除四端附条件 ⇒ 2008 写权限**方可**进入开启评估（**仍需另一次显式决定**）。

---

## 五、待授权 / 待拍板

| # | 事项 | 类别 | 状态 |
|:--:|---|---|:--:|
| 1 | **方案 A**：新增 `GET /api/v1/evaluation/reports`（含契约/权限/数据域登记与门禁全套） | 🔴 后端数据域 + 契约（高风险） | ✅ **已实施（2026-10-15）**，见 §六 |
| 2 | 一并补 `/family/bindings*` 的 `by-role` 标 `ELDER` | 🔴 后端数据域 | ✅ **已实施（4 个端点；3 个未补并逐条给理由）**，见 §六 |
| 3 | D-1~D-3 真机验收排期 | 🟡 协调 | ⏸ 需真实设备与账号（执行单见 §四；后端阻塞项已解除） |
| 4 | 回滚脚本模板（D）/ Secret 轮换（E） | 🔴 / 🟠 | ✅ 已交付（模板 + 轮换步骤；**均不入库**） |
| 5 | 会签栏签署日（10-15）晚于执行日（10-14）口径 | 🟠 PM/CCB | ⏸ 待拍板 |
| 6 | CR-M2-008 遗留「并入后升 v1.3」vs 本次同版不升版 | 🟠 PM/CCB | ⏸ 待拍板 |

---

## 六、实施结果与 Lead 独立复核（2026-10-15）

> 授权：用户 2026-10-15 明确授权 **A + B**（多选确认）。实施方：后端子代理；**复核方：Lead（全部亲自复算）**。

### 6.1 交付内容

| 项 | 内容 |
|---|---|
| 契约 | `openapi/yl-api.yaml` 新增 `GET /api/v1/evaluation/reports`（`operationId: reportList`、`report:view`、`x-data-scope.mode: self` + `by-role` 五角色 + `via`、`x-status: implemented`）+ schema `ReportListItem`（**恰 6 字段**，无敏感明文、无维度分）。**操作数 48 → 49** |
| 实现 | `ReportListController.java`（`@RequiresPermission(REPORT_VIEW)`）+ `AssessmentReportService#listReports`：**只经 Mapper 走第三闸**（服务层零归属条件）、`status=1 && deleted=0`、`published_at DESC, id DESC`、分页缺省 1/20 上限 100、无数据 → **空列表 + code 0**（不泄露存在性） |
| B | 4 个端点补 `by-role`：`/{id}/reject`(2014 仅 ELDER)、`/{relationId}/reports`、`/reports/{reportId}`、`/trend`；**未补 3 个**：`GET /bindings`(2013 老人不持 ⇒ 稳定 20003，补即过度承诺)、`/approve`(2009 机构独占)、`/unbind`(老人两码均不持 + 写端点) |
| 测试 | 新增 `ReportListControllerTest`(2)、`ReportListSecondGateTest`(3)、`AssessmentReportServiceTest` 增 6 例、`DataScopePermissionHandlerTest#elderRoleListsOnlyOwnBindings`；覆盖 4 类负例（他人 elder → 空、VOID 不出现、缺 `report:view` → **20003**、ORG_ADMIN 不受影响） |
| 冒烟 | `smoke-report-review-chain.sh` 增 6 条 rR1a6L 断言 |

### 6.2 Lead 独立复核（不看自报）

| # | 项 | 我的方法 | 结果 |
|:--:|---|---|:--:|
| 1 | 权威门禁 | **我亲自** `mvn -o -B verify` | ✅ **BUILD SUCCESS**，聚合 **366 / 0 / 0 （Skipped 1）**，Checkstyle 0、SpotBugs 0 |
| 2 | 契约门禁 | **我亲自** `bash scripts/verify-openapi.sh` | ✅ **8/8**，契约操作 **49**（implemented 49 / planned 0），双向漂移 0 |
| 3 | 契约测试 | 我亲自 `mvn -pl yl-bootstrap -am test -Dtest=OpenApiContractTest,ControllerAuthorizationCoverageTest` | ✅ 6/0 与 2/0，BUILD SUCCESS |
| 4 | **端到端冒烟** | **我亲自把 8080 切到新 jar**（旧 jar 备份 `%TEMP%\d4-run\yl-bootstrap-OLD-20261013.jar`）后在 8080 上跑冒烟 | ✅ **通过 127 / 失败 0 / 跳过 1，exit=0**；6 条新断言全绿；夹具自清理（剩余业务数据 0 行） |
| 5 | 未认证面 | `curl` 未带令牌直连新端点 | ✅ **HTTP 401 + code=10002**（**绝非 200 放行**） |
| 6 | 实现是否绕闸 | 读 `listReports` 与控制器源码 | ✅ 服务层**无** `elder_id`/`org_id`/`assessor_id` 条件；仅 `reportMapper` 走第三闸 |
| 7 | 服务可用性 | 重启后健康检查 | ✅ `status=UP`、`reportFont=ready`（约 12s 起） |

### 6.3 Lead 的两处修正 / 教训

1. **契约保真度（我改了）**：新端点 `by-role` 原**漏列 FAMILY**。契约头约定 `by-role` 列各角色**实际落点**（`GET /elders`/`reportPdfGenerate` 均列全五角色），而 FAMILY 持 `report:view`、第三闸落 `relation` ⇒ **已补 `FAMILY: relation`** 并在 `extra` 注明「家属读面推荐仍走 `/family/bindings/{relationId}/reports`」。
2. **★ 假红教训（我踩的）**：`mvn -pl yl-bootstrap test`（**不带 `-am`**）会从 m2 取**旧**的 `yl-module-evaluation` 构件 ⇒ `ClassNotFoundException: ReportListController`，看起来像「契约测试挂了」。带 `-am` 即绿。**判据：凡跨模块测试必须 `-am`；见到 NoClassDefFound 先怀疑构件来源，不要怀疑代码。**
3. **基线口径订正**：`mvn verify` 基线今日实测为 **356/0/0(+1)**（不是文档旧记的 341/0）；本次改动后 **366/0/0(+1)** —— 「只升不降」按今日实测判定。

### 6.4 仍未做（诚实登记）

- **真机 D-1~D-3** 未跑（无设备/账号）；
- 本端点**突变反证**（如把 `status=1` 改坏应否变红）未跑；
- 后端已可供端侧 `getMyReportsAsElder()` 消费，但**端侧尚未改为消费该端点**（端侧当前仍走显式抛错路径；改一行即可接通，属端侧任务）。
