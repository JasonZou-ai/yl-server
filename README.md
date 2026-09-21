# yl-server · 银龄守护后端

老年人能力评估四端系统（iOS / 安卓 / 微信小程序 / 抖音小程序）的统一后端。
评估依据 **GB/T 42195-2022《老年人能力评估规范》**。

- 架构形态：**模块化单体**（不做微服务，模块边界强隔离，M3 后可按域拆分）
- 技术栈：Java 17 · Spring Boot 3.2 · Spring Security 6 (JWT) · MyBatis-Plus · MySQL 8 · Redis 7 · RocketMQ 5 · 腾讯云 COS · XXL-JOB
- CI：**GitHub Actions**（PR 门禁：格式化 + 静态检查 + 单测 + 覆盖率）
- 决策记录：见 `docs/ADR`（架构决策记录）

---

## 1. 模块结构

```
yl-server（父 POM）
├── yl-common          通用：统一响应 R、错误码、异常、追踪上下文、脱敏/加密工具
├── yl-domain          领域层：实体、值对象、领域服务、仓储端口（纯业务，不依赖 Spring/ORM）
├── yl-application     应用层：用例编排、事务边界、DTO 组装
├── yl-infrastructure  基础设施：MyBatis-Plus / Redis / COS / RocketMQ / XXL-JOB 适配
├── yl-api             接口层：REST 控制器、请求响应模型、校验、全局异常、API 文档
├── yl-modules         业务域聚合
│   ├── yl-module-account      账号权限（B2）
│   ├── yl-module-evaluation   评估填报 + 国标分级引擎（C1/C2）
│   ├── yl-module-archive      老人档案 / 报告归档（D）
│   ├── yl-module-care         个性化照护计划（D）
│   ├── yl-module-supervise    监管视角 / 合规留痕（D）
│   └── yl-module-report       统计报表 / 导出（含 1 万条异步导出预留）
└── yl-bootstrap       启动模块：装配全部模块，产出可执行 fat jar
```

**依赖方向（单向，禁止反向依赖由 Checkstyle/评审把关）：**

```
yl-common ← yl-domain ← yl-application ← yl-infrastructure
                              ↑
                           yl-api ← yl-modules/* ← yl-bootstrap
```

---

## 2. 本地一键启动

### 前置
- JDK 17（`java -version`）
- Maven 3.9+（或使用 `./mvnw`）
- Docker Desktop（用于中间件容器）

> Maven 建议配置国内镜像（阿里云）加速依赖下载，见文末。

### 首次克隆后：启用 Git 钩子（一次即可）

```bash
bash scripts/setup-hooks.sh
```

- `commit-msg`：校验提交信息符合 Conventional Commits（见 §3）
- `pre-commit`：秒级检查——构建产物/二进制误提交、敏感凭据与私钥、超大文件、CRLF 行尾、调试残留

钩子存放于随仓库版本控制的 `.githooks/`，通过 `core.hooksPath` 生效，**不需要往 `.git/hooks` 拷贝文件**。
未启用也不影响正确性（CI 会兜底），但本地就失去了快速反馈。取舍见 `docs/ADR/0002-commit-convention-and-hooks.md`。

### 一键启动

```bash
bash scripts/dev-up.sh
```

或分两步：

```bash
# 1) 拉起 MySQL8 + Redis7 + RocketMQ5
docker compose up -d

# 2) 启动应用（dev）
mvn -pl yl-bootstrap -am spring-boot:run -Dspring-boot.run.profiles=dev
```

启动后自检：

| 用途 | 地址 |
|---|---|
| 健康检查 | http://127.0.0.1:8080/api/v1/system/health |
| 接口文档（Knife4j） | http://127.0.0.1:8080/doc.html |
| Actuator | http://127.0.0.1:8080/actuator/health |

默认端口占用：MySQL `3306`、Redis `6379`、RocketMQ Namesrv `9876` / Broker `10911`。
本地开发口令（**仅限本地**）：MySQL `root/yl_root_2026`，Redis 密码 `yl_redis_2026`。

### 契约与结构文件（B1-2 / B1-4 产出）

| 文件 | 说明 |
|---|---|
| `openapi/yl-api.yaml` | **统一 API 契约**（OpenAPI 3.0.3，20 个路径）。四端共用；实现须与契约一致，契约变更走评审 |
| `docker/mysql/init/01_init.sql` | 建库 + 国标规则版本表 `gb_rule_version` |
| `docker/mysql/init/02_schema.sql` | **7 大核心域 + 3 支撑域 DDL（34 张表）**；含敏感字段密文列、国标规则域、审计与埋点 |
| `checkstyle/checkstyle.xml` | 静态检查规则；豁免用源码内注释标记 `// CHECKSTYLE_OFF: <CheckName>` … `// CHECKSTYLE_ON: <CheckName>`（随代码走，不依赖外置文件） |
| `scripts/verify-schema.sh` | **DDL 落地验证**：真实 MySQL 执行 init 脚本，断言表/列/索引/国标播种，并做「软删除 × 唯一键」行为验证 |
| `scripts/setup-hooks.sh` | 启用 Git 钩子（提交信息校验 + 提交前检查），`--status` / `--uninstall` 管理 |

---

## 3. 代码规范与静态检查

| 工具 | 作用 | 命令 |
|---|---|---|
| Spotless (google-java-format AOSP) | 格式统一 | `mvn spotless:apply`（修复）/ `mvn spotless:check`（校验） |
| Checkstyle | 禁 `System.out` / `printStackTrace` / `java.util.Date`、圈复杂度 ≤15 等 | 绑定 `verify`，随 `mvn verify` 执行 |
| SpotBugs | 字节码缺陷扫描（effort=Max，threshold=Medium，**Medium 及以上阻断构建**） | 绑定 `verify`，随 `mvn verify` 执行 |
| JaCoCo | 覆盖率 | `mvn verify` |

> 三个闸门均已绑定 `mvn verify`，**一次 `mvn verify` 即完成全部质量校验**，CI 与本地行为一致。

**豁免约定（不得滥用于掩盖问题）：**

- Checkstyle：源码内注释标记 `// CHECKSTYLE_OFF: <CheckName>` … `// CHECKSTYLE_ON: <CheckName>`，随代码走，不依赖外置文件。
- SpotBugs：使用 `@SuppressFBWarnings(value = "...", justification = "...")` 精准标注，**`justification` 必填**。若是真实缺陷
  （如本次修复的 `HRS_REQUEST_PARAMETER_TO_HTTP_HEADER` 头注入），必须**先修代码**，不得压制。

跳过静态检查快速构建（仅本地应急）：`mvn -Pfast verify`（等价 `-DskipCheckstyle=true -DskipSpotbugs=true`）

设计取舍见 `docs/ADR/0001-static-analysis-gates.md`。

### 提交信息规范（Conventional Commits）

格式 `<type>(<scope>)!: <描述>`，`type` 取值：`feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert`。

```bash
git commit -m "feat(eval): 新增评估单提交复核接口"
git commit -m "fix(rule): 修正 45 分边界上的等级判定错误"
git commit -m "docs(adr): 补充提交规范与本地钩子决策记录"
```

本地由 `.githooks/commit-msg` 校验，CI 的 `commit-lint` 作业调用**同一份脚本**，口径唯一。
首行之外的正文不校验——鼓励写清"为什么这么改"，国标规则类改动须留下条款依据。
设计取舍见 `docs/ADR/0002-commit-convention-and-hooks.md`。

---

## 4. CI（GitHub Actions）

| 工作流 | 触发 | 内容 |
|---|---|---|
| `.github/workflows/ci.yml` | PR / push | ①提交信息规范 ②格式与静态检查 ③DDL 落地验证 ④构建与测试 ⑤SonarQube（main/develop） |
| `.github/workflows/build-image.yml` | main 合并 / tag / 手动 | 构建镜像并推送，支持按 tag 回滚 |

需要在仓库 Secrets 配置：`SONAR_TOKEN`、`SONAR_HOST_URL`、`REGISTRY_HOST`、`REGISTRY_USERNAME`、`REGISTRY_PASSWORD`。

### DDL 落地验证（B1-4 / 任务 r4UcEv）

```bash
# 有 Docker：先起中间件，再校验
docker compose up -d mysql
bash scripts/verify-schema.sh

# 任意 MySQL 8 实例（本地便携版 / 已有实例）
MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306 MYSQL_USER=root MYSQL_PASSWORD=xxx \
  MYSQL_BIN=/path/to/mysql bash scripts/verify-schema.sh

# 验证后清理测试库
bash scripts/verify-schema.sh --drop
```

脚本先 DROP 再重建 `yl_evaluation`，顺序执行 `docker/mysql/init/*.sql`，然后断言 **34 项**：
表数量（37）、3 处 `active_uk` 生成列、敏感字段密文/摘要列、**明文敏感列必须为 0**、
索引数量、国标规则域播种（1 版本 / 4 维度 / 5 等级阈值 / 3 基规则 / 条款号可回溯）、
评估单状态机与录入-复核互斥字段、埋点 180 天留存列、
**ADR-0003 全库口径**（自增列 = 1、`TIMESTAMP` = 0、`DATETIME` ≥ 60、外键 = 0、`deleted` ≥ 19），
并做**行为验证**——软删除后同键绑定可重建、未删除时唯一键真实拒绝重复（`active_uk` 方案生效的证据）。
CI 的 `schema-verify` 作业用 MySQL 8.0.37 service container 跑同一脚本。

全库级口径（主键策略 / 时区 / 引用完整性 / 软删除）见 `docs/ADR/0003-id-timezone-fk-softdelete.md`。

---

## 5. 合规约束（必须遵守）

- **敏感字段加密**：身份证号等使用 `yl.security.sensitive-field-key` 加密后落库，禁止明文。
- **脱敏输出**：列表/详情默认脱敏（PRD §7）；埋点禁止采集身份证全文与人脸原图（PRD §9）。
- **日志安全**：禁止打印证件号、手机号全文；异常不向客户端返回堆栈。
- **幂等**：写接口统一携带幂等 Key（PRD §7）。
- **国标可追溯**：评估规则条目须可回溯到 GB/T 42195-2022 条款号。

---

## 6. 待办衔接

- ~~`B1-2` 统一 API 规范与网关~~ → **已产出**：契约 `openapi/yl-api.yaml`、错误码字典、JWT 双令牌鉴权、Redis 限流、幂等切面
- ~~`B1-4` 数据库 ER~~ → **已产出**：`docker/mysql/init/`（合计 37 表 = `02_schema.sql` 36 + `01_init.sql` 1，含 ER 评审补丁 3 表）、敏感字段 AES-256-GCM 加密、索引与 1 万条导出容量规划
- ~~DDL 真实执行验证~~ → **已完成**：`scripts/verify-schema.sh` 在 MySQL 8.0.37 上 **34 项断言全绿**，已纳入 CI（`schema-verify` 作业，MySQL service container）
- ~~ER 三方评审 P1 DDL 补丁~~ → **已应用**：ER-03 / 04 / 05 / 06 / 07 / 08 六项落库（表数 34 → 37），提交 `75b704a`
- ~~ER-09 / 12 / 13 全库口径（主键 / 时区 / 外键 / 软删除）~~ → **已完成**：`docs/ADR/0003-id-timezone-fk-softdelete.md`，并固化为 `verify-schema.sh` 断言（违反即 CI 失败）
- ~~提交规范与本地钩子~~ → **已完成**：`.githooks/`（commit-msg + pre-commit）+ `scripts/setup-hooks.sh`；CI `commit-lint` 复用同一份脚本
- **待人工签字（不可由研发代签）**：ER-03 保留期 / ER-08 告知同意留痕 待 **DPO 会签**（ER 纪要 §七 生效条件 2）
- `B2` 账号 RBAC（本模块 `yl-module-account`）
- `C2` 国标规则引擎（本模块 `yl-module-evaluation`，规则版本表见 `docker/mysql/init/01_init.sql`，26 项指标与规则条目播种归 C2-1）

---

## 附：Maven 国内镜像（`~/.m2/settings.xml`）

```xml
<mirror>
  <id>aliyun</id>
  <mirrorOf>central</mirrorOf>
  <url>https://maven.aliyun.com/repository/public</url>
</mirror>
```
