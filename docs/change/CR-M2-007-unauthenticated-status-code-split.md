# CR-M2-007 未认证状态码分叉（未携带凭据 403 / 凭据无效 401）

| 项 | 内容 |
|---|---|
| 编号 | **CR-M2-007** |
| 类型 | **变更 / 行为一致性缺陷（第一闸语义）** |
| 优先级 | **P2 —— 不泄露数据、不改变"拦截/放行"的结论，只影响状态码语义与客户端错误提示** |
| 状态 | **✅ 已裁决（采纳「统一 401」）并随 D-2 落地**（裁决记录见 §八） |
| 登记日期 | 2026-09-24 |
| 发现来源 | **CR-M2-006 验收复验（副产品）** —— 定位冒烟假红时直测端点顺带发现 |
| 关联 | `SecurityConfig`、`JwtAuthFilter`、CR-M2-002、`openapi/yl-api.yaml` v1.1.1 |

---

## 一、现象（一句话）

**同一语义「你没有登录」被实现成两个状态码**：请求不带 `Authorization` 头 → **403**；带了但无效/过期 → **401**。

---

## 二、证据

### 2.1 源码事实

| 路径 | 行为 |
|---|---|
| `JwtAuthFilter`（L173-177） | **仅当**解析到 token 但校验失败时，显式 `response.setStatus(SC_UNAUTHORIZED)` + `R.fail(UNAUTHORIZED)`（= 401 + 业务码 10002） |
| `JwtAuthFilter`（无 `Authorization` 头时） | **直接放行**（不做任何响应），交给后续过滤器链 |
| `SecurityConfig`（L76-77） | `anyRequest().authenticated()` 拒绝匿名请求 |
| `SecurityConfig`（全文） | **没有** `exceptionHandling(...)`，**没有** `authenticationEntryPoint` |
| 兜底 | Spring Security 默认 `Http403ForbiddenEntryPoint` → 匿名访问受保护资源落 **403** |

### 2.2 实测

```
$ curl -H "Authorization: Bearer not-a-real-token" \
       http://127.0.0.1:8080/api/v1/evaluation/reports/RP-CR-M2-006-ORPHAN/pdf
  -> HTTP 401 + {"code":10002,...}          ← JwtAuthFilter 显式置位

$ curl http://127.0.0.1:8080/api/v1/evaluation/reports/RP-CR-M2-006-ORPHAN/pdf
  -> HTTP 403                                ← 落到默认入口点，且响应体非统一响应体
```

### 2.3 与既有承诺不符

`SecurityConfig` 类注释（L35）写明：

> 未认证统一返回 401 + 统一响应体，不跳转登录页

**注释承诺 401 + `R`，实测 403 且响应体不是 `R`** —— 与 CR-M2-006 同族的"文档/实现不一致"。

---

## 三、影响面

| 维度 | 评估 |
|---|---|
| **安全结论** | **未被削弱**：403 与 401 都是"拒绝"，越权访问不会因此放行 |
| **数据泄露** | 无 —— 两种情形都在进入业务代码前被拦 |
| **客户端体验** | **有影响**：四端若按 403 判"已登录但无权限"、401 判"需重新登录"，则**未带凭据时**会提示成权限问题而非"请重新登录"，用户按错误提示操作（找管理员要权限）而实际只需重登 |
| **可观测性** | 403 响应体非统一 `R`，日志/告警管道按 `code` 聚合的规则会漏掉这一类 |
| **契约** | 已在 v1.1.1 中**如实声明**（`401` = 凭据无效/过期、`403` = 未携带凭据），故**契约与现实已无漂移**；本卡争的是"要不要统一成 401" |

---

## 四、建议修复（最小改动，约 12 行）

在 `SecurityConfig` 的过滤器链上补入口点与拒绝处理器，落回统一响应体：

```java
.exceptionHandling(
        ex ->
                ex.authenticationEntryPoint(this::writeUnauthorized)   // 未认证 → 401 + R(10002)
                        .accessDeniedHandler(this::writeForbidden))    // 已认证但无权 → 403 + R(10003)
```

- `writeUnauthorized`：`setStatus(SC_UNAUTHORIZED)` + `Content-Type: application/json;charset=UTF-8` + `R.fail(UNAUTHORIZED...)`；
- `writeForbidden`：同理，403 + `R.fail(FORBIDDEN...)`；
- 需注入口袋 `ObjectMapper`（**构造器注入**，勿 `new ObjectMapper()`，保持与 MVC 的序列化口径一致）。

> **理由**：`R` 的序列化口径（时间格式、空值处理、字段名）必须与 MVC 一致，否则同一个 `code` 在两条路径上格式不同，反而新增不一致。

---

## 五、为何不在 CR-M2-006 批次夹带

1. **爆炸半径不同**：CR-M2-006 只动 2 个类的 1 个端点；本卡动的是**第一闸**，影响**全站每一个端点**在"未认证"下的响应。
2. **可能是有意设计**：403-only 的入口点是部分团队的刻意选择（不暴露"认证机制存在"）。**是否统一需 PM/架构裁决**，不应由修复方默认放行 —— 与本项目「契约过一道轻评审再实现」的既定口径一致。
3. **当前无实际损害**：拦截结论正确、数据未泄露，故不构成 P0/P1。

---

## 六、验收标准（若裁决修复）

| # | 验收项 | 判定 |
|---|---|---|
| 1 | 未携带 `Authorization` | 401 + `R.code=10002`（不再 403） |
| 2 | 凭据无效 / 已过期 | 401 + `R.code=10002`（保持） |
| 3 | 已认证但无权限码 | 403 + `R.code=10003`（或保持现状，若现状已含 `R`） |
| 4 | 响应体格式 | 与 MVC 序列化口径一致（同一 `ObjectMapper`） |
| 5 | 回归 | 冒烟脚本新增「未携带凭据 → 401」断言；`SecurityConfig` 类注释与实现一致 |
| 6 | 契约 | `openapi/yl-api.yaml` 全局约定同步；`verify-openapi.sh` / `OpenApiContractTest` 不因状态码变更而红 |

---

## 七、结论

| 项 | 结论 |
|---|---|
| 是否真实缺陷 | **是**（实现与自身文档注释矛盾、同一语义两个状态码），但**非安全缺陷** |
| 严重度 | **P2** |
| 是否阻塞 D 系列 | **不阻塞** |
| 建议时点 | 随 **D-2**（档案域后端）一并处理，或独立小批次；需先裁决"统一 401"还是"保留 403-only" |

---

## 八、裁决记录（2026-09-26 · D-2 落地，**采纳**）

**裁决**：采纳「统一 401」方案。在 `SecurityConfig.filterChain` 补 `exceptionHandling`：

```java
.exceptionHandling(
        ex -> ex.authenticationEntryPoint(authenticationEntryPoint)   // 未认证 → 401 + R.fail(UNAUTHORIZED/10002)
                 .accessDeniedHandler(accessDeniedHandler))            // 框架层拒绝 → 403 + R.fail(FORBIDDEN/10003)
```

**落地位置**：

| 项 | 文件:行 |
|---|---|
| 入口点装配 | `yl-api/src/main/java/cn/yl/api/config/SecurityConfig.java:96-99` |
| 401 入口点 | `yl-api/src/main/java/cn/yl/api/security/RestAuthenticationEntryPoint.java`（新增，`@Component`，构造器注入 `ObjectMapper`） |
| 403 拒绝处理器 | `yl-api/src/main/java/cn/yl/api/security/RestAccessDeniedHandler.java`（新增，同口径） |
| 类注释同步 | `SecurityConfig.java:37,41-44`（把"未认证统一 401"从承诺改为**已实现的事实描述**） |

**职责边界（两条路径不重叠，勿混战）**：

- 入口点/拒绝处理器**只处理过滤器链内**的 `AuthenticationException`/`AccessDeniedException`（第一闸）；
- `PermissionAspect` 抛的 `BizException`（业务层无权=20003 / 缺二次验证=10008）在 DispatcherServlet 内，
  仍由 `GlobalExceptionHandler` 收敛为 **200 + `R.code`**；**未改动** `PermissionAspect` 与 `GlobalExceptionHandler`。

**契约**：无需改契约（v1.1.1 已如实声明 401/403），`verify-openapi.sh`（8/8）与 `OpenApiContractTest` 不因本变更而红。

**验收实测**（`scripts/smoke-report-review-chain.sh` §[7/7]）：

| 验收项 §六 | 实测 |
|---|---|
| ① 未携带 `Authorization` | **HTTP 401 + `R.code=10002`** ✅ |
| ② 凭据无效 / 过期 | 401 + `R.code=10002` ✅（`JwtAuthFilter` 既有） |
| ③ 已认证但缺权限码 | **200 + `R.code=20003`** ✅（第二闸，既有） |
| ④ 响应体口径 | 与 MVC 同一 `ObjectMapper` ✅ |
| ⑤ 回归 | 冒烟新增「未携带凭据 → 401 + 10002」断言；类注释与实现一致 ✅ |
| ⑥ 契约/门禁 | `verify-openapi.sh` 8/8、`OpenApiContractTest` 绿 ✅ |
