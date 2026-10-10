# D-3 报表与监管域 · 端到端验证清单（E2E Checklist）

> 角色：工程师（寇豆码）｜依据：`docs/design/D3-report-supervise-domain-design.md`、`docs/reviews/D3-foundation-check.md`
> 范围：`yl-server` 报表与监管域 **7 端点**（B6/B7/B8/B9/B10 + R6×2），跨 4 模块（evaluation / account / report / supervise）。

---

## 0. 判定口径（务必先读）

- 本项目业务失败一律 **HTTP 200 + 响应体 `R.code`**（`GlobalExceptionHandler#handleBiz` 无 `@ResponseStatus`）。
  **拒绝的证据是 `R.code != 0`，绝不以 HTTP 200 当"放行"证据（恒真、假绿）**。
- 唯一例外：**未携带凭据**属框架层（Spring Security 过滤器链），CR-M2-007 修复后为
  **HTTP 401 + `R.code = 10002`**（须同时校验状态码与业务码）。
- 二进制/文件产物（B9 CSV）额外验 **文件魔数**（首 3 字节 `EF BB BF`）+ **单元格注入转义**，以证明"拒绝/转义没有退化为返回原始字节"。
- `@Valid` 参数校验在方法调用前触发（早于 `PermissionAspect` 的 `@Before`），故 B9 缺 `format/exportType` 命中 `10001` 而非 `10008`。

---

## 1. 环境前置

| 项 | 值 / 命令 |
|---|---|
| MySQL 8 | `C:\mysql`（不在 PATH）：`MYSQL_BIN=/c/mysql/bin/mysql.exe`，root 口令 `Mysql8@2026#Dev` |
| Redis | `C:\redis`，6379 无密码（启动时须 `REDIS_PASSWORD=''`） |
| 敏感字段密钥 | Base64 且 ≥32 字节，启动时注入（B9 CSV 姓名/手机解密→脱敏正常路径必需） |
| 二次验证口令 | `SECOND_VERIFY_DEV_CODE`（B9 `data:export` 敏感点必需） |
| 导出落盘目录 | `EXPORT_DIR`（= 后端 `yl.export.dir`），供 B9 魔数/表头/注入断言读取 |
| dev 账号 | `assessor_dev`（ASSESSOR）/ `orgadmin_dev`（ORG_ADMIN）；口令由环境变量 **`DEV_LOGIN_PASSWORD`** 注入（**不落任何已跟踪文件**） |

启动后端（逐字照抄，三个坑必须绕开）：

```sh
# ① 必须先 unset 宿主注入的 SERVER__PORT（会覆盖 server.port → "Port ... already in use"）
# ② REDIS_PASSWORD='' （本机 Redis 无密码；yml 默认会 AUTH 失败）
unset SERVER__PORT
export DB_PASSWORD='Mysql8@2026#Dev' REDIS_PASSWORD='' SECOND_VERIFY_DEV_CODE=246810 \
       SENSITIVE_FIELD_KEY="$(head -c 32 /dev/urandom | base64 -w0)" \
       SENSITIVE_FIELD_HASH_KEY="$(head -c 32 /dev/urandom | base64 -w0)" \
       EXPORT_DIR="$PWD/target/smoke-export"
java -cp yl-bootstrap/target/yl-bootstrap.jar \
     org.springframework.boot.loader.launch.JarLauncher \
     --spring.profiles.active=dev --server.port=8080
# ③ 复验前确认新代码真在跑：netstat -ano | grep :8080 → taskkill //F //PID <pid> → 重建 → 起新实例 → 看日志时间戳
```

> 注：本机 sandbox 会静默丢弃 `env -u ...` 形式的命令，故用 `unset` + `export` 替代。

---

## 2. 逐端点验收

### 2.1 B6 评估任务列表 `GET /api/v1/evaluation/tasks`（`evaluation:task:read`）

| 步骤 | 期望 | 判定 |
|---|---|---|
| 评估员令牌 | `code=0`，仅见本机构行（第三闸 `eval_task.org_id IN (orgIds)`） | ✅ 冒烟已覆盖 |
| 查询语句 | 显式含 `deleted = 0`（`eval_task` 有软删列，须显式追加） | ✅ 单测覆盖 |
| 分页参数越界 | `10001` | ✅ 单测覆盖 |
| 缺权限码（ELDER/FAMILY） | `20003` | ✅ 既有 `PermissionAspect` |

### 2.2 B7 规则映射 `GET /api/v1/rule/mapping?ruleVersionId=<id>`（`rule:view`）

| 步骤 | 期望 | 判定 |
|---|---|---|
| 缺 `ruleVersionId` | `code=10001`（`@RequestParam` 必填 → 400+PARAM_INVALID） | ✅ 冒烟已覆盖 |
| 有效 `ruleVersionId` | `code=0`，返回映射项 | ✅ 冒烟已覆盖 |
| 数据域 | **字典表**（`gb_rule_mapping` 不登记第三闸，mode=all，五角色同） | ✅ 单测覆盖 |

### 2.3 B8 二维码验真 `GET /api/v1/reports/verify?code=<reportNo>`（public，无权限码）

| 步骤 | 期望 | 判定 |
|---|---|---|
| 已发布报告编号 | `code=0` 且 `valid=true` | ✅ 冒烟已覆盖 |
| 不存在/非法编号 | `code=0` 且 `valid=false`（**不泄露存在性**） | ✅ 冒烟已覆盖 |
| 返回体字段 | **仅** `{valid, gradeCode, publishedAt}`，**不含** `name/idCard/phone`（裁决 4 最小返回面） | ✅ 冒烟已覆盖 |
| 验真口径 | 反查报告 → 按 `order_id` 取评估单 → 重算 `sha256(canonicalContent(order))`（8 字段 `|` join）比对 `report.verify_hash` | ✅ 单测覆盖（与 `AssessmentReportService#canonicalContent` 逐字段钉死） |
| 未认证访问 | **放行**（`SecurityConfig` permitAll，零改动） | ✅ 冒烟已覆盖（无令牌 `code=0`） |

### 2.4 B9 创建导出任务 `POST /api/v1/supervise/exports`（`data:export`，敏感点）

| 步骤 | 期望 | 判定 |
|---|---|---|
| 缺 `X-Second-Verify-Token` | `code=10008`（第二闸过 → 第四闸联动，自动） | ✅ 冒烟已覆盖 |
| 缺 `format`/`exportType` | `code=10001`（`@Valid` 先于切面） | ✅ 冒烟已覆盖 |
| 缺 `Idempotency-Key` | `code=10007` | ✅ 单测覆盖 |
| `format=XLSX/PDF`、`exportType≠EVAL_LIST` | `code=10001`（裁决 2：仅 CSV，零新增依赖） | ✅ 冒烟已覆盖（XLSX） |
| 带令牌 + `format=CSV` | `code=0`；存储键 `export/<taskNo>.csv` | ✅ 冒烟已覆盖 |
| 落盘文件 | 首 3 字节 `EF BB BF`（UTF-8 BOM）+ 首行 8 列表头 + CRLF | ✅ 冒烟已覆盖 |
| **公式注入转义** | 单元格首字符 ∈ `= + - @ \t \r` → 前置 `'`（真实产物单元格） | ✅ 冒烟已覆盖（`'=1+1`） |
| `export_task.org_id` | 落库**非空**（裁决 1-A：nullable 但由登录态写机构锚） | ✅ 冒烟已覆盖 |
| `audit_log` 留痕 | `action='EXPORT' AND biz_type='EXPORT_TASK' AND \`sensitive\`=1` | ✅ 单测覆盖（`@Audit` 联动） |
| 超限（`>max-sync-rows`） | `code=60001` | ✅ 单测覆盖 |

> **第四闸凭据为一次性**：每次敏感调用前各自 `/auth/second-verify` 换新凭据；同一 token 连打两次第二次必 `10008`。

### 2.5 B10 监管上报 `POST /api/v1/supervise/report`（`supervise:report:submit`）

| 步骤 | 期望 | 判定 |
|---|---|---|
| 机构管理员令牌 | `code=0`，返回 `{reportMonth, orgId, status, sentAt}` | ✅ 冒烟已覆盖 |
| 首次上报 | `supervise_report.retain_until IS NOT NULL`（**只写值**，裁决 3：无清理调度） | ✅ 冒烟已覆盖 |
| 同月同机构重放 | `code=0` 且**不新增行**（`uk_month_org` upsert） | ✅ 冒烟已覆盖 |
| 缺 `Idempotency-Key` | `code=10007` | ✅ 既有机制 |
| 数据域铁律 | `supervise_report` 登记 `SELF_DENY_TABLES`（scope=1/5 读它恒假 `(1 = 0)`）；写 `org_id` 取登录态 | ✅ 单测覆盖 |

### 2.6 R6 机构列表/详情 `GET /api/v1/account/orgs`｜`/orgs/{orgId}`（`account:org:manage` OR `:read`）

| 步骤 | 期望 | 判定 |
|---|---|---|
| 机构管理员列表 | `code=0`（第三闸 `org.id IN (orgIds)`） | ✅ 冒烟已覆盖 |
| 本机构详情 | `code=0` | ✅ 冒烟已覆盖 |
| 越界/不存在详情 | `code=10004`（**同码，不泄露存在性**） | ✅ 冒烟已覆盖 |
| 返回体字段 | **不含** `contactPhoneEnc`（机构联系方式密文列） | ✅ 单测覆盖 |

### 2.7 CR-M2-007 · 未认证修复（D-3 回归）

| 步骤 | 期望 | 判定 |
|---|---|---|
| 无 `Authorization` 访问受保护端点（B6） | **HTTP 401** + `R.code=10002` | ✅ 冒烟已覆盖 |
| 已认证但缺权限码 | HTTP 200 + `R.code=20003` | ✅ 既有 `PermissionAspect` |

---

## 3. 一键冒烟

```sh
MYSQL_BIN=/c/mysql/bin/mysql.exe MYSQL_PASSWORD='Mysql8@2026#Dev' \
  SECOND_VERIFY_DEV_CODE=246810 EXPORT_DIR="$PWD/target/smoke-export" \
  bash scripts/smoke-report-review-chain.sh
```

2026-09-28 实测：**通过 79 项，失败 0 项，跳过 0 项**（含新增 `[8/8] 报表与监管域` 段 26 条断言）。

> 断言数随环境变量变化（`MYSQL_BIN/MYSQL_PASSWORD`、`SECOND_VERIFY_DEV_CODE`、`EXPORT_DIR`），**勿硬编码总数**：
> 未设密钥 → B9 全链 1 跳过；未设 `EXPORT_DIR` → B9 魔数/表头/注入 1 跳过；未设 `MYSQL_BIN` → 落库锚/注入样本 各 1 跳过。

---

## 4. 四项门禁实测（2026-09-28）

| 门禁 | 命令 | 实测 |
|---|---|---|
| 单测 + 静态检查 | `mvn -o verify` | **185 tests / 0 failures / 0 errors / 0 skipped**；Checkstyle **0 violations ×14 模块**；SpotBugs **BugInstance 0 / Error 0**；BUILD SUCCESS |
| 结构门禁 | `bash scripts/verify-schema.sh` | **83 通过 / 0 失败 / 0 待批准守卫** |
| 契约门禁 | `bash scripts/verify-openapi.sh` | **8 通过 / 0 失败**；契约操作 43（implemented **33** / planned **10**），双向漂移 **0** |
| 端到端冒烟 | `bash scripts/smoke-report-review-chain.sh` | **79 通过 / 0 失败 / 0 跳过** |

---

## 5. 残留与不可达项（须在验收说明中显式声带）

1. **仅 CSV（裁决 2，残留 R2）**：`format ∈ {XLSX, PDF}`、`exportType ∈ {REPORT, STAT}` 一律 `10001`；**零新增依赖**（未引入 POI/EasyExcel/COS）。
2. **`export_task.org_id` 为 nullable（裁决 1-A）**：历史 NULL 行在 scope=2 下**天然不可见**（NULL 参与 `IN` 判定为假 → 拒），故**不**回填、**不**改 NOT NULL。
3. **`retain_until` 只写值（裁决 3）**：`RetentionChainScheduler` 覆盖 `eval_order`/`sys_user_third_party`，**不含** `supervise_report`；D-3 内**零新增 `@Scheduled`**。
4. **B8 最小返回面（裁决 4）**：仅 `{valid, gradeCode, publishedAt}`，任一时刻不返回 `name/idCard/phone`。
5. **导出文件/孤儿清理未做（P2-7）**：CSV 物理文件与 `export_task.expire_at` 过期清理不在 D-3 范围。
6. **B10 留痕未加 `@Audit`**：设计 §2.2 记为"可选（建议后续补 `@Audit(SUPERVISE_REPORT)`）"，本期未加，非验收项。
7. **冒烟"注入样本"口径偏差**：设计 §T05 记「`queryParams` 造一条以 `=` 开头的值 → 单元格以 `'` 开头」；因 CSV 单元格**只来自数据行**（8 列固定，`queryParams` 仅入 `export_task.query_params` 快照，不渲染为单元格），实现改为**把真实评估单编号临时改为 `=1+1`** 注入产物单元格，断言 `'=1+1` 前缀后还原——同一安全属性、更贴近真实产物、且确定性可复现。

---

## 6. QA 突变自检（删一处应全红）

> 下表为**工程师自检**结果（证据在下方命令）；正式验收建议由独立 QA 复跑一遍。

| # | 突变 | 期望变红 | 自检结果 |
|---|---|---|---|
| 1 | 从 `SELF_DENY_TABLES` 删 `supervise_report` | `DataScopePermissionHandlerTest#selfScopeFailsClosedOnSuperviseReport` | ✅ T01 已验（先红后绿：断言 `(1 = 0)`） |
| 2 | 从 `ORG_COLUMN` 删 `export_task` | `DataScopePermissionHandlerTest#orgScopeFiltersExportTaskByOrgId` | ✅ T01 已验（先红后绿：断言 `(export_task.org_id IN (1))`） |
| 3 | 摘除 B9 控制器的 `@RequiresPermission(DATA_EXPORT)` | `ControllerAuthorizationCoverageTest#everyHandlerEndpointDeclaresPermission` | ✅ 实测红：`存在未声明权限码的端点（裸奔）：[SuperviseExportController#create]` |
| 4 | 给 B8 handler 加 `@RequiresPermission` | `ControllerAuthorizationCoverageTest#whitelistHasNoRedundantEntry` | ✅ 实测红：`白名单存在无效/冗余条目：[ReportVerifyController#verify]` |
| 5 | 把某操作 `x-status` 由 `implemented` 翻回 `planned` | `OpenApiContractTest` / `verify-openapi.sh` 双向漂移 | ✅ 实测红：`verify-openapi.sh` 退出 1，`契约缺登的已实现端点 = 1` |

复现命令（突变 3/4/5，跑完须还原）：

```sh
# 3/4：改控制器注解后
mvn -o -pl yl-bootstrap -am test -Dtest=ControllerAuthorizationCoverageTest -Dsurefire.failIfNoSpecifiedTests=false
# 5：改 openapi x-status 后
bash scripts/verify-openapi.sh
```

> 注：`spotless:check` 绑定 `compile`、`checkstyle:check`/`spotbugs:check` 绑定 `verify`；故突变用 `mvn test` 触发，不引入格式/静态检查噪声。

