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

---

## 4. CI（GitHub Actions）

| 工作流 | 触发 | 内容 |
|---|---|---|
| `.github/workflows/ci.yml` | PR / push | ①格式与静态检查 ②构建与测试 ③SonarQube（main/develop） |
| `.github/workflows/build-image.yml` | main 合并 / tag / 手动 | 构建镜像并推送，支持按 tag 回滚 |

需要在仓库 Secrets 配置：`SONAR_TOKEN`、`SONAR_HOST_URL`、`REGISTRY_HOST`、`REGISTRY_USERNAME`、`REGISTRY_PASSWORD`。

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
- ~~`B1-4` 数据库 ER~~ → **已产出**：`docker/mysql/init/02_schema.sql`（34 表）、敏感字段 AES-256-GCM 加密、索引与 1 万条导出容量规划
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
