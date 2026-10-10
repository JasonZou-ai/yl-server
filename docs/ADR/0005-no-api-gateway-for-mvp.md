# ADR-0005：MVP 阶段不引入独立 API 网关（B1-2 / #57 收口）

- 状态：**已接受**（2026-09-22）
- 决策者：产品负责人（拍板）+ 研发侧
- 相关：B1-2 API 契约（21 路径 / 22 操作）、任务清单 #57、看板 v2.1、ADR-0003 / ADR-0004

## 背景

清单 #57 长期挂起：B1-2 的「独立 API 网关」**从未作为独立模块落地**——全仓无 `gateway` 模块、无任何 `spring-cloud-gateway` 依赖（全量 pom grep 为空），鉴权与限流实际已由应用内承担。

它同时是「安卓切真实接口」的**前置**：不先定，BASE_URL 与限流方案悬空，联调必然返工。

现状事实（2026-09-22 实证）：

- **部署形态**：单体 `yl-server`（`yl-bootstrap`），`server.port: 8080`，**未配置 context-path**，根路径即 `/`。
- **鉴权**：`yl-api` 的 `SecurityConfig` + `JwtAuthFilter`，STATELESS + 关闭 CSRF，Bearer JWT（access 2h / refresh 14d）。
- **限流**：`RateLimitInterceptor`（注册于 `WebMvcConfig`），底层 `RedisRateLimiter`；已登录按 `userId + 接口` 60 秒 120 次，匿名按 `ip + 接口` 60 秒 30 次，命中抛 `RATE_LIMITED`（10429）。

## 决策

**MVP 阶段（M2–M3）不引入独立 API 网关模块。** 鉴权、限流、审计继续由应用内 `SecurityConfig` + `RateLimitInterceptor` 承担，四端直连 `yl-server`。

## 备选与淘汰理由

| 方案 | 评估 |
|---|---|
| **不引入网关（选）** | 当前单体部署、单入口，网关的核心收益（统一路由、集中限流、跨服务鉴权传递）在单体下几乎为零；零新增模块与运维面，#57 立即收口，安卓 BASE_URL 当场可锁 |
| 落地独立 gateway 模块（spring-cloud-gateway） | 需新增模块 + spring-cloud 依赖体系 + 独立部署单元 + 路由/熔断配置；把「四端联调」和「基础设施改造」绑到同一条关键路径，拖慢 M2-S2 主链路闭环 |
| 保留 #57 悬空、等联调后再定 | 直接违背「前置先定」原则：BASE_URL 与限流配置会反复返工（本项目已显式点名该风险） |

## 由决策派生的四条硬约束

1. **BASE_URL 锁定为单体直连**：`http://<host>:8080`，**API 前缀 `/api/v1`**，无 context-path。安卓 / iOS / 微信 / 抖音四端统一。
2. **限流语义以应用内为准**：四端按 `RATE_LIMITED`（10429）统一提示与退避重试；**不得假设网关层限流**。
3. **IP 取真实客户端**：拦截器已优先 `X-Forwarded-For` / `X-Real-IP`；即便后续挂 Nginx 或负载均衡，匿名限流维度仍正确，无需改码。
4. **接口设计不得依赖网关能力**：路由重写、跨服务聚合、灰度染色一律不做；确有需要时在应用内 controller 层解决。

## 何时重新评估（触发条件）

出现下列任一情况，即启动「引入独立网关」的重新评估：

- **多服务拆分**：出现 ≥ 2 个可独立部署的后端服务，需要统一入口与跨服务鉴权传递；
- **灰度路由需求**：需要按版本 / 租户 / 用户分桶做流量切分与灰度发布；
- 需要**集中熔断降级与重试编排**，或运维要求统一的网关级访问日志 / 审计视图；
- 前后端拆为多域名，需网关统一处理 CORS 与证书卸载。

## 对后续步骤的影响

- 解锁「第四步 安卓切真实接口」：BASE_URL 与限流配置在本 ADR 生效后即可锁定，不再返工。
- 第二步（C1 评估中心接入引擎）、第三步（C3 报告绑定 ruleVersion）不受本决策影响。

## 落地位置

- 鉴权：`yl-api/src/main/java/cn/yl/api/config/SecurityConfig.java`（+ `JwtAuthFilter`）
- 限流：`yl-api/src/main/java/cn/yl/api/support/RateLimitInterceptor.java`（注册于 `cn/yl/api/config/WebMvcConfig.java`；端口 `cn.yl.common.ratelimit.RateLimiter`，实现 `cn.yl.infrastructure.ratelimit.RedisRateLimiter`）
- 端口 / 路径：`yl-bootstrap/src/main/resources/application.yml`（`server.port=8080`，无 context-path）
