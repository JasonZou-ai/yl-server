# D-2 档案域 · 端到端验证清单（E2E Checklist）

> 角色：工程师（寇豆码）｜依据：`docs/design/D2-archive-domain-design.md`
> 范围：`yl-server` 档案域 4 端点翻旗（`POST /api/v1/elders`、`GET /api/v1/elders`、
> `GET /api/v1/elders/{elderId}`、`POST /api/v1/security/reveal`）+ CR-M2-007 未认证修复。

---

## 0. 判定口径（务必先读）

- 本项目业务失败一律 **HTTP 200 + 响应体 `R.code`**（`GlobalExceptionHandler#handleBiz` 无 `@ResponseStatus`）。
  **拒绝的证据是 `R.code != 0`，绝不以 HTTP 200 当"放行"证据（恒真、假绿）**。
- 唯一例外：**未携带凭据**属框架层（Spring Security 过滤器链），CR-M2-007 修复后为
  **HTTP 401 + `R.code = 10002`**（须同时校验状态码与业务码）。
- 断言一律下沉到 `R.code` / 魔数层级。

---

## 1. 环境前置

| 项 | 值 / 命令 |
|---|---|
| MySQL 8 | `C:\mysql`（不在 PATH）：`MYSQL_BIN=/c/mysql/bin/mysql.exe`，root 口令 `Mysql8@2026#Dev` |
| Redis | `C:\redis`，6379 无密码（启动时须 `REDIS_PASSWORD=''`） |
| 敏感字段密钥 | Base64 且 ≥32 字节，启动时注入（建档/reveal 正常路径必需） |
| 二次验证口令 | `SECOND_VERIFY_DEV_CODE`（敏感 reveal 必需） |
| dev 账号 | `assessor_dev`（ASSESSOR）/ `orgadmin_dev`（ORG_ADMIN，reveal 用）；口令由环境变量 **`DEV_LOGIN_PASSWORD`** 注入（**不落任何已跟踪文件**，见 `04_seed_dev_accounts.sql` 头部声明） |

启动后端（逐字照抄，三个坑必须绕开）：

```sh
# ① 必须先 unset 宿主注入的 SERVER__PORT（会覆盖 server.port → "Port ... already in use"）
# ② REDIS_PASSWORD='' （本机 Redis 无密码；yml 默认 yl_redis_2026 会 AUTH 失败）
unset SERVER__PORT
export DB_PASSWORD='Mysql8@2026#Dev' REDIS_PASSWORD='' SECOND_VERIFY_DEV_CODE=246810 \
       SENSITIVE_FIELD_KEY="$(head -c 32 /dev/urandom | base64 -w0)" \
       SENSITIVE_FIELD_HASH_KEY="$(head -c 32 /dev/urandom | base64 -w0)"
java -cp yl-bootstrap/target/yl-bootstrap.jar \
     org.springframework.boot.loader.launch.JarLauncher \
     --spring.profiles.active=dev --server.port=8080
# ③ 复验前确认新代码真在跑：netstat -ano | grep :8080 → taskkill //F //PID <pid> → 重建 → 起新实例 → 看日志时间戳
```

> 注：本机 sandbox 会静默丢弃 `env -u ...` 形式的命令，故用 `unset` + `export` 替代。

---

## 2. 逐端点验收

### 2.1 建档 `POST /api/v1/elders`（`@Idempotent`，`Idempotency-Key` 必填）

| 步骤 | 期望 | 判定 |
|---|---|---|
| 评估员/机构管理员令牌 + `Idempotency-Key` + 明文 body | `code=0`，返回 `ElderView` | ✅ 冒烟已覆盖 |
| 返回体 `name` | 脱敏形态 `张*` | ✅ |
| 返回体 `idCardMask` | 掩码串 `5301**********nnnn` | ✅ |
| 查库 `elder.name_enc` / `id_card_enc` | **密文**（≠ 明文） | ✅ |
| 查库 `elder.name_hash` / `id_card_hash` | 非空、`CHAR_LENGTH = 64` | ✅ |
| 同一身份证再建档（`deleted=0`） | `code=10005`（CONFLICT） | ⬜ 单测覆盖 |
| `orgId` 缺失 | 取登录态默认机构 | ✅ 单测覆盖 |
| `orgId` 越出本人机构域 | `code=20003`（ROLE_NOT_PERMITTED） | ✅ 单测覆盖 |
| 未装配敏感字段密钥 | `code=40001`（拒绝明文落库） | ✅ 单测覆盖 |
| 缺 `Idempotency-Key` | `code=10007` | ⬜ 既有机制 |

### 2.2 列表 `GET /api/v1/elders`（`elder:archive:read`）

| 步骤 | 期望 | 判定 |
|---|---|---|
| 评估员令牌 | `code=0`，仅见本机构行（第三闸 `org_id IN (orgIds)`） | ✅ 冒烟已覆盖 |
| `keyword` | 姓名走 `name_hash` 精确 / 档案号走前缀（GCM 密文不可模糊查） | ✅ 单测覆盖 |
| 查询语句 | 显式含 `deleted = 0` | ✅ 单测覆盖 |

### 2.3 详情 `GET /api/v1/elders/{elderId}`（`elder:archive:read`）

| 步骤 | 期望 | 判定 |
|---|---|---|
| 本机构内 | `code=0`，`name` 脱敏 | ✅ 冒烟已覆盖 |
| 跨机构 / 不存在 | `code=10004`（**同码，不泄露存在性**） | ✅ 冒烟已覆盖（含改挂 org_id=999 回归） |

### 2.4 敏感字段明文 `POST /api/v1/security/reveal`（`data:reveal`，敏感点）

| 步骤 | 期望 | 判定 |
|---|---|---|
| 缺 `X-Second-Verify-Token` | `code=10008`（SECOND_VERIFY_REQUIRED） | ✅ 冒烟已覆盖 |
| 带令牌 + 白名单字段 | `code=0`，返回明文 name/idCardNo/phone/address | ✅ 冒烟已覆盖 |
| 白名单外字段（如 `healthHistoryEnc`） | `code=10001` | ✅ 单测覆盖 |
| `bizType=USER` | `code=10001`（"暂不支持"，本期仅 ELDER） | ✅ 单测覆盖 |
| 越界 / 不存在 | `code=10004` | ✅ 单测覆盖 |
| `audit_log` 留痕 | 存在 `action='DATA_REVEAL' AND biz_type='ELDER' AND \`sensitive\`=1`（查询须给 `sensitive` 加反引号——保留字） | ✅ 冒烟已覆盖 |

### 2.5 CR-M2-007 · 未认证修复

| 步骤 | 期望 | 判定 |
|---|---|---|
| 无 `Authorization` 访问受保护端点 | **HTTP 401** + `R.code=10002` | ✅ 冒烟已覆盖 |
| 坏 / 过期令牌 | HTTP 401 + `R.code=10002` | ✅ 既有 `JwtAuthFilter` |
| 已认证但缺权限码 | **HTTP 200** + `R.code=20003` | ✅ 既有 `PermissionAspect` |

---

## 3. 一键冒烟

```sh
MYSQL_BIN=/c/mysql/bin/mysql.exe MYSQL_PASSWORD='Mysql8@2026#Dev' \
  SECOND_VERIFY_DEV_CODE=246810 bash scripts/smoke-report-review-chain.sh
```

2026-09-26 实测：**通过 53 项，失败 0 项，跳过 0 项**（含新增 `[7/7] 档案域` 段）。

---

## 4. 残留与不可达项（须在验收说明中显式声带）

1. **ELDER 端真机 E2E 不可达（`D-2R1`）**：老人↔登录账号的绑定写入流程本期不实现，`elder.user_id`
   无生产写入方，故老人本人看不到自己档案的**真机**路径不可达。本期以
   `DataScopePermissionHandlerTest#selfScopeFiltersElderById`（钉 `(elder.user_id = me)`）作为 SQL 语义的
   确定性正确性证明；E2E 随 `D-2R1` 落地补齐。
2. **`dataReveal.bizType=USER` 未支持**：收到即 `10001`（"暂不支持"）；属账号域，本期不扩面。
3. **`audit_log.biz_id` 未填**：`@Audit` 仅透传 `action/biz_type/sensitive`，`biz_id` 保持 NULL；
   靠 `action + biz_type + sensitive=1` 留痕（满足 D-2 验收）。
4. **孤儿 PDF 清理未做**：报告 PDF 对象键的物理文件清理不在 D-2 范围。
