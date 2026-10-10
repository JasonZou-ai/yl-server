#!/bin/sh
# =============================================================================
# 短信核身通道 HTTP 冒烟（P2-8-A）——独立脚本，**不修改**既有冒烟脚本的任何断言。
#
# 覆盖（零 DB 播种，仅需后端在跑 + Redis）：
#   [1] SMS-01 图形验证码：未登录可达（HTTP 200）+ code=0 + captchaImage 为 PNG data URI + ttl=120
#   [2] SMS-02 参数校验：非法手机号 → 10001
#   [3] SMS-02 发送：合法手机号 → code=0 + maskedPhone 脱敏 + ttl=300（provider=log）
#   [4] SMS-02 频控：同手机号 60s 内第二次 → 10429
#   [5] SMS-03 校验：错误验证码 → 20006（错误与过期同码）
#   [6] SMS-04 兑换：伪造 ticket → 20008
#   [7] SMS-02 fail-closed：provider=tencent/aliyun（未接入）→ 20007【需 EXPECT_PROVIDER=tencent|aliyun】
#
# ★ 判定口径（与既有冒烟一致）：业务异常一律 **HTTP 200 + 统一响应体**，拒绝的证据是 `R.code != 0`。
#
# 前置：后端已启动（默认 http://127.0.0.1:8080），Redis 可用。
# 用法：
#   bash scripts/smoke-sms-chain.sh
#   BASE_URL=http://127.0.0.1:8081 bash scripts/smoke-sms-chain.sh
#   EXPECT_PROVIDER=tencent bash scripts/smoke-sms-chain.sh   # 追加 fail-closed 断言 [7]
# =============================================================================

BASE=${BASE_URL:-http://127.0.0.1:8080}
SMS_API="$BASE/api/v1/sms"
AUTH_API="$BASE/api/v1/auth"

# 随机手机号（每次运行不同，避免 60s 频控窗口串扰；格式 ^1[3-9]\d{9}$）
PHONE=${SMS_PHONE:-138$(printf '%08d' $(( ($$ * 7919) % 100000000 )))}
EXPECT_PROVIDER=${EXPECT_PROVIDER:-}

PASS=0
FAIL=0
SKIP=0

pass() { echo "  ✓ $1"; PASS=$((PASS + 1)); }
fail() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }
skip() { echo "  ⊘ $1"; SKIP=$((SKIP + 1)); }

check() { # desc / actual / expected
    if [ "$2" = "$3" ]; then pass "$1 = $2"; else fail "$1（期望 $3，实际 $2）"; fi
}

code_of() { echo "$1" | grep -o '"code":[0-9]*' | head -1 | sed 's/.*://'; }
str_field() { echo "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed "s/.*\"$2\":\"//;s/\"$//"; }

post_sms() { # $1=path $2=json
    curl -s --noproxy '*' -m 30 -X POST "$SMS_API$1" -H 'Content-Type: application/json' -d "$2"
}

echo "=============================================================="
echo " 短信核身通道冒烟（P2-8-A）：$BASE"
echo "=============================================================="
echo ""

# [1] SMS-01 图形验证码
echo "[1/7] SMS-01 图形验证码"
RESP=$(post_sms "/captcha-image" '{"scene":"sms_send"}')
check "SMS-01 code" "$(code_of "$RESP")" "0"
case "$(str_field "$RESP" captchaImage)" in
    data:image/png\;base64,*) pass "SMS-01 captchaImage 为 PNG data URI" ;;
    *) fail "SMS-01 captchaImage 非 PNG data URI" ;;
esac
echo ""

# [2] SMS-02 参数校验
echo "[2/7] SMS-02 非法手机号"
RESP=$(post_sms "/send" '{"phone":"123","purpose":"LOGIN","scene":"WECHAT"}')
check "非法手机号 code" "$(code_of "$RESP")" "10001"
echo ""

# [3] SMS-02 发送（首次）
echo "[3/7] SMS-02 发送（phone=$PHONE）"
RESP=$(post_sms "/send" "{\"phone\":\"$PHONE\",\"purpose\":\"LOGIN\",\"scene\":\"WECHAT\"}")
check "SMS-02 code" "$(code_of "$RESP")" "0"
MASKED=$(str_field "$RESP" maskedPhone)
case "$MASKED" in
    *"****"*) pass "SMS-02 返回脱敏手机号 = $MASKED" ;;
    *) fail "SMS-02 未返回脱敏手机号（$MASKED）" ;;
esac
echo ""

# [4] SMS-02 频控（同号 60s 第二次）
echo "[4/7] SMS-02 频控（同号 60s 内第二次）"
RESP=$(post_sms "/send" "{\"phone\":\"$PHONE\",\"purpose\":\"LOGIN\",\"scene\":\"WECHAT\"}")
check "频控 code" "$(code_of "$RESP")" "10429"
echo ""

# [5] SMS-03 错误验证码
echo "[5/7] SMS-03 错误验证码"
RESP=$(post_sms "/verify" "{\"phone\":\"$PHONE\",\"purpose\":\"LOGIN\",\"code\":\"000000\",\"scene\":\"WECHAT\"}")
check "错误验证码 code" "$(code_of "$RESP")" "20006"
echo ""

# [6] SMS-04 伪造 ticket
echo "[6/7] SMS-04 伪造 ticket"
RESP=$(curl -s --noproxy '*' -m 30 -X POST "$AUTH_API/login-by-ticket" \
    -H 'Content-Type: application/json' -d '{"ticket":"not-a-real-ticket","scene":"WECHAT"}')
check "伪造 ticket code" "$(code_of "$RESP")" "20008"
echo ""

# [7] fail-closed（仍需后端 provider 为真实服务商）
echo "[7/7] SMS-02 fail-closed（provider=$EXPECT_PROVIDER）"
if [ -z "$EXPECT_PROVIDER" ]; then
    skip "未设 EXPECT_PROVIDER，跳过 fail-closed 断言（需后端 provider=tencent/aliyun）"
else
    RESP=$(post_sms "/send" "{\"phone\":\"$PHONE\",\"purpose\":\"LOGIN\",\"scene\":\"WECHAT\"}")
    check "通道未接入 code" "$(code_of "$RESP")" "20007"
fi
echo ""

echo "=============================================================="
echo " 冒烟结果：通过 $PASS / 失败 $FAIL / 跳过 $SKIP"
echo "=============================================================="
[ "$FAIL" -eq 0 ] || exit 1
exit 0
