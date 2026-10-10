#!/bin/sh
# =============================================================================
# 镜像启动验证（P2-8-R14）——**构建产物必须被真的运行起来**才算通过。
#
# 为什么需要它（第四个门禁盲区：整个环节不存在）
#   CI 原有 5 个作业（提交信息规范 / 格式与静态检查 / DDL 落地验证 / 构建与测试 / SonarQube）
#   **没有一个启动过镜像**。而 `Dockerfile:36` 的 ENTRYPOINT 默认 `prod`，仓库却只有
#   `application.yml` + `application-dev.yml`（datasource/redis 只配在 dev）⇒ 5/5 全绿产出的
#   镜像**根本起不来**。
#   这与前三次盲区同源（43 项绿但新表零覆盖 / retention SQL 摘守卫仍绿 / M4–M13 摘哈希仍绿），
#   区别在于：前三次是「**断言**覆盖不到」，这次是「**环节**不存在」。
#
# 判据（写断言前必读）
#   **不覆盖 profile** 把容器以镜像默认 ENTRYPOINT 启起来（即走 Dockerfile 默认的 `prod`），
#   探 `GET /api/v1/system/health` —— HTTP 200 且 `R.code=0` 且 `data.status=UP` 才算通过。
#   ★ 刻意不传 `-e SPRING_PROFILES_ACTIVE`：要测的正是「**默认值能不能用**」。
#     传了就等于把 Dockerfile 的默认值从被测范围里摘出去，缺陷会再次溜过去。
#
# 库的形状：**生产形状** = 01/02/03（建库 + 结构 + RBAC），**不含 04_seed_dev_accounts.sql**。
#   原因：`DevCredentialStartupGuard`（P2-8-R12）在非 dev profile 下发现 `*_dev` 账号会
#   **拒绝启动** —— 那是设计意图，不是要绕过的障碍。生产初始化本就不该跑联调账号种子。
#
# 前置：本机 Docker 可用；MySQL / Redis 可在 127.0.0.1 访问（CI 里用 service 容器）。
# 环境变量（缺则 fail-closed，不静默降级）：
#   DB_PASSWORD  —— **必须显式提供**（允许为空串，CI 的 MySQL root 就是空口令），
#                   但**不允许"没有这个变量"**：application-prod.yml 用的是裸占位，
#                   变量缺失会让容器启动失败，这里提前判掉以便报错更清楚。
#   JWT_SECRET / SENSITIVE_FIELD_KEY / SENSITIVE_FIELD_HASH_KEY
#                —— 可选；未提供时本脚本**现场生成临时值**（仅本次验证用，绝不落盘）。
#                   生成密钥而不是写死测试值，是为了让本脚本自身**不承载任何密钥**。
#   MYSQL_BIN（默认 mysql）· DB_HOST（默认 127.0.0.1）· DB_PORT（3306）· DB_USERNAME（root）
#   DB_NAME（yl_evaluation）· REDIS_HOST（127.0.0.1）· REDIS_PORT（6379）
#   IMAGE_TAG（yl-server:ci-boot）· BOOT_TIMEOUT_SECONDS（180）
# =============================================================================

set -u

cd "$(dirname "$0")/.." || exit 1

MYSQL_BIN=${MYSQL_BIN:-mysql}
DB_HOST=${DB_HOST:-127.0.0.1}
DB_PORT=${DB_PORT:-3306}
DB_USERNAME=${DB_USERNAME:-root}
DB_NAME=${DB_NAME:-yl_evaluation}
REDIS_HOST=${REDIS_HOST:-127.0.0.1}
REDIS_PORT=${REDIS_PORT:-6379}
IMAGE_TAG=${IMAGE_TAG:-yl-server:ci-boot}
BOOT_TIMEOUT_SECONDS=${BOOT_TIMEOUT_SECONDS:-180}
CONTAINER_NAME=yl-boot-smoke

PASS=0
FAIL=0
pass() { PASS=$((PASS + 1)); printf '  ✓ %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf '  ✗ %s\n' "$1"; }

if [ -z "${DB_PASSWORD+set}" ]; then
    echo "✗ 未提供 DB_PASSWORD（**fail-closed**）："
    echo "  该值必须显式提供（可以为空串，但「没有这个变量」不算提供）——"
    echo "  application-prod.yml 对 datasource 口令用的是裸占位 \${DB_PASSWORD}，"
    echo "  变量缺失时容器会启动失败；在此提前判掉，好让报错更直接。"
    echo "  用法：DB_PASSWORD='' bash scripts/verify-image-boot.sh"
    exit 1
fi

if ! command -v docker >/dev/null 2>&1; then
    echo "✗ 未找到 docker（**fail-closed**）：本脚本验证的是镜像能否启动，没有 docker 就无法验证。"
    echo "  本机（Windows 开发环境）通常不装 docker —— 请在 CI 上运行本作业。"
    exit 1
fi

MYSQL_CONN="-h${DB_HOST} -P${DB_PORT} -u${DB_USERNAME} -p${DB_PASSWORD}"

# ---- 密钥：未提供则现场生成（仅本次验证；不写死、不落盘） ----
gen_key() { head -c "$1" /dev/urandom | base64 | tr -d '\n'; }
JWT_SECRET=${JWT_SECRET:-$(gen_key 48)}
SENSITIVE_FIELD_KEY=${SENSITIVE_FIELD_KEY:-$(gen_key 32)}
SENSITIVE_FIELD_HASH_KEY=${SENSITIVE_FIELD_HASH_KEY:-$(gen_key 32)}

echo "== [1/4] 以「生产形状」初始化数据库（跳过 04_seed_dev_accounts.sql）=="
INIT_OK=1
for f in docker/mysql/init/*.sql; do
    case "$f" in
        *04_seed_dev_accounts.sql)
            echo "  ⊘ 跳过 $f（联调账号种子；非 dev profile 下带 *_dev 账号会让启动守卫拒绝启动）"
            continue
            ;;
    esac
    if sed "s/\`yl_evaluation\`/\`${DB_NAME}\`/g" "$f" | "$MYSQL_BIN" $MYSQL_CONN >/dev/null 2>&1; then
        echo "  · 已执行 $f"
    else
        INIT_OK=0
        echo "  ✗ 执行失败：$f"
        break
    fi
done
if [ "$INIT_OK" = "1" ]; then
    pass "生产形状库初始化成功（01/02/03，未播种联调账号）"
else
    fail "生产形状库初始化失败（后续步骤无法进行）"
fi

echo ""
echo "== [2/4] 构建镜像 =="
if [ ! -f yl-bootstrap/target/yl-bootstrap.jar ]; then
    echo "  ✗ 缺少 yl-bootstrap/target/yl-bootstrap.jar —— 请先执行 mvn -DskipTests package"
    fail "镜像构建前置（jar 产物）缺失"
else
    if docker build -t "$IMAGE_TAG" . >/tmp/yl-image-build.log 2>&1; then
        pass "docker build 成功（$IMAGE_TAG）"
    else
        fail "docker build 失败（日志：/tmp/yl-image-build.log）"
        tail -n 20 /tmp/yl-image-build.log 2>/dev/null
    fi
fi

echo ""
echo "== [3/4] 以镜像默认 ENTRYPOINT 启动容器（★ 不覆盖 profile）=="
docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1
docker run -d --name "$CONTAINER_NAME" --network host \
    -e DB_HOST="$DB_HOST" -e DB_PORT="$DB_PORT" -e DB_USERNAME="$DB_USERNAME" \
    -e DB_PASSWORD="$DB_PASSWORD" -e DB_NAME="$DB_NAME" \
    -e REDIS_HOST="$REDIS_HOST" -e REDIS_PORT="$REDIS_PORT" \
    -e JWT_SECRET="$JWT_SECRET" \
    -e SENSITIVE_FIELD_KEY="$SENSITIVE_FIELD_KEY" \
    -e SENSITIVE_FIELD_HASH_KEY="$SENSITIVE_FIELD_HASH_KEY" \
    "$IMAGE_TAG" >/dev/null 2>&1

echo ""
echo "== [4/4] 探活 GET /api/v1/system/health =="
HEALTH_URL="http://127.0.0.1:8080/api/v1/system/health"
HTTP_CODE=""
BODY=""
elapsed=0
while [ "$elapsed" -lt "$BOOT_TIMEOUT_SECONDS" ]; do
    # 容器已退出就不必再等（区别于"还在启动"）
    if [ -z "$(docker ps -q -f "name=^/${CONTAINER_NAME}$")" ]; then
        break
    fi
    # 一次请求同时取「响应体」与「状态码」，避免两次请求拿到不一致的快照
    RESP=$(curl -s --noproxy '*' -m 5 -w '\n%{http_code}' "$HEALTH_URL" 2>/dev/null)
    HTTP_CODE=$(printf '%s' "$RESP" | tail -n 1)
    BODY=$(printf '%s' "$RESP" | sed '$d')
    [ "$HTTP_CODE" = "200" ] && break
    sleep 5
    elapsed=$((elapsed + 5))
done

if [ "$HTTP_CODE" = "200" ]; then
    pass "健康端点 HTTP 状态码 = 200"
else
    fail "健康端点 HTTP 状态码 = ${HTTP_CODE:-（无响应）}（等待 ${elapsed}s）"
fi

# ★ 提取一律用 `grep -o | head -1`（取**第一个**匹配），不要用 `sed 's/.*"key"...'`
#   —— sed 的 `.*` 是贪婪的，会取到**最后一个**匹配。健康响应里 `status` 出现两次
#   （`data.status`="UP" 与嵌套的 `reportFont.status`="ready"），贪婪匹配会把
#   "ready" 当成 data.status，导致断言恒假（假红）。
CODE=$(printf '%s' "$BODY" | grep -o '"code"[[:space:]]*:[[:space:]]*[0-9-]*' | head -1 \
    | sed 's/.*:[[:space:]]*//')
if [ "$CODE" = "0" ]; then
    pass "健康端点 R.code=0"
else
    fail "健康端点 R.code=${CODE:-（缺字段）}（本项目业务失败一律 HTTP 200 + R.code，故必须一并断言）"
fi

STATUS=$(printf '%s' "$BODY" | grep -o '"status"[[:space:]]*:[[:space:]]*"[A-Za-z]*"' | head -1 \
    | sed 's/.*:[[:space:]]*"//; s/"$//')
if [ "$STATUS" = "UP" ]; then
    pass "健康端点 data.status=UP"
else
    fail "健康端点 data.status=${STATUS:-（缺字段）}"
fi

# ---- 清理（无论成败都要清，避免污染下一次运行 —— 见 SMOKE-R1 的教训） ----
if [ "$FAIL" -gt 0 ]; then
    echo ""
    if [ -n "$(docker ps -aq -f "name=^/${CONTAINER_NAME}$")" ]; then
        echo "---- 容器日志（末尾 40 行，用于定位启动失败原因）----"
        docker logs --tail 40 "$CONTAINER_NAME" 2>&1 | sed 's/^/  | /'
    else
        echo "---- 容器未能创建（docker run 阶段即失败）----"
    fi
fi
docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1

echo ""
echo "=============================================================="
echo " 结果：通过 ${PASS} 项，失败 ${FAIL} 项"
echo "=============================================================="
[ "$FAIL" -eq 0 ] || exit 1
exit 0
