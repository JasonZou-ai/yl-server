#!/usr/bin/env bash
# =============================================================================
# D-1 一次性夹具：非空列表「真实路径」实跑 + 状态闸负例 + 自清理
# -----------------------------------------------------------------------------
# 立项依据：docs/reviews/2026-10-15-closeout-plan-for-approval.md §三 D 组
#   D-1 🟠 非空列表真实路径未实跑（库内 eval_report 等全 0 行）⇒ 现有绿是「空态绿」
#   —— 空库下 selectPage 恒返空列表，「看得到自己的报告」这条能力从未被真实验证过。
#
# 授权：用户于 Q4 明确授权「同意授权，可以继续」（一次性夹具造数 + 自清理 + 突变反证）。
#
# 设计原则（照项目红线）：
#   ① fail-closed：缺任一前置（mysql 凭据 / 后端未起 / 登录口令）⇒ 报错 exit 1，不静默跳过；
#   ② 自清理：夹具 id 全部固定在 900000000000090xxx 段，清理按 id 精确限定，
#      且清理后回验行数必须回到开跑前基线（否则 exit 1，把残留暴露出来而不是留给下一次）；
#   ③ 不碰任何既有行：只 INSERT 自己的 id，只 DELETE 自己的 id；
#   ④ 断言下沉到 R.code / 业务字段，不拿 HTTP 200 当放行（JwtAuthFilter 全路径先拦，
#      401/404 都不能作为映射判据 —— 2026-10-15 已实证作废）。
#
# ⚠ 仅为模板，复制后务必本地复测，严禁直接粘贴到生产环境执行。
# =============================================================================
set -uo pipefail

DB_HOST=${DB_HOST:-127.0.0.1}
DB_PORT=${DB_PORT:-3306}
DB_USER=${DB_USER:-root}
DB_NAME=${DB_NAME:-yl_evaluation}
API=${API:-http://127.0.0.1:8080/api/v1}
BASE=${BASE:-${API%/api/v1}}

PASS=0
FAIL=0
pass() { PASS=$((PASS + 1)); echo "  ✅ $1"; }
fail() { FAIL=$((FAIL + 1)); echo "  ❌ $1"; }

die() { echo "FATAL: $1"; exit 1; }

# ---------- 0) 前置 fail-closed ----------
echo "== 0) 前置检查 =="
[ -n "${MYSQL_BIN:-}" ] || die "缺 MYSQL_BIN（缺凭据时清理段曾静默跳过 ⇒ SMOKE-R1 同源风险）"
[ -n "${MYSQL_PASSWORD:-}" ] || die "缺 MYSQL_PASSWORD"
[ -n "${DEV_LOGIN_PASSWORD:-}" ] || die "缺 DEV_LOGIN_PASSWORD（登录口令，勿内联硬编码）"
curl -s --noproxy '*' -m 8 "$BASE/actuator/health" 2>/dev/null | grep -q '"UP"' \
  || die "后端未就绪（$BASE，可用 BASE 环境变量覆盖）"

sql() { "$MYSQL_BIN" -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$MYSQL_PASSWORD" "$DB_NAME" \
          -N -B -e "$1" 2>&1 | grep -v "Using a password"; }

# 夹具 id 固定段（清理按此精确限定）
ELDER_ID=900000000000090001
ORDER_ID=900000000000090002
ORDER_ID2=900000000000090003   # 状态闸负例：报告 status=0（未发布）
REPORT_ID=900000000000090004   # status=1 已发布 → 老人应可见
REPORT_ID2=900000000000090005  # status=0 生成中 → 老人应不可见
REPORT_NO="D1FIX-REPORT-0001"
REPORT_NO2="D1FIX-REPORT-0002"
USER_ELDER=$(sql "SELECT id FROM sys_user WHERE username='elder_dev' LIMIT 1;")
[ -n "$USER_ELDER" ] || die "查不到 elder_dev 账号"
ORG_ID=$(sql "SELECT id FROM org WHERE deleted=0 LIMIT 1;")
[ -n "$ORG_ID" ] || die "查不到机构"
echo "  elder_dev user_id=$USER_ELDER  org_id=$ORG_ID"

# 开跑前基线（清理后必须回到这里）
BASE_REPORT=$(sql "SELECT COUNT(*) FROM eval_report WHERE deleted=0;")
BASE_ORDER=$(sql "SELECT COUNT(*) FROM eval_order WHERE deleted=0;")
BASE_ELDER=$(sql "SELECT COUNT(*) FROM elder WHERE deleted=0;")
echo "  基线：eval_report=$BASE_REPORT eval_order=$BASE_ORDER elder=$BASE_ELDER"

cleanup() {
  echo "== 清理夹具 =="
  sql "DELETE FROM eval_report WHERE id IN ($REPORT_ID,$REPORT_ID2);
       DELETE FROM eval_order  WHERE id IN ($ORDER_ID,$ORDER_ID2);
       DELETE FROM elder       WHERE id = $ELDER_ID;" >/dev/null
}
trap cleanup EXIT

# ---------- 1) 造数 ----------
echo "== 1) 造一次性夹具 =="
sql "INSERT INTO elder (id, elder_no, name_enc, org_id, user_id, status, deleted, created_at, updated_at)
     VALUES ($ELDER_ID, 'D1FIX-ELDER-0001', 'FIXTURE_NON_PII', $ORG_ID, $USER_ELDER, 1, 0, NOW(), NOW());
     INSERT INTO eval_order (id, order_no, elder_id, org_id, assessor_id, rule_version_id, status, deleted, created_at, updated_at)
     VALUES ($ORDER_ID,  'D1FIX-ORDER-0001', $ELDER_ID, $ORG_ID, $USER_ELDER, 1, 'PUBLISHED', 0, NOW(), NOW()),
            ($ORDER_ID2, 'D1FIX-ORDER-0002', $ELDER_ID, $ORG_ID, $USER_ELDER, 1, 'PUBLISHED', 0, NOW(), NOW());
     -- 注：eval_report 无 updated_at 列（库内实测，与 02_schema.sql 一致），只写 created_at
     INSERT INTO eval_report (id, report_no, order_id, elder_id, org_id, grade_code, total_score, verify_hash, status, published_at, deleted, created_at)
     VALUES ($REPORT_ID,  '$REPORT_NO',  $ORDER_ID,  $ELDER_ID, $ORG_ID, 'GRADE_MILD', 68.00, 'D1FIXHASH0001', 1, NOW(), 0, NOW()),
            ($REPORT_ID2, '$REPORT_NO2', $ORDER_ID2, $ELDER_ID, $ORG_ID, 'GRADE_MILD', 68.00, 'D1FIXHASH0002', 0, NULL,     0, NOW());" \
  | grep -i "error" && die "造数失败（见上）"
N_REPORT=$(sql "SELECT COUNT(*) FROM eval_report WHERE id IN ($REPORT_ID,$REPORT_ID2);")
[ "$N_REPORT" = "2" ] || die "造数未生效（期望 2 行，实际 $N_REPORT）"
pass "夹具入库 2 报告 / 2 评估单 / 1 老人"

# ---------- 2) ELDER 实跑非空路径 ----------
echo "== 2) ELDER 登录 → 实跑报告列表（非空路径）=="
RESP=$(curl -s --noproxy '*' -m 30 -X POST "$API/auth/login/ANDROID" \
        -H 'Content-Type: application/json' \
        -d "{\"username\":\"elder_dev\",\"password\":\"$DEV_LOGIN_PASSWORD\"}")
CODE=$(printf '%s' "$RESP" | grep -o '"code":[0-9]*' | head -1 | cut -d: -f2)
TOK=$(printf '%s' "$RESP" | grep -o '"accessToken":"[^"]*"' | head -1 | sed 's/.*:"//;s/"$//')
[ "$CODE" = "0" ] && pass "elder_dev 登录 code=0" || fail "elder_dev 登录 code=$CODE"
[ -n "$TOK" ] || die "未取到令牌，后续断言无意义"

LIST=$(curl -s --noproxy '*' -m 20 -H "Authorization: Bearer $TOK" "$API/evaluation/reports")
LCODE=$(printf '%s' "$LIST" | grep -o '"code":[0-9]*' | head -1 | cut -d: -f2)
TOTAL=$(printf '%s' "$LIST" | grep -o '"total":[0-9]*' | head -1 | cut -d: -f2)
HAS=$(printf '%s' "$LIST" | grep -c "$REPORT_NO")
echo "    返回：code=$LCODE total=${TOTAL:-?}"
[ "$LCODE" = "0" ] && pass "列表 code=0" || fail "列表 code=$LCODE"
if [ "${TOTAL:-0}" -ge 1 ] && [ "$HAS" -ge 1 ]; then
  pass "非空路径实跑通过：老人看得到本人已发布报告（total=${TOTAL}，命中 $REPORT_NO）"
else
  fail "非空路径未通过：total=${TOTAL:-0}、命中 $REPORT_NO 次数=$HAS（空态绿到此为止）"
fi

# ---------- 3) 状态闸负例：status=0 未发布报告不得出现 ----------
echo "== 3) 状态闸负例（status=0 生成中）=="
if printf '%s' "$LIST" | grep -q "$REPORT_NO2"; then
  fail "状态闸失效：未发布报告 $REPORT_NO2 出现在列表中"
else
  pass "状态闸有效：未发布报告 $REPORT_NO2 未泄漏"
fi

# ---------- 4) 清理 + 回验（trap 已注册；此处显式执行并回验）----------
cleanup
trap - EXIT
echo "== 4) 清理回验 =="
A_REPORT=$(sql "SELECT COUNT(*) FROM eval_report WHERE deleted=0;")
A_ORDER=$(sql "SELECT COUNT(*) FROM eval_order WHERE deleted=0;")
A_ELDER=$(sql "SELECT COUNT(*) FROM elder WHERE deleted=0;")
LEFT=$(sql "SELECT COUNT(*) FROM eval_report WHERE id IN ($REPORT_ID,$REPORT_ID2);")
[ "$LEFT" = "0" ] && pass "夹具行已清零" || fail "夹具残留 $LEFT 行（会污染下一次运行）"
[ "$A_REPORT" = "$BASE_REPORT" ] && [ "$A_ORDER" = "$BASE_ORDER" ] && [ "$A_ELDER" = "$BASE_ELDER" ] \
  && pass "库内行数回到基线（report=$A_REPORT order=$A_ORDER elder=$A_ELDER）" \
  || fail "未回到基线：report $BASE_REPORT→$A_REPORT / order $BASE_ORDER→$A_ORDER / elder $BASE_ELDER→$A_ELDER"

echo
echo "===== D-1 汇总：PASS=$PASS FAIL=$FAIL ====="
[ "$FAIL" -eq 0 ] || exit 1
