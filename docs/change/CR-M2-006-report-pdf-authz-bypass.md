# CR-M2-006 报告 PDF 下载越权（`ReportPdfService.read` 绕过数据域）

| 项 | 内容 |
|---|---|
| 编号 | **CR-M2-006** |
| 类型 | **变更 / 安全缺陷修复（P0）** |
| 优先级 | **P0 —— 安全项，已按建议插队至 D-2 之前闭环** |
| 状态 | **✅ 已修复并实证（2026-09-24）** |
| 登记日期 | 2026-09-23 ｜ 修复日期 2026-09-24 |
| 发现来源 | **D-1 契约盘点（副产品）** —— 盘点「实现有、契约缺」的 C 类端点时核对鉴权链发现 |
| 关联 | CR-M2-002（同类：基建已备、路径未接）、`ReportPdfService.read()`、`ReportController` |

---

## 一、现象（一句话）

**任何已登录用户（含家属、老人、其他机构评估员）只要知道报告编号，即可下载任意报告的完整 PDF** —— 内含姓名、能力等级、分项得分与照护建议（个人健康信息）。

---

## 二、证据链（2026-09-23 实测，三重）

### 2.1 代码事实：`read()` 零 DB 访问

```java
// yl-modules/yl-module-evaluation/.../service/ReportPdfService.java:125-139
public byte[] read(String reportNo) {
    if (reportNo == null || !reportNo.matches("[A-Za-z0-9_-]{1,64}")) {
        throw new BizException(ErrorCode.PARAM_INVALID, "报告编号非法: " + reportNo);
    }
    Path file = resolve("report/" + reportNo + ".pdf");   // ← 直接拼路径
    if (!Files.isRegularFile(file)) { ... }
    return Files.readAllBytes(file);                       // ← 直接读文件，不经 Mapper
}
```

唯一防护是**报告编号格式白名单**（防目录穿越，有效）。**无任何归属、机构、状态校验**。

`ReportController.download` 亦**无 `@RequiresPermission`**（第二闸不生效）：

```java
@RestController
@RequestMapping("/api/v1/evaluation/reports")
public class ReportController {
    @GetMapping("/{reportNo}/pdf")     // ← 无 @RequiresPermission
    public ResponseEntity<byte[]> download(@PathVariable String reportNo) { ... }
}
```

**关键推论**：第三闸 `DataPermissionInterceptor` 是 **MyBatis 拦截器**（只改写 SQL）。`read()` 不产生 SQL → **第三闸完全无法介入**。

### 2.2 实测 A —— 下载「DB 中不存在」的文件（返回 200）

```
$ mysql -e "SELECT COUNT(*) FROM eval_report WHERE report_no='RP0e549ea6-26f'"
  0                                     ← DB 记录数 0（库重建后的孤儿文件）

$ curl -H "Authorization: Bearer <org_id=1 评估员令牌>" \
       http://127.0.0.1:8080/api/v1/evaluation/reports/RP0e549ea6-26f/pdf
  -> HTTP 200 | 28788 字节 | 魔数='%PDF-'     ← 拿到完整 PDF
```

### 2.3 实测 B —— **跨机构**下载（返回 200）+ 同资源对照（被拦）

把报告 `RPe243182b-71f` 改挂 `org_id=999`（模拟"他机构"），请求者仍在 `org_id=1`：

| 路径 | 是否经 DB | 结果 |
|---|:--:|---|
| `GET /api/v1/evaluation/reports/RPe243182b-71f/pdf`（**本缺陷**） | ❌ | **HTTP 200**，20445 字节，`%PDF-` |
| `GET /api/v1/evaluation/assessments/{orderId}/report`（**对照**） | ✅ | `{"code":10004,"message":"报告尚未生成"}` ← **第三闸生效、记录被过滤** |
| `GET .../reports/RPe243182b-71f/pdf`（无令牌） | — | HTTP 403 ← 第一闸正常 |

> **对照实验的意义**：**同一个资源、同一个令牌**，经 DB 的路径被数据域拦掉，走文件的路径完整拿到。
> → 缺陷**精确定位在 `read()` 绕过数据域**，而非数据域配置错误。这排除了"基建没配好"的解释。

---

## 三、影响面

| 维度 | 影响 |
|---|---|
| **越权类型** | **横向越权（跨机构）+ 绕过数据域**；且不要求持有任何权限码（仅需认证） |
| **泄露内容** | 报告 PDF 全文：老人姓名、能力等级、4 维度分项得分、照护建议 |
| **触发门槛** | `reportNo` 形如 `RPe243182b-71f`（约 44 bit）。**不是密码级强度**；且编号出现在报告的**二维码内容**里（`qrBaseUrl?no=<reportNo>&hash=...`），任何一次分享图片即等于公开编号；无速率限制可遍历 |
| **同类端点** | 另有 **3 个端点无第二闸**（`GET /evaluation/items`、`POST /evaluation/grade`、`GET /evaluation/rule-meta`）。均为低敏静态数据（题库/规则元信息），**风险低**，但违反"接口须挂权限码"的一致口径，建议同批补 |
| **是否已在生产中** | 是（随 C4 报告域上线） |
| **是否被端侧依赖** | 是 —— 四端「下载 PDF」按钮走此端点，**修复须保持路径与响应不变** |

---

## 四、根因

**与 CR-M2-002 同源**：*"基建已备、某条路径没接上"*。

| 闸 | 应有 | 实际 |
|---|---|---|
| 第一闸 认证 | 生效 | ✅ 生效（403 实测） |
| 第二闸 授权 | `report:view` | ❌ **无 `@RequiresPermission`** |
| 第三闸 数据域 | 按 `org_id` / 关系域过滤 | ❌ **`read()` 无 SQL → 拦截器无法介入** |
| 第四闸 二次验证 | 不适用（非敏感码） | — |

**为什么此前没暴露**：
1. CR-M2-002 收口时只重写了 `AssessmentController`，**未巡检其余 controller**（`ReportController` / `EvaluationItemController` / `GradingController` 均无注解）；
2. 单元测试 Mock 掉 `read()` 或只验文件读写，**不验归属**；
3. `smoke-report-review-chain.sh` 的 32 项断言覆盖状态机，**不覆盖"跨机构下文件"**；
4. 库重建后遗留的孤儿 PDF 反而"看起来正常"（能下 = 以为功能正常），掩盖了"不校验 DB"这一事实。

---

## 五、修复方案（建议）

### 5.1 必做（最小改动，~10 行）

```java
// ① ReportPdfService.read()：先经 DB 反查，让第三闸生效
public byte[] read(String reportNo) {
    if (reportNo == null || !reportNo.matches("[A-Za-z0-9_-]{1,64}")) { ... }
    // 新增：按 report_no 查 eval_report —— 该查询经 MyBatis 拦截器，第三闸自动按 org_id 过滤
    EvalReport report = reportMapper.selectOne(
            Wrappers.<EvalReport>lambdaQuery().eq(EvalReport::getReportNo, reportNo));
    if (report == null) {
        throw new BizException(ErrorCode.NOT_FOUND, "报告不存在");   // 与"不存在"同码（R.code=10004），不泄露存在性
    }
    ...再读文件
}

// ② ReportController.download：补第二闸
@RequiresPermission(PermissionCode.REPORT_VIEW)
@GetMapping("/{reportNo}/pdf")
public ResponseEntity<byte[]> download(@PathVariable String reportNo) { ... }
```

> **注意**：`report:view` 五角色全授，**第二闸本身不构成隔离**；真正的隔离来自 ① 让第三闸介入。①是主修复，②是一致性补齐。

### 5.2 建议同批

| # | 动作 |
|---|---|
| 1 | 补 `EvaluationItemController`（`GET /items` → `EVALUATION_TASK_READ` OR `EVALUATION_ITEM_INPUT`）、`GradingController`（`POST /grade` → `EVALUATION_ITEM_INPUT`；`GET /rule-meta` → `RULE_VIEW`）的第二闸 |
| 2 | 孤儿 PDF 清理纳入保留期链路（`export_task.expire_at` 已有同类机制可参照）；`eval_report.deleted=1` / 库重建时应联动清理文件 |
| 3 | 冒烟脚本新增断言：**跨机构 reportNo 下载必须被拒**（回归防线） |
| 4 | 巡检**全仓** `@*Mapping` 方法是否都有 `@RequiresPermission`（防止再出现"裸奔端点"），建议做成门禁测试 |

### 5.3 验收

> ⚠ 下表为**提案期**写的判定口径，其中"404"的写法已被 §8.4 更正为 `R.code=10004`（HTTP 仍 200）。保留原文以留痕。

| # | 验收项 | 判定 |
|---|---|---|
| 1 | 跨机构下载 | `org_id=1` 令牌读 `org_id=999` 报告 → **被拒**（同一业务码，不泄露存在性） |
| 2 | DB 不存在的 reportNo | **被拒**且不返回文件字节（不再泄露 `%PDF-`） |
| 3 | 本机构正常下载 | 200 + `%PDF-`，字节数与修复前一致（**端侧零改动**） |
| 4 | 无令牌 | 403（保持） |
| 5 | 单测 | `read()` 在归属校验失败时抛错；新增跨机构用例 |
| 6 | 门禁 | 「所有端点均有 `@RequiresPermission`」断言通过 |

---

## 六、与 D 系列的关系

- **不属 D-1**（D-1 只产出契约差异清单）；
- **建议插队**：修复约 10 行 + 3 处注解，成本远低于 D-2，且属安全项 —— 按既定原则（安全主线第一优先）应优先闭环；
- **与 D-2 的耦合点**：D-2 落地 `elder` 档案域后，"关系级数据域"（D-1 待裁决 2）将使本修复自动覆盖家属场景（家属经关系域校验）。**建议 ① 先修本卡（用机构域即可验证），② D-2 落地关系域后无需再改本处代码。**

---

## 七、结论

| 项 | 结论 |
|---|---|
| 是否真实缺陷 | **是**，可复现、跨机构、绕过数据域 |
| 严重度 | **P0**（泄露个人健康信息；无权限码要求，仅需认证） |
| 是否阻塞 D | **不阻塞 D-1**；已按建议**插队至 D-2 之前**闭环 |
| 登记义务 | 已登记本卡；D-1 清单 §五 同步记载 |

---

## 八、实施记录（2026-09-24）

### 8.1 代码改动（4 个文件）

| # | 文件 | 改动 |
|---|---|---|
| 1 | `ReportPdfService.read()` | **主修复**：新增 `requireVisible(reportNo)` —— 按 `report_no` 经 `EvalReportMapper` 反查一次，跨机构/跨关系的行被行级条件过滤 → 统一抛 `NOT_FOUND`（与"不存在"同码，不泄露存在性）；随后才读文件 |
| 2 | `ReportController.download` | 补 `@RequiresPermission(PermissionCode.REPORT_VIEW)`（第二闸；该码五角色全授，隔离仍由第三闸承担） |
| 3 | `EvaluationItemController.items` | 补 `@RequiresPermission({EVAL_TASK_READ, EVAL_ITEM_INPUT}, OR)` |
| 4 | `GradingController.grade` / `ruleMeta` | 补 `@RequiresPermission(EVAL_ITEM_INPUT)` / `@RequiresPermission(RULE_VIEW)` |

### 8.2 防回归（三层，均已落地）

| 层 | 载体 | 断言 |
|---|---|---|
| 单元 | `ReportPdfServiceTest.readFailsClosedWhenReportNotVisible` | **文件真实存在**但反查返回 `null`（模拟被第三闸过滤）→ 必须抛 `NOT_FOUND`；钉死"先查库"这一步 |
| 结构 | `ControllerAuthorizationCoverageTest`（新增） | 反射遍历全部 6 个控制器：**每个处理端点必须声明 `@RequiresPermission`**，白名单仅 4 个会话入口/探针且**禁止冗余条目**（注解补齐后忘摘白名单同样变红） |
| HTTP | `smoke-report-review-chain.sh`（+5 断言） | ① 无归属 `reportNo` → 落 `R.code=10004`（修复前孤儿文件返 200 + `%PDF-`）；② 报告改挂 `org_id=999` 后 `org_id=1` 令牌 → 落 `R.code=10004`，且同资源 DB 路径对照被拦；③ 两处均需**证明未泄露文件字节**（响应体非 PDF 魔数）；④ **还原归属后必须仍拿到 `%PDF`**（证明拒绝来自数据域，而非把功能改坏） |

> **口径更正（见 §8.4）**：上述断言最初写成「期望 HTTP 404」，与实现不符 —— 本服务业务异常一律 HTTP 200 + 响应体 `code`，
> 故首轮跑出 **2 条假红**，且「还原后 200」那条是**假绿**（成功/失败同为 200，无区分力）。已改为按 `R.code` + 字节层判定。

### 8.3 验收对照（§5.3 六项）

> 判定列按实际口径给出（本服务业务失败=HTTP 200 + `R.code`，详见 §8.4）。

| # | 验收项 | 判定 | 结果 |
|---|---|---|---|
| 1 | 跨机构下载 | `org_id=1` 读 `org_id=999` → 被拒 | ✅ `R.code=10004`（HTTP 200）+ 响应体非 PDF 魔数（冒烟实测） |
| 2 | DB 不存在的 reportNo | 被拒且不返回文件 | ✅ `R.code=10004` + 未泄露 PDF 字节 |
| 3 | 本机构正常下载 | `%PDF-`，字节数与修复前一致 | ✅ 28789 字节 / `%PDF`（还原归属后断言） |
| 4 | 无令牌 | 被第一闸拦 | ✅ HTTP **403**（实测；与契约声明一致，见 §8.4 发现二） |
| 5 | 单测 | 归属校验失败抛 `NOT_FOUND`；文件真实存在 | ✅ `readFailsClosedWhenReportNotVisible` |
| 6 | 门禁 | 全端点均有 `@RequiresPermission` | ✅（结构测试 + 白名单自检） |

> **残留（顺带记录，不阻塞）**：孤儿 PDF 文件仍未纳入保留期清理（§5.2 第 2 条）；已登记入 D-5 的归档链路待评估。
> **契约侧同步**：本端点在 `openapi/yl-api.yaml` v1.1.1 中标注 `x-status: implemented` +
> `x-data-scope.mode: relation` + `extra: 先查库后读文件（否则第三闸在文件路径上失效）`，并由 `scripts/verify-openapi.sh` 的漂移断言守住。

### 8.4 验收期两个口径更正（2026-09-24 复验）

首轮复验时冒烟报 **34 通过 / 2 失败**，我先按「新 jar 未生效」怀疑进程占用（已 `taskkill` 旧 PID 26956 并重建），
但复跑仍有失败，于是**直读运行实例日志 + 直测端点**定位，结论不是越权回退，而是**我的断言写错了层级**：

**更正一：业务失败是 HTTP 200，不是 404（假红 + 假绿）**

- 事实：`GlobalExceptionHandler#handleBiz` **没有 `@ResponseStatus`** → 业务异常一律 200，业务码在响应体里。
  全项目只有参数校验（400）、未预期异常（500）改状态码。日志实证：
  `WARN ... uri=/api/v1/evaluation/reports/RP-CR-M2-006-ORPHAN/pdf code=10004 msg=报告不存在或无权访问`，
  同一请求 HTTP 状态码 **200**。
- 后果：按 HTTP 状态码断言 → ①拒绝也被读成 200 → **假红**；②「还原后可下载（HTTP 200）」→ 成功与失败同为 200，
  **该断言恒真、零区分力**（假绿，比假红更危险）。
- 处置：断言下沉到**业务码 + 字节层** —— 拒绝须 `R.code=10004` **且**响应体非 `%PDF`（证明没退化成"返回了文件"）；
  放行须响应体以 `%PDF` 开头。契约同步勘误（v1.1.1）：该端点 responses 由误写的 `'404'` 改为 `200/401/403` 并写明判 `code` 口径；
  `info.description`「统一响应」一节写死该规则。

**更正二（新发现，另立卡）：未携带凭据返回 403 而非 401**

- `JwtAuthFilter` 只对**凭据无效/过期**显式置 401（源码 L173-177 `SC_UNAUTHORIZED` + `R.fail(UNAUTHORIZED)`）；
  **完全不带** `Authorization` 头时该过滤器放行，由 Spring Security `anyRequest().authenticated()` 拒绝 →
  落到默认 `Http403ForbiddenEntryPoint` → **403**（实测确认）。
- 但 `SecurityConfig` 类注释承诺「未认证统一返回 401 + 统一响应体」，且**未配置 `exceptionHandling()` 入口点**。
- 影响：同一语义（"你没登录"）分叉成 403/401，客户端若按 403 判"无权限"会给出错误提示。
- 处置：**不在本卡夹带**（属第一闸行为，改动面覆盖全站），已登记 **CR-M2-007（P2）**，建议随 D-2 一并处理。
