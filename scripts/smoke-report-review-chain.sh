#!/bin/sh
# =============================================================================
# 报告域 HTTP 冒烟（真实后端 + 真实 MySQL）：
#   评估提交 → 报告生成（含维度得分）→ 照护方案生成/确认 → PDF 生成/下载
#   → 复核发布状态机（互斥校验 / 驳回重提 / 作废）
#   → 档案域（D-2：建档加密 / 第三闸收口 / reveal 二次验证 + 留痕 / 未认证 401）
#
# 用途：验证「报告 PDF + 照护建议 + 复核发布状态机」在真实运行环境下闭环可用，
#       补 Mock 单测覆盖不到的真实缺陷（列约束 / 类型 / 映射 / 文件落盘）。
#
# CR-M2-002 起本脚本同时充当**鉴权收口的验收脚本**：
#   · 操作者一律取自登录态（请求体 operatorId 已移除，脚本故意仍带上以证明其无效）；
#   · 评估员令牌调用复核/发布必须被拒（即使伪造 operatorId 为机构管理员）；
#   · 作废属敏感点，须机构管理员令牌 + 第四闸二次验证凭据。
#
# ★ 判定口径（写断言前必读）：本项目业务异常一律 **HTTP 200 + 统一响应体**，
#   拒绝的证据是 `R.code != 0`（越权/不存在用 10004），**不是** HTTP 状态码。
#   二进制端点（如 PDF 下载）额外验 PDF 魔数，以证明拒绝没有退化为"返回了文件字节"。
#
# 前置：后端已启动（默认 http://127.0.0.1:8080），数据库已完成初始化，
#       且**已播种 dev 联调账号**（docker/mysql/init/04_seed_dev_accounts.sql）。
#       作废用例还需后端启动时配置 SECOND_VERIFY_DEV_CODE（未配置则二次验证一律拒绝）。
# 用法（**必需** 环境变量，缺则 fail-closed 退出，不再静默降级 —— 见 SMOKE-R1）：
#   DEV_LOGIN_PASSWORD=***                    # dev 账号口令（本机值见未跟踪文件，仓库内无明文）
#   MYSQL_BIN=/c/mysql/bin/mysql.exe MYSQL_PASSWORD=***   # 前清/后清与跨机构段必需
#   SECOND_VERIFY_DEV_CODE=***                # 第四闸（作废/解绑等敏感点）必需
#   EXPORT_DIR=<后端 yl.export.dir 同目录>     # B9 CSV 魔数/表头断言必需
#   BASE_URL=http://127.0.0.1:8080            # 可选，默认 8080
#   bash scripts/smoke-report-review-chain.sh
#
# 断言数：**随环境变量齐备度变化 —— 不存在「固定 N」，勿硬编码**（2026-10-16 订正）。
#   环境变量全齐备（MYSQL_BIN+MYSQL_PASSWORD+SECOND_VERIFY_DEV_CODE+EXPORT_DIR）时，
#   2026-10-15 实测 **130 通过 / 0 失败 / 0 跳过**（见文末各次实测记录）。
#   ★ 2026-10-10（SMOKE-R1 闭环）起 `MYSQL_BIN`+`MYSQL_PASSWORD` 由「可选」升为**必需**：
#     原先缺凭据时清理整段与跨机构段被**静默跳过**，既让断言数漂移，又把残留数据留给下一次运行
#     （实测造成 113/0/6 假红）。现在缺任一必需项直接 exit 1，不再有"半跑"状态。
#   · 唯一可剩余跳过项：D-3 B9 CSV（需 EXPORT_DIR 可读）。
# 2026-09-24 实测（带 MYSQL_BIN + MYSQL_PASSWORD，未设 SECOND_VERIFY_DEV_CODE）：34 通过 / 0 失败 / 1 跳过。
# 2026-09-26 D-2 新增「档案域」段（第 [7/7] 段）。
# 2026-09-28 D-3 新增「报表与监管域」段（第 [8/8] 段：B6/B7/B8/B9/B10 + R6 + 未认证 401 + 跨机构收口）。
# 2026-09-29 D-4 新增「家属域」段（第 [9/9] 段：绑定五态 + 家属读三端点 + elder↔user 绑定 + ★C1/C3 + authLogout 黑名单六步）。
#   · 需 04_seed_dev_accounts.sql 含 elder_dev/family_dev；需后端配置 SENSITIVE_FIELD_KEY（否则申请返回 40001）。
#   · 解绑「带凭据 → code=0」需设 SECOND_VERIFY_DEV_CODE。
# 2026-10-13 D4-R7 ★C3 声明翻转：ELDER E2E **已覆盖**报告读取（原「D4-R7 恒假」不再成立）——9.5b 段新增 5 条断言：
#   本人已发布报告 code=0 + status=1 / 他人或不存在 orderId → 10004 / 本人已作废报告 → 10004（状态闸 M3）/ 夹具还原。
#   ✅〔2026-10-16 订正〕断言总数**已实跑校准 = 124 通过 / 0 失败 / 0 跳过**
#     （2026-10-13，commit `d943992` 夹具订正后实测，**非推算**；原文「尚未实跑校准」已失效）。
# 2026-09-30 D-4a 收尾（R-1/R-2）：[9/9] 增 9.4b/9.6b/9.7b —— ★R-2 重新申请复用活性行（拒绝/解绑后 → code=0 且 status∈{0,1} 且 id 不变；status∈{0,1,2} 重复申请 → 10005）。
# 2026-10-10 P2-8-R12/SMOKE-R1：口令改由 DEV_LOGIN_PASSWORD 注入（明文全部出仓）；清理凭据 fail-closed；新增开跑前"前清"使套件幂等。
# 2026-10-15 rR1a6L B5-后端：9.5b 段内新增「本人报告列表」6 条断言（同一夹具，与详情状态闸同口径）：
#   GET /evaluation/reports → code=0 / total≥1 / 命中本人 reportNo / 最小字段集（无维度分与敏感列）/
#   status=2 时仍 code=0（空态不报错）/ VOID 报告不出现在列表。断言总数随环境变量变化，勿硬编码。
# =============================================================================

BASE=${BASE_URL:-http://127.0.0.1:8080}
API="$BASE/api/v1/evaluation"
AUTH_API="$BASE/api/v1/auth"
ELDER=${ELDER_ID:-1}
ORG=${ORG_ID:-1}

# 联调账号（04_seed_dev_accounts.sql）：评估员（录入人）与机构管理员（复核人/派单），二者必须不同
ASSESSOR_USER=${ASSESSOR_USER:-assessor_dev}
ADMIN_USER=${ADMIN_USER:-orgadmin_dev}
# D-4 家属域联调账号（04_seed_dev_accounts.sql）：老人本人（ELDER/scope=1）与家属（FAMILY/scope=5）
ELDER_USER=${ELDER_USER:-elder_dev}
FAMILY_USER=${FAMILY_USER:-family_dev}
ELDER_DEV_USER_ID=900000000000000003

# ★ 口令**绝不落脚本**（P2-8-R12：原写死的固定口令已随公开历史永久泄露，本轮已轮换）：
#   统一由环境变量 DEV_LOGIN_PASSWORD 注入（本机值见未跟踪文件，如 /tmp/d4-run/env.sh）。
#   四个 dev 账号共用同一口令、但散列**互不相同**；如需单独覆盖用 ASSESSOR_PWD/ADMIN_PWD/ELDER_PWD/FAMILY_PWD。
DEV_LOGIN_PASSWORD=${DEV_LOGIN_PASSWORD:-}
ASSESSOR_PWD=${ASSESSOR_PWD:-$DEV_LOGIN_PASSWORD}
ADMIN_PWD=${ADMIN_PWD:-$DEV_LOGIN_PASSWORD}
ELDER_PWD=${ELDER_PWD:-$DEV_LOGIN_PASSWORD}
FAMILY_PWD=${FAMILY_PWD:-$DEV_LOGIN_PASSWORD}
if [ -z "$ASSESSOR_PWD" ] || [ -z "$ADMIN_PWD" ] || [ -z "$ELDER_PWD" ] || [ -z "$FAMILY_PWD" ]; then
    echo "✗ 未提供 dev 口令（**fail-closed**）："
    echo "  请先 export DEV_LOGIN_PASSWORD=...（或分别设 ASSESSOR_PWD/ADMIN_PWD/ELDER_PWD/FAMILY_PWD）。"
    echo "  口令来源见 docker/mysql/init/04_seed_dev_accounts.sql 头部声明 —— 本仓库内**不含**明文口令。"
    exit 1
fi
# 第四闸二次验证口令（与后端 yl.security.second-verify.dev-code / SECOND_VERIFY_DEV_CODE 一致）
SECOND_VERIFY_CODE=${SECOND_VERIFY_DEV_CODE:-}

# ---- 清理凭据：**必需，缺则 fail-closed**（闭环 SMOKE-R1） ----
# 历史缺陷：缺 MYSQL_BIN/MYSQL_PASSWORD 时清理整段被静默跳过 ⇒ 残留 elder 的 user_id 绑定
#   ⇒ **下一次**运行 9.2 user-binding 撞 `elder.user_id` 唯一键 ⇒ 10005 ⇒ 下游 6 条断言级联假红。
#   即「只在空库上才绿」的验收脚本 = 假红发生器。故此处拒绝运行而非静默降级。
MYSQL_BIN=${MYSQL_BIN:-}
MYSQL_PASSWORD=${MYSQL_PASSWORD:-}
if [ -z "$MYSQL_BIN" ] || [ -z "$MYSQL_PASSWORD" ]; then
    echo "✗ 缺少 MYSQL_BIN / MYSQL_PASSWORD（**fail-closed**）："
    echo "  本脚本既是冒烟、也是「验收数据自清理 + 幂等」的门禁；缺凭据会导致："
    echo "    ① 本次不清理 → 污染**下一次**运行（SMOKE-R1 实证：113/0/6 假红）；"
    echo "    ② 「跨机构 reportNo 回归」段被跳过 → 断言数随环境漂移，掩盖真实回归。"
    echo "  用法：MYSQL_BIN=/c/mysql/bin/mysql.exe MYSQL_PASSWORD=*** bash scripts/smoke-report-review-chain.sh"
    exit 1
fi
MYSQL_CONN="-h${MYSQL_HOST:-127.0.0.1} -P${MYSQL_PORT:-3306} -u${MYSQL_USER:-root} -p${MYSQL_PASSWORD}"

# ---- 前清（幂等前提）：解除 dev 账号在 elder.user_id 上的历史绑定 ----
# 唯一键冲突的唯一来源（9.2 把建档老人绑为 elder_dev 本人）。残留成因：上次运行在 9.2 之前中断，
#   或（历史缺陷 SMOKE-R1）清理被跳过。做成"开跑前先清"后，本套件对上次残留**幂等**。
"$MYSQL_BIN" $MYSQL_CONN yl_evaluation >/dev/null 2>&1 <<'SQL'
UPDATE elder e JOIN sys_user u ON u.id = e.user_id SET e.user_id = NULL WHERE u.username LIKE '%\_dev';
SQL
if [ $? -ne 0 ]; then
    echo "✗ 前清失败：无法连接数据库或 SQL 执行失败（检查 MYSQL_BIN/MYSQL_PASSWORD/端口/库名 yl_evaluation）。"
    exit 1
fi
echo "前清完成：已解除 dev 账号的历史 elder.user_id 绑定（幂等前提）"

PASS=0
FAIL=0
SKIP=0

# 26 项指标 id:满分（来源：权威规则集，合计 90）
ITEM_SCORES="101:4 102:4 103:4 104:4 105:4 106:4 107:4 108:4 201:4 202:4 203:4 204:3 \
301:4 302:4 303:4 304:4 305:4 306:4 307:1 308:1 309:2 401:2 402:2 403:4 404:3 405:4"

pass() {
    echo "  ✓ $1"
    PASS=$((PASS + 1))
}

fail() {
    echo "  ✗ $1"
    FAIL=$((FAIL + 1))
}

skip() {
    echo "  ⊘ $1"
    SKIP=$((SKIP + 1))
}

check() {
    if [ "$2" = "$3" ]; then
        pass "$1 = $2"
    else
        fail "$1（期望 $3，实际 $2）"
    fi
}

check_ge() {
    if [ "$2" -ge "$3" ] 2>/dev/null; then
        pass "$1 = $2"
    else
        fail "$1（期望 ≥ $3，实际 $2）"
    fi
}

# ---------- 鉴权：账密登录换取双令牌（登录端点属"会话入口"，不继承既有登录态） ----------
login() { # $1=username $2=password → 输出 accessToken
    curl -s --noproxy '*' -m 30 -X POST "$AUTH_API/login/ANDROID" \
        -H 'Content-Type: application/json' \
        -d "{\"username\":\"$1\",\"password\":\"$2\"}"
}

token_of() {
    echo "$1" | grep -o '"accessToken":"[^"]*"' | head -1 | sed 's/.*:"//;s/"$//'
}

userid_of() {
    num_field "$1" userId
}

api_as() { # $1=token，其余同 curl
    token=$1
    shift
    curl -s --noproxy '*' -m 30 -H 'Content-Type: application/json' \
        -H "Authorization: Bearer $token" "$@"
}

api_h() { # 携带第四闸凭据的调用：$1=token $2=secondVerifyToken，其余同 curl
    token=$1
    sv=$2
    shift 2
    curl -s --noproxy '*' -m 30 -H 'Content-Type: application/json' \
        -H "Authorization: Bearer $token" -H "X-Second-Verify-Token: $sv" "$@"
}

# ★ 断言口径（越权用例必读）：本项目「业务异常」一律走 HTTP 200 + 统一响应体
# （GlobalExceptionHandler#handleBiz 无 @ResponseStatus，只有参数校验 400、未预期异常 500 才改状态码）。
# 因此**越权断言不能按 HTTP 状态码写**：
#   ① 按状态码写会得到假红（拒绝也是 200）；
#   ② 拿「200」当"放行"的证据更是假绿（拒绝同为 200，毫无区分力）。
# 正确口径：拒绝 → 响应体 R.code = 10004（用 code_of 判定）；放行 → 二进制端点须验 PDF 魔数。
is_pdf_file() { # $1=文件路径；PDF 魔数判定（证明"没拿到文件"这件事真的被守住）
    [ "$(head -c 4 "$1" 2>/dev/null)" = "%PDF" ]
}

# 取 JSON 中首个数值字段（避免依赖 jq）
num_field() {
    echo "$1" | grep -o "\"$2\":-\?[0-9]*" | head -1 | sed 's/.*://'
}

str_field() {
    echo "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed "s/.*\"$2\":\"//;s/\"$//"
}

bool_field() {
    echo "$1" | grep -o "\"$2\":\(true\|false\)" | head -1 | sed 's/.*://'
}

code_of() {
    echo "$1" | grep -o '"code":[0-9]*' | head -1 | sed 's/.*://'
}

answers_json() {
    body=""
    for pair in $ITEM_SCORES; do
        id=${pair%%:*}
        score=${pair##*:}
        if [ -n "$body" ]; then
            body="$body,"
        fi
        body="$body{\"itemId\":$id,\"score\":$score}"
    done
    echo "{\"answers\":[$body]}"
}

new_order() {
    api_as "$ASSESSOR_TOKEN" -X POST "$API/assessments" \
        -d "{\"elderId\":$ELDER,\"orgId\":$ORG,\"assessorId\":$ASSESSOR_ID}" \
        > /tmp/yl_smoke_create.json
    num_field "$(cat /tmp/yl_smoke_create.json)" orderId
}

submit_order() {
    order_id=$1
    api_as "$ASSESSOR_TOKEN" -X PUT "$API/assessments/$order_id/answers" -d "$(answers_json)" \
        > /tmp/yl_smoke_answers.json
    api_as "$ASSESSOR_TOKEN" -X POST "$API/assessments/$order_id/submit" -d '{}' \
        > /tmp/yl_smoke_submit.json
}

echo "=============================================================="
echo " 报告域 HTTP 冒烟（$BASE）"
echo "=============================================================="

echo ""
echo "[0/6] 账密登录取令牌（角色/权限/机构域随令牌下发）"
ASSESSOR_LOGIN=$(login "$ASSESSOR_USER" "$ASSESSOR_PWD")
ASSESSOR_TOKEN=$(token_of "$ASSESSOR_LOGIN")
ASSESSOR_ID=$(userid_of "$ASSESSOR_LOGIN")
ADMIN_LOGIN=$(login "$ADMIN_USER" "$ADMIN_PWD")
ADMIN_TOKEN=$(token_of "$ADMIN_LOGIN")
ADMIN_ID=$(userid_of "$ADMIN_LOGIN")

if [ -z "$ASSESSOR_TOKEN" ] || [ -z "$ADMIN_TOKEN" ]; then
    fail "联调账号登录失败（检查 04_seed_dev_accounts.sql 是否已执行）"
    echo "  评估员响应：$(echo "$ASSESSOR_LOGIN" | head -c 200)"
    exit 1
fi
pass "评估员登录（$ASSESSOR_USER → userId=$ASSESSOR_ID）"
pass "复核人登录（$ADMIN_USER → userId=$ADMIN_ID，机构管理员）"
check "录入人 ≠ 复核人（PRD §2.2 互斥前提）" "$([ "$ASSESSOR_ID" != "$ADMIN_ID" ] && echo ok)" "ok"

echo ""
echo "[1/6] 评估提交 → 报告生成（含 4 维度得分）"
ORDER=$(new_order)
if [ -n "$ORDER" ] && [ "$ORDER" -gt 0 ] 2>/dev/null; then
    pass "创建评估单（assessorId=$ASSESSOR_ID） = $ORDER"
else
    fail "创建评估单失败：$(cat /tmp/yl_smoke_create.json)"
    echo "=============================================================="
    echo " 结果：通过 $PASS 项，失败 $FAIL 项"
    echo "=============================================================="
    exit 1
fi

submit_order "$ORDER"
check "提交后评估单状态" "$(str_field "$(cat /tmp/yl_smoke_submit.json)" status)" "SUBMITTED"
check "终判等级（满分 90 → GRADE_INTACT）" "$(str_field "$(cat /tmp/yl_smoke_submit.json)" gradeCode)" "GRADE_INTACT"

api_as "$ASSESSOR_TOKEN" -X POST "$API/assessments/$ORDER/report" > /tmp/yl_smoke_report.json
REPORT=$(cat /tmp/yl_smoke_report.json)
REPORT_NO=$(str_field "$REPORT" reportNo)
DIM_COUNT=$(echo "$REPORT" | grep -o '"dimensionCode":"D[0-9]"' | wc -l | tr -d ' ')
check "报告维度得分条数（雷达图数据源）" "$DIM_COUNT" "4"
check "报告内容摘要长度（SHA-256）" "$(echo "$REPORT" | grep -o '"verifyHash":"[0-9a-f]*"' | head -1 | sed 's/.*:"//;s/"//' | wc -c | tr -d ' ')" "65"
check "报告生成态（0=生成中，发布后才有效）" "$(str_field "$REPORT" status)" "0"

echo ""
echo "[2/6] 照护方案：按等级匹配模板 → 管理员确认（建议人/确认人均取登录态）"
api_as "$ASSESSOR_TOKEN" -X POST "$API/assessments/$ORDER/care-plan" > /tmp/yl_smoke_care.json
CARE=$(cat /tmp/yl_smoke_care.json)
check_ge "照护建议条目数（无模板时降级通用建议）" "$(echo "$CARE" | grep -o '"title":"[^"]*"' | wc -l | tr -d ' ')" "1"
check "方案初始状态（0=待确认）" "$(num_field "$CARE" status)" "0"
check "建议人取登录态（= 评估员）" "$(num_field "$CARE" suggestBy)" "$ASSESSOR_ID"
api_as "$ADMIN_TOKEN" -X POST "$API/assessments/$ORDER/care-plan/confirm" > /tmp/yl_smoke_care2.json
check "确认后状态（1=已确认）" "$(num_field "$(cat /tmp/yl_smoke_care2.json)" status)" "1"
api_as "$ASSESSOR_TOKEN" -X POST "$API/assessments/$ORDER/care-plan/confirm" > /tmp/yl_smoke_care3.json
if [ "$(code_of "$(cat /tmp/yl_smoke_care3.json)")" != "0" ]; then
    pass "评估员自确认被拒绝（确认须 care:plan:edit，评估员仅 suggest）"
else
    fail "评估员自确认未被拒绝（双人闭环失效）"
fi

echo ""
echo "[3/6] 报告 PDF 生成与下载"
api_as "$ASSESSOR_TOKEN" -X POST "$API/assessments/$ORDER/report/pdf" > /tmp/yl_smoke_pdf.json
PDF=$(cat /tmp/yl_smoke_pdf.json)
check_ge "PDF 字节数" "$(num_field "$PDF" sizeBytes)" "1000"
check "中文字体嵌入（Windows 环境期望 true）" "$(bool_field "$PDF" chineseEmbedded)" "true"
PDF_HEAD=$(api_as "$ASSESSOR_TOKEN" "$API/reports/$REPORT_NO/pdf" | head -c 4)
check "下载 PDF 魔数" "$PDF_HEAD" "%PDF"

# ---- CR-M2-006 回归：报告 PDF 下载必须过第二闸 + 第三闸 ----
# 缺陷史：ReportController 无 @RequiresPermission（第二闸缺失）且 ReportPdfService.read() 零 DB 查询
#         → 数据域拦截器（只作用于 SQL）在文件路径上完全失效 → 任何已登录用户知道 reportNo 即可跨机构下载全文。
# 修复：① 下载端点补 report:view；② read() 先按 report_no 反查（查不到统一 10004）。
# 判定口径见上方「★ 断言口径」：业务拒绝 = R.code 10004，放行 = PDF 魔数。
# 断言①：无归属 reportNo 必须被拒，且**不得**返回文件字节
api_as "$ASSESSOR_TOKEN" "$API/reports/RP-CR-M2-006-ORPHAN/pdf" > /tmp/yl_smoke_orphan.out
check "无归属 reportNo 下载被拒（业务码 10004）" "$(code_of "$(cat /tmp/yl_smoke_orphan.out)")" "10004"
if is_pdf_file /tmp/yl_smoke_orphan.out; then
    fail "无归属 reportNo 泄露了 PDF 字节（响应体是 %PDF- 开头，说明拒绝逻辑未生效）"
else
    pass "无归属 reportNo 未泄露文件字节（响应体非 PDF 魔数）"
fi

# 断言②：跨机构（报告改挂 org_id=999，评估员属 org_id=$ORG）下载必须被数据域拦下；同资源走 DB 的路径作对照
if [ -n "$MYSQL_BIN" ] && [ -n "$MYSQL_PASSWORD" ]; then
    CONN_CR="-h${MYSQL_HOST:-127.0.0.1} -P${MYSQL_PORT:-3306} -u${MYSQL_USER:-root} -p${MYSQL_PASSWORD}"
    "$MYSQL_BIN" $CONN_CR -N -e \
        "UPDATE yl_evaluation.eval_report SET org_id=999 WHERE report_no='$REPORT_NO';" >/dev/null 2>&1
    api_as "$ASSESSOR_TOKEN" "$API/reports/$REPORT_NO/pdf" > /tmp/yl_smoke_cross.out
    CROSS_BODY=$(api_as "$ASSESSOR_TOKEN" "$API/assessments/$ORDER/report")
    "$MYSQL_BIN" $CONN_CR -N -e \
        "UPDATE yl_evaluation.eval_report SET org_id=$ORG WHERE report_no='$REPORT_NO';" >/dev/null 2>&1
    check "跨机构 reportNo 下载被拒（业务码 10004）" "$(code_of "$(cat /tmp/yl_smoke_cross.out)")" "10004"
    if is_pdf_file /tmp/yl_smoke_cross.out; then
        fail "跨机构 reportNo 泄露了 PDF 字节（数据域在文件路径上失效！）"
    else
        pass "跨机构 reportNo 未泄露文件字节（响应体非 PDF 魔数）"
    fi
    if [ "$(code_of "$CROSS_BODY")" != "0" ]; then
        pass "同资源对照：DB 路径亦被数据域拦（业务码 $(code_of "$CROSS_BODY")）"
    else
        fail "同资源对照：DB 路径未被数据域拦（数据域配置可能失效）"
    fi
    # 还原后必须仍可正常下载（证明上面的拒绝来自数据域，而非把功能改坏）；
    # 这里验 PDF 魔数而非 HTTP 200 —— 200 在本项目对成功/失败都会出现，不具区分力。
    api_as "$ASSESSOR_TOKEN" "$API/reports/$REPORT_NO/pdf" > /tmp/yl_smoke_restored.pdf
    check "还原归属后可正常下载（PDF 魔数 %PDF）" "$(head -c 4 /tmp/yl_smoke_restored.pdf)" "%PDF"
else
    skip "跨机构 reportNo 下载回归（需 MYSQL_BIN + MYSQL_PASSWORD 以临时改挂机构）"
fi

echo ""
echo "[4/6] 复核发布状态机 + 鉴权收口验收（CR-M2-002）"
# 验收①：评估员令牌**即使伪造请求体 operatorId 为机构管理员**也必须被拒（第二闸权限码）
api_as "$ASSESSOR_TOKEN" -X POST "$API/assessments/$ORDER/review/publish" \
    -d "{\"operatorId\":$ADMIN_ID,\"opinion\":\"越权发布尝试\"}" > /tmp/yl_smoke_self.json
if [ "$(code_of "$(cat /tmp/yl_smoke_self.json)")" != "0" ]; then
    pass "评估员令牌伪造 operatorId 发布被拒（越权收口生效）"
else
    fail "评估员令牌成功发布（越权漏洞未收口！）"
fi

# 验收②：机构管理员令牌发布，复核人必须取自登录态
api_as "$ADMIN_TOKEN" -X POST "$API/assessments/$ORDER/review/publish" \
    -d '{"opinion":"材料齐全，同意发布"}' > /tmp/yl_smoke_pub.json
check "复核发布后状态" "$(str_field "$(cat /tmp/yl_smoke_pub.json)" status)" "PUBLISHED"
check "发布后报告状态（1=有效）" "$(str_field "$(api_as "$ADMIN_TOKEN" "$API/assessments/$ORDER/report")" status)" "1"
api_as "$ADMIN_TOKEN" -X GET "$API/assessments/$ORDER/review-logs" > /tmp/yl_smoke_logs.json
LOGS=$(cat /tmp/yl_smoke_logs.json)
check_ge "流转日志条数（SUBMIT + PUBLISH）" "$(echo "$LOGS" | grep -o '"action":"[A-Z]*"' | wc -l | tr -d ' ')" "2"
# 验收②：发布流水中的操作人必须是登录态本人（评估单响应体不含 reviewerId，故以审计链为准）
check_ge "发布流水操作人取自登录态（= 机构管理员 $ADMIN_ID）" "$(echo "$LOGS" | grep -o "\"operatorId\":$ADMIN_ID" | wc -l | tr -d ' ')" "1"

echo ""
echo "[5/6] 驳回 → 修改重提（PRD §4.3 复核驳回）"
ORDER2=$(new_order)
submit_order "$ORDER2"
api_as "$ASSESSOR_TOKEN" -X POST "$API/assessments/$ORDER2/report" > /dev/null
api_as "$ADMIN_TOKEN" -X POST "$API/assessments/$ORDER2/review/reject" -d '{}' \
    > /tmp/yl_smoke_reject_noopinion.json
if [ "$(code_of "$(cat /tmp/yl_smoke_reject_noopinion.json)")" != "0" ]; then
    pass "驳回未填意见被拒绝（须保留意见）"
else
    fail "驳回未填意见未被拒绝"
fi
api_as "$ADMIN_TOKEN" -X POST "$API/assessments/$ORDER2/review/reject" \
    -d '{"opinion":"第 12 项留证缺失，请补充后重提"}' > /tmp/yl_smoke_reject.json
check "驳回后状态" "$(str_field "$(cat /tmp/yl_smoke_reject.json)" status)" "REJECTED"
submit_order "$ORDER2"
check "驳回后重新提交（可修改重提）" "$(str_field "$(cat /tmp/yl_smoke_submit.json)" status)" "SUBMITTED"
api_as "$ASSESSOR_TOKEN" -X POST "$API/assessments/$ORDER2/report" > /tmp/yl_smoke_report2.json
check_ge "重提后报告可重新生成" "$(num_field "$(cat /tmp/yl_smoke_report2.json)" reportId)" "1"
api_as "$ADMIN_TOKEN" -X POST "$API/assessments/$ORDER2/review/publish" \
    -d '{"opinion":"补充材料已核，发布"}' > /tmp/yl_smoke_pub2.json
check "重提后复核发布" "$(str_field "$(cat /tmp/yl_smoke_pub2.json)" status)" "PUBLISHED"

echo ""
echo "[6/6] 作废（敏感点：第四闸二次验证 + 留痕）"
ORDER3=$(new_order)
submit_order "$ORDER3"
api_as "$ASSESSOR_TOKEN" -X POST "$API/assessments/$ORDER3/report" > /dev/null

# 6.1 缺第四闸凭据必须被拒（证明敏感点未被架空）
api_as "$ADMIN_TOKEN" -X POST "$API/assessments/$ORDER3/review/void" \
    -d '{"opinion":"缺二次验证凭据"}' > /tmp/yl_smoke_void_nosv.json
if [ "$(code_of "$(cat /tmp/yl_smoke_void_nosv.json)")" != "0" ]; then
    pass "作废缺二次验证凭据被拒绝（第四闸生效）"
else
    fail "作废未校验二次验证（敏感点被架空！）"
fi

# 6.2 换取一次性凭据后作废
if [ -n "$SECOND_VERIFY_CODE" ]; then
    SV_JSON=$(api_as "$ADMIN_TOKEN" -X POST "$AUTH_API/second-verify" \
        -d "{\"method\":\"PHONE\",\"credential\":\"$SECOND_VERIFY_CODE\"}")
    SV_TOKEN=$(str_field "$SV_JSON" token)
    if [ -z "$SV_TOKEN" ]; then
        fail "二次验证换凭据失败：$(echo "$SV_JSON" | head -c 200)"
    else
        pass "二次验证换取一次性凭据"
        api_h "$ADMIN_TOKEN" "$SV_TOKEN" -X POST "$API/assessments/$ORDER3/review/void" \
            -d '{"opinion":"评估对象信息有误，作废重评"}' > /tmp/yl_smoke_void.json
        check "作废后评估单状态" "$(str_field "$(cat /tmp/yl_smoke_void.json)" status)" "VOID"
        check "作废后报告状态（2=已作废，扫码须提示失效）" "$(str_field "$(api_as "$ADMIN_TOKEN" "$API/assessments/$ORDER3/report")" status)" "2"
        # 凭据一次性：重复使用须失效
        api_h "$ADMIN_TOKEN" "$SV_TOKEN" -X POST "$API/assessments/$ORDER3/review/void" \
            -d '{"opinion":"重复使用凭据"}' > /tmp/yl_smoke_void_replay.json
        if [ "$(code_of "$(cat /tmp/yl_smoke_void_replay.json)")" != "0" ]; then
            pass "二次验证凭据一次性（重放被拒绝）"
        else
            fail "二次验证凭据可重放（第四闸失效）"
        fi
    fi
else
    skip "作废全链路（未设 SECOND_VERIFY_DEV_CODE；后端须以同一值配置 yl.security.second-verify.dev-code）"
fi

echo ""
echo "[7/7] 档案域（D-2：建档加密 / 第三闸收口 / reveal 二次验证 + 留痕 / 未认证 401）"
# 说明：本段验证 D-2 档案域四端点（elderCreate/elderList/elderDetail/dataReveal）在真实环境闭环。
# 判定口径同上：**全部看 R.code（业务失败恒为 HTTP 200）**；唯一例外是「未携带凭据」——CR-M2-007
# 修复后未认证属框架层，HTTP 401 + R.code=10002（**必须同时校验状态码与业务码**）。
ELDER_API="$BASE/api/v1/elders"
REVEAL_API="$BASE/api/v1/security/reveal"
ELDER_CONN=""
if [ -n "$MYSQL_BIN" ] && [ -n "$MYSQL_PASSWORD" ]; then
    ELDER_CONN="-h${MYSQL_HOST:-127.0.0.1} -P${MYSQL_PORT:-3306} -u${MYSQL_USER:-root} -p${MYSQL_PASSWORD}"
fi
IDEMP_KEY="smoke-elder-$$-$(date +%s)"
ID_CARD="53010219900307$(( (RANDOM % 9000) + 1000 ))"

# 7.1 建档（明文提交 → 服务端加密；Idempotency-Key 必填）
CREATE_RESP=$(api_as "$ASSESSOR_TOKEN" -X POST "$ELDER_API" \
    -H "Idempotency-Key: $IDEMP_KEY" \
    -d "{\"name\":\"张伟\",\"idCardNo\":\"$ID_CARD\",\"gender\":1,\"birthDate\":\"1990-03-07\",\"phone\":\"13812345678\",\"address\":\"云南省昆明市五华区\",\"orgId\":$ORG}")
ELDER_ID=$(num_field "$CREATE_RESP" id)
if [ "$(code_of "$CREATE_RESP")" = "0" ] && [ -n "$ELDER_ID" ]; then
    pass "建档成功（code=0，elderId=$ELDER_ID）"
else
    fail "建档失败：$(echo "$CREATE_RESP" | head -c 300)"
fi
check "建档返回姓名已脱敏（张*）" "$(str_field "$CREATE_RESP" name)" "张*"
check "建档返回身份证掩码（前4后4）" "$(str_field "$CREATE_RESP" idCardMask)" "$(echo "$ID_CARD" | sed 's/^\(....\).*\(....\)$/\1**********\2/')"

# 7.2 列表 / 详情（第三闸按机构收口）
LIST_RESP=$(api_as "$ASSESSOR_TOKEN" -X GET "$ELDER_API?pageNum=1&pageSize=5")
check "列表可读本机构档案（code=0）" "$(code_of "$LIST_RESP")" "0"
DETAIL_RESP=$(api_as "$ASSESSOR_TOKEN" -X GET "$ELDER_API/$ELDER_ID")
check "详情返回脱敏姓名" "$(str_field "$DETAIL_RESP" name)" "张*"

# 7.3 reveal：缺二次验证凭据 → 10008；带令牌 → 明文 + 留痕
NO_SV=$(api_as "$ADMIN_TOKEN" -X POST "$REVEAL_API" -H "Idempotency-Key: $IDEMP_KEY-sv0" \
    -d "{\"bizType\":\"ELDER\",\"bizId\":$ELDER_ID,\"fields\":[\"name\",\"idCardNo\"]}")
check "reveal 缺二次验证凭据（业务码 10008）" "$(code_of "$NO_SV")" "10008"
if [ -n "$SECOND_VERIFY_CODE" ]; then
    RV_SV_JSON=$(api_as "$ADMIN_TOKEN" -X POST "$AUTH_API/second-verify" \
        -d "{\"method\":\"PHONE\",\"credential\":\"$SECOND_VERIFY_CODE\"}")
    RV_SV_TOKEN=$(str_field "$RV_SV_JSON" token)
    if [ -z "$RV_SV_TOKEN" ]; then
        fail "reveal 二次验证换凭据失败：$(echo "$RV_SV_JSON" | head -c 200)"
    else
        RV=$(api_h "$ADMIN_TOKEN" "$RV_SV_TOKEN" -X POST "$REVEAL_API" -H "Idempotency-Key: $IDEMP_KEY-rv1" \
            -d "{\"bizType\":\"ELDER\",\"bizId\":$ELDER_ID,\"fields\":[\"name\",\"idCardNo\",\"address\"]}")
        check "reveal 带令牌返回明文姓名" "$(str_field "$RV" name)" "张伟"
        check "reveal 带令牌返回明文身份证" "$(str_field "$RV" idCardNo)" "$ID_CARD"
        check "reveal 带令牌返回明文住址" "$(str_field "$RV" address)" "云南省昆明市五华区"
        if [ -n "$ELDER_CONN" ]; then
            AUDIT_ROWS=$("$MYSQL_BIN" $ELDER_CONN -N -e \
                "SELECT COUNT(*) FROM yl_evaluation.audit_log WHERE action='DATA_REVEAL' AND biz_type='ELDER' AND \`sensitive\`=1;")
            check_ge "audit_log 留痕（DATA_REVEAL/ELDER/sensitive=1）行数" "$AUDIT_ROWS" "1"
            ENC_FLAGS=$("$MYSQL_BIN" $ELDER_CONN -N -e \
                "SELECT (name_enc <> '张伟'), (id_card_enc <> '$ID_CARD'), (CHAR_LENGTH(name_hash) = 64) FROM yl_evaluation.elder WHERE id=$ELDER_ID;")
            check "库内姓名/身份证为密文且摘要齐备（1 1 1）" "$(echo $ENC_FLAGS)" "1 1 1"
        else
            skip "库内密文/留痕核对（需 MYSQL_BIN + MYSQL_PASSWORD）"
        fi
    fi
else
    skip "reveal 带令牌全链路（未设 SECOND_VERIFY_DEV_CODE；后端须以同一值配置 yl.security.second-verify.dev-code）"
fi

# 7.4 未携带凭据 → HTTP 401 + R.code=10002（CR-M2-007 修复后的新口径）
NOAUTH_CODE=$(curl -s --noproxy '*' -m 30 -o /tmp/yl_smoke_noauth.json -w '%{http_code}' \
    -X GET "$ELDER_API?pageNum=1&pageSize=1")
check "未携带凭据访问受保护端点 HTTP 状态码（CR-M2-007）" "$NOAUTH_CODE" "401"
check "未携带凭据响应体业务码 R.code=10002" "$(code_of "$(cat /tmp/yl_smoke_noauth.json)")" "10002"

# 7.5 越界 / 不存在详情 → 10004（同码，不泄露存在性）
CROSS_MISSING=$(api_as "$ASSESSOR_TOKEN" -X GET "$ELDER_API/999000000000000999")
check "越界/不存在详情（业务码 10004）" "$(code_of "$CROSS_MISSING")" "10004"
if [ -n "$ELDER_CONN" ]; then
    "$MYSQL_BIN" $ELDER_CONN -N -e "UPDATE yl_evaluation.elder SET org_id=999 WHERE id=$ELDER_ID;" >/dev/null 2>&1
    CROSS_DETAIL=$(api_as "$ASSESSOR_TOKEN" -X GET "$ELDER_API/$ELDER_ID")
    "$MYSQL_BIN" $ELDER_CONN -N -e "UPDATE yl_evaluation.elder SET org_id=$ORG WHERE id=$ELDER_ID;" >/dev/null 2>&1
    check "跨机构详情被第三闸拦下（业务码 10004）" "$(code_of "$CROSS_DETAIL")" "10004"
else
    skip "跨机构详情回归（需 MYSQL_BIN + MYSQL_PASSWORD 以临时改挂机构）"
fi

echo ""
echo "[8/8] 报表与监管域（D-3：B6/B7/B8/B9/B10 + R6 + 未认证 401 + 跨机构收口）"
# 判定口径同上：业务失败一律 HTTP 200 + R.code；未认证 = HTTP 401 + R.code=10002；CSV 产物验魔数（BOM）。
# 断言数随环境变量变化（MYSQL_BIN/MYSQL_PASSWORD、SECOND_VERIFY_DEV_CODE、EXPORT_DIR），勿硬编码总数。
TASK_API="$BASE/api/v1/evaluation/tasks"
RULE_API="$BASE/api/v1/rule/mapping"
VERIFY_API="$BASE/api/v1/reports/verify"
ORG_API="$BASE/api/v1/account/orgs"
EXPORT_API="$BASE/api/v1/supervise/exports"
SREPORT_API="$BASE/api/v1/supervise/report"
D3_CONN=""
if [ -n "$MYSQL_BIN" ] && [ -n "$MYSQL_PASSWORD" ]; then
    D3_CONN="-h${MYSQL_HOST:-127.0.0.1} -P${MYSQL_PORT:-3306} -u${MYSQL_USER:-root} -p${MYSQL_PASSWORD}"
fi
D3_STAMP="$$-$(date +%s)"
EXP_DIR=${EXPORT_DIR:-}

# 公式注入样本还原兜底（QA 加固）：B9 注入段会把真实评估单 order_no 临时改为 '=1+1'，
# 原实现仅在正常路径还原；若脚本被中断（断网 502 / Ctrl-C / TERM）会永久留下脏单号。
# 此处登记 trap，保证 EXIT/INT/TERM 时把 order_no 修回（幂等：未注入或已删除则 no-op）。
B9_INJ_ORDER=""
B9_INJ_ORIG=""
restore_injection_sample() {
    if [ -n "$B9_INJ_ORDER" ] && [ -n "$B9_INJ_ORIG" ] && [ -n "$D3_CONN" ]; then
        "$MYSQL_BIN" $D3_CONN -N -e \
            "UPDATE yl_evaluation.eval_order SET order_no='$B9_INJ_ORIG' WHERE id=$B9_INJ_ORDER;" \
            >/dev/null 2>&1
    fi
}
trap restore_injection_sample EXIT INT TERM

# ---- 8.1 B6 评估任务列表 ----
B6=$(api_as "$ASSESSOR_TOKEN" -X GET "$TASK_API?pageNum=1&pageSize=5")
check "B6 评估任务列表（code=0）" "$(code_of "$B6")" "0"

# ---- 8.2 B7 规则映射（缺参 10001 / 有效参 code=0） ----
B7_MISS=$(api_as "$ASSESSOR_TOKEN" -X GET "$RULE_API")
check "B7 缺 ruleVersionId（业务码 10001）" "$(code_of "$B7_MISS")" "10001"
RULE_VER=""
if [ -n "$D3_CONN" ]; then
    RULE_VER=$("$MYSQL_BIN" $D3_CONN -N -e "SELECT id FROM yl_evaluation.gb_rule_version LIMIT 1;" 2>/dev/null)
fi
if [ -n "$RULE_VER" ]; then
    B7=$(api_as "$ASSESSOR_TOKEN" -X GET "$RULE_API?ruleVersionId=$RULE_VER")
    check "B7 规则映射（code=0）" "$(code_of "$B7")" "0"
else
    skip "B7 规则映射（需 MYSQL_BIN + MYSQL_PASSWORD 取有效 ruleVersionId）"
fi

# ---- 8.3 B8 二维码验真（public，最小返回面） ----
if [ -n "$REPORT_NO" ]; then
    B8_OK=$(curl -s --noproxy '*' -m 30 "$VERIFY_API?code=$REPORT_NO")
    check "B8 验真已发布报告（code=0）" "$(code_of "$B8_OK")" "0"
    check "B8 验真已发布报告（valid=true）" "$(bool_field "$B8_OK" valid)" "true"
    if echo "$B8_OK" | grep -qE '"name"|"idCard"|"phone"'; then
        fail "B8 验真返回体泄露了个人信息字段（违反裁决 4 最小返回面）"
    else
        pass "B8 验真返回最小面（无 name/idCard/phone）"
    fi
else
    skip "B8 验真已发布报告（需 REPORT_NO，另行准备已发布报告）"
fi
B8_MISS=$(curl -s --noproxy '*' -m 30 "$VERIFY_API?code=RP-NOT-EXIST-$D3_STAMP")
check "B8 验真不存在报告（code=0）" "$(code_of "$B8_MISS")" "0"
check "B8 验真不存在报告（valid=false，不泄露存在性）" "$(bool_field "$B8_MISS" valid)" "false"

# ---- 8.4 R6 机构列表/详情（第三闸按机构收口） ----
R6=$(api_as "$ADMIN_TOKEN" -X GET "$ORG_API?pageNum=1&pageSize=5")
check "R6 机构列表（code=0）" "$(code_of "$R6")" "0"
R6_DETAIL=$(api_as "$ADMIN_TOKEN" -X GET "$ORG_API/$ORG")
check "R6 本机构详情（code=0）" "$(code_of "$R6_DETAIL")" "0"
R6_MISS=$(api_as "$ADMIN_TOKEN" -X GET "$ORG_API/999000000000000999")
check "R6 越界/不存在机构详情（业务码 10004）" "$(code_of "$R6_MISS")" "10004"

# ---- 8.5 B9 CSV 导出（第二/四闸联动 + 魔数 + 注入转义 + 落库机构锚） ----
B9_NOSV=$(api_as "$ADMIN_TOKEN" -X POST "$EXPORT_API" -H "Idempotency-Key: d3-nosv-$D3_STAMP" \
    -d '{"exportType":"EVAL_LIST","format":"CSV"}')
check "B9 缺二次验证凭据（业务码 10008）" "$(code_of "$B9_NOSV")" "10008"
B9_NOTYPE=$(api_as "$ADMIN_TOKEN" -X POST "$EXPORT_API" -H "Idempotency-Key: d3-notype-$D3_STAMP" \
    -d '{}')
check "B9 缺 format/exportType 参数校验（业务码 10001）" "$(code_of "$B9_NOTYPE")" "10001"
if [ -n "$SECOND_VERIFY_CODE" ]; then
    # ★ 第四闸凭据为**一次性**：每个敏感调用前各自换新凭据（同一凭据重放必被拒 → 10008）。
    #   故每次调用都重新 POST /auth/second-verify，绝不复用同一 token 连打两次。
    SV_XLSX_JSON=$(api_as "$ADMIN_TOKEN" -X POST "$AUTH_API/second-verify" \
        -d "{\"method\":\"PHONE\",\"credential\":\"$SECOND_VERIFY_CODE\"}")
    SV_XLSX=$(str_field "$SV_XLSX_JSON" token)
    B9_XLSX=$(api_h "$ADMIN_TOKEN" "$SV_XLSX" -X POST "$EXPORT_API" -H "Idempotency-Key: d3-xlsx-$D3_STAMP" \
        -d '{"exportType":"EVAL_LIST","format":"XLSX"}')
    check "B9 format=XLSX 暂不支持（业务码 10001，裁决 2）" "$(code_of "$B9_XLSX")" "10001"
    # 公式注入样本（§2.4）：CSV 单元格来自真实数据行，故把本机构一张评估单的编号临时改成以
    # `=` 开头（真实产物单元格来源），导出后该单元格须以 `'` 前缀；核对完立即还原。
    B9_INJ_ORIG=""
    B9_INJ_APPLIED=""
    if [ -n "$D3_CONN" ] && [ -n "$ORDER" ]; then
        B9_INJ_ORIG=$("$MYSQL_BIN" $D3_CONN -N -e "SELECT order_no FROM yl_evaluation.eval_order WHERE id=$ORDER;")
        if [ -n "$B9_INJ_ORIG" ]; then
            "$MYSQL_BIN" $D3_CONN -N -e "UPDATE yl_evaluation.eval_order SET order_no='=1+1' WHERE id=$ORDER;" >/dev/null 2>&1
            B9_INJ_APPLIED=1
            B9_INJ_ORDER=$ORDER
        fi
    fi
    SV_CSV_JSON=$(api_as "$ADMIN_TOKEN" -X POST "$AUTH_API/second-verify" \
        -d "{\"method\":\"PHONE\",\"credential\":\"$SECOND_VERIFY_CODE\"}")
    SV_CSV=$(str_field "$SV_CSV_JSON" token)
    B9=$(api_h "$ADMIN_TOKEN" "$SV_CSV" -X POST "$EXPORT_API" -H "Idempotency-Key: d3-csv-$D3_STAMP" \
        -d '{"exportType":"EVAL_LIST","format":"CSV","queryParams":{"note":"=SUM(1)"}}')
    check "B9 CSV 导出（code=0）" "$(code_of "$B9")" "0"
    B9_TASKNO=$(str_field "$B9" taskNo)
    check "B9 存储键名口径（export/<taskNo>.csv）" "$(str_field "$B9" fileCosKey)" "export/$B9_TASKNO.csv"
    if [ -n "$D3_CONN" ] && [ -n "$B9_TASKNO" ]; then
        B9_ORG=$("$MYSQL_BIN" $D3_CONN -N -e \
            "SELECT org_id FROM yl_evaluation.export_task WHERE task_no='$B9_TASKNO';")
        check "B9 落库机构锚 export_task.org_id 非空（裁决 1-A）" "$([ -n "$B9_ORG" ] && [ "$B9_ORG" != "NULL" ] && echo ok)" "ok"
    else
        skip "B9 落库机构锚核对（需 MYSQL_BIN + MYSQL_PASSWORD）"
    fi
    if [ -n "$EXP_DIR" ] && [ -n "$B9_TASKNO" ] && [ -f "$EXP_DIR/export/$B9_TASKNO.csv" ]; then
        B9_MAGIC=$(head -c 3 "$EXP_DIR/export/$B9_TASKNO.csv" | od -An -tx1 | tr -d ' \n')
        check "B9 CSV 产物魔数（UTF-8 BOM efbbbf）" "$B9_MAGIC" "efbbbf"
        # 首行表头：去掉 BOM（首 3 字节）与行尾 CR，用 case 前缀匹配（勿用 cut -c，字节/字符口径不可靠）
        B9_HEAD=$(head -1 "$EXP_DIR/export/$B9_TASKNO.csv" | sed 's/^\xEF\xBB\xBF//; s/\r$//')
        case "$B9_HEAD" in
            评估单号,能力等级,老人姓名*)
                pass "B9 CSV 首行表头（评估单号,能力等级,老人姓名,...）"
                ;;
            *)
                fail "B9 CSV 首行表头异常：$B9_HEAD"
                ;;
        esac
        if [ -n "$B9_INJ_APPLIED" ]; then
            if grep -q "'=1+1" "$EXP_DIR/export/$B9_TASKNO.csv"; then
                pass "B9 CSV 公式注入转义（单元格以 ' 前缀，§2.4）"
            else
                fail "B9 CSV 公式注入未转义（CSV 内未见 '=1+1 单元格）"
            fi
        else
            skip "B9 CSV 公式注入转义（需 MYSQL_BIN + MYSQL_PASSWORD 注入样本）"
        fi
    else
        skip "B9 CSV 魔数/表头/注入转义（需设 EXPORT_DIR 指向后端 yl.export.dir，且该目录可读）"
    fi
    if [ -n "$B9_INJ_APPLIED" ]; then
        "$MYSQL_BIN" $D3_CONN -N -e "UPDATE yl_evaluation.eval_order SET order_no='$B9_INJ_ORIG' WHERE id=$ORDER;" >/dev/null 2>&1
        B9_INJ_ORDER=""
    fi
else
    skip "B9 带令牌导出全链路（未设 SECOND_VERIFY_DEV_CODE；后端须以同一值配置 yl.security.second-verify.dev-code）"
fi

# ---- 8.6 B10 监管上报（幂等：同月重放不新增行；retain_until 只写值） ----
B10_1=$(api_as "$ADMIN_TOKEN" -X POST "$SREPORT_API" -H "Idempotency-Key: d3-rpt1-$D3_STAMP")
check "B10 监管上报（code=0）" "$(code_of "$B10_1")" "0"
B10_BEFORE=""
if [ -n "$D3_CONN" ]; then
    B10_RETAIN=$("$MYSQL_BIN" $D3_CONN -N -e \
        "SELECT COUNT(*) FROM yl_evaluation.supervise_report WHERE org_id=$ORG AND retain_until IS NOT NULL;")
    check_ge "B10 retain_until 已写入（只写值，裁决 3）" "$B10_RETAIN" "1"
    B10_BEFORE=$("$MYSQL_BIN" $D3_CONN -N -e \
        "SELECT COUNT(*) FROM yl_evaluation.supervise_report WHERE org_id=$ORG;")
fi
B10_2=$(api_as "$ADMIN_TOKEN" -X POST "$SREPORT_API" -H "Idempotency-Key: d3-rpt2-$D3_STAMP")
check "B10 同月重放（code=0）" "$(code_of "$B10_2")" "0"
if [ -n "$D3_CONN" ]; then
    B10_AFTER=$("$MYSQL_BIN" $D3_CONN -N -e \
        "SELECT COUNT(*) FROM yl_evaluation.supervise_report WHERE org_id=$ORG;")
    check "B10 同月重放不新增行（uk_month_org）" "$B10_AFTER" "$B10_BEFORE"
else
    skip "B10 库内核对的幂等/retain_until（需 MYSQL_BIN + MYSQL_PASSWORD）"
fi

# ---- 8.7 CR-M2-007：未携带凭据 → HTTP 401 + R.code=10002 ----
NOAUTH_TASK_CODE=$(curl -s --noproxy '*' -m 30 -o /tmp/yl_smoke_d3_noauth.json -w '%{http_code}' \
    -X GET "$TASK_API?pageNum=1&pageSize=1")
check "D-3 未携带凭据访问受保护端点 HTTP 状态码（CR-M2-007）" "$NOAUTH_TASK_CODE" "401"
check "D-3 未携带凭据响应体业务码 R.code=10002" "$(code_of "$(cat /tmp/yl_smoke_d3_noauth.json)")" "10002"

echo ""
echo "[9/9] 家属域（D-4：绑定状态机 + 家属读三端点 + elder↔user 绑定 + ELDER 段 + authLogout 黑名单）"
# 判定口径同上：业务失败一律 HTTP 200 + R.code；未认证 = HTTP 401 + R.code=10002。
# ★C1 修复直接钉住：FAMILY 打 approve → HTTP 200 + R.code=20003（权限码 account:org:manage，FAMILY 不持有）。
# 断言数随环境变量变化（SECOND_VERIFY_DEV_CODE / 是否有 [7/7] ELDER_ID），勿硬编码总数。
FAM_API="$BASE/api/v1/family/bindings"
USERBIND_API="$BASE/api/v1/elders"
D4_STAMP="$$-$(date +%s)"
RELATION_ELDER=""
REL2=""
E2_ID=""

# ---- 9.1 登录家属 + 老人本人账号（04 种子） ----
ELDER_LOGIN=$(login "$ELDER_USER" "$ELDER_PWD")
ELDER_TOKEN=$(token_of "$ELDER_LOGIN")
ELDER_UID=$(userid_of "$ELDER_LOGIN")
FAMILY_LOGIN=$(login "$FAMILY_USER" "$FAMILY_PWD")
FAMILY_TOKEN=$(token_of "$FAMILY_LOGIN")
FAMILY_UID=$(userid_of "$FAMILY_LOGIN")
if [ -z "$ELDER_TOKEN" ] || [ -z "$FAMILY_TOKEN" ]; then
    fail "D-4 联调账号登录失败（检查 04_seed_dev_accounts.sql 是否含 elder_dev/family_dev）"
else
    pass "家属登录（$FAMILY_USER → userId=$FAMILY_UID）"
    pass "老人本人登录（$ELDER_USER → userId=$ELDER_UID）"
fi

# ---- 9.2 elder↔user 绑定（把 [7/7] 建档老人 E1 绑为 elder_dev 本人；设计 §T07 取「经 user-binding 端点」） ----
if [ -n "$ELDER_ID" ] && [ -n "$ADMIN_TOKEN" ]; then
    UB=$(api_as "$ADMIN_TOKEN" -X POST "$USERBIND_API/$ELDER_ID/user-binding" \
        -H "Idempotency-Key: d4-ub-$D4_STAMP" -d "{\"userId\":$ELDER_UID}")
    check "user-binding 绑定老人本人账号（code=0）" "$(code_of "$UB")" "0"
    # ★C1 附加断言：FAMILY（无 account:org:manage）打 user-binding → 200 + 20003
    UB_FAM=$(api_as "$FAMILY_TOKEN" -X POST "$USERBIND_API/$ELDER_ID/user-binding" \
        -H "Idempotency-Key: d4-ub-fam-$D4_STAMP" -d "{\"userId\":$ELDER_UID}")
    check "FAMILY 打 user-binding 被拒（业务码 20003，★C1）" "$(code_of "$UB_FAM")" "20003"
else
    skip "elder↔user 绑定（需 [7/7] ELDER_ID + 机构管理员令牌）"
fi

# ---- 9.3 家属申请绑定（E1 已有登录账号 → 落 status=0 待老人确认） ----
if [ -n "$ELDER_ID" ] && [ -n "$ID_CARD" ] && [ -n "$FAMILY_TOKEN" ]; then
    APPLY=$(api_as "$FAMILY_TOKEN" -X POST "$FAM_API" \
        -H "Idempotency-Key: d4-apply-$D4_STAMP" \
        -d "{\"elderId\":$ELDER_ID,\"relation\":\"CHILD\",\"verifyMethod\":\"SCAN\",\"elderName\":\"张伟\",\"idCardNo\":\"$ID_CARD\"}")
    check "家属申请绑定（code=0）" "$(code_of "$APPLY")" "0"
    RELATION_ELDER=$(num_field "$APPLY" id)
    APPLY_STATUS=$(num_field "$APPLY" status)
    if [ "$APPLY_STATUS" = "0" ] || [ "$APPLY_STATUS" = "1" ]; then
        pass "申请落态 status∈{0,1}（实际 $APPLY_STATUS）"
    else
        fail "申请落态非法（期望 0/1，实际 $APPLY_STATUS；缺 SENSITIVE_FIELD_KEY 会得 40001）"
    fi
    # ---- 9.4 老人侧拒绝（ELDER；第三闸经 elder.user_id 反查锚收口） ----
    if [ -n "$RELATION_ELDER" ]; then
        REJ=$(api_as "$ELDER_TOKEN" -X POST "$FAM_API/$RELATION_ELDER/reject" \
            -H "Idempotency-Key: d4-rej-$D4_STAMP" -d '{"reason":"不同意"}')
        check "老人侧拒绝绑定（code=0）" "$(code_of "$REJ")" "0"
        check "拒绝后状态（3=已拒绝）" "$(num_field "$REJ" status)" "3"
        # ---- 9.4b ★R-2 拒绝后重新申请：必须复用原活性行（status 3→0/1，绝不 10005） ----
        REAPPLY_REJ=$(api_as "$FAMILY_TOKEN" -X POST "$FAM_API" \
            -H "Idempotency-Key: d4-reapply-rej-$D4_STAMP" \
            -d "{\"elderId\":$ELDER_ID,\"relation\":\"CHILD\",\"verifyMethod\":\"SCAN\",\"elderName\":\"张伟\",\"idCardNo\":\"$ID_CARD\"}")
        check "★R-2 拒绝后重新申请（code=0，不得 10005）" "$(code_of "$REAPPLY_REJ")" "0"
        check "★R-2 拒绝后重新申请复用原活性行（id 不变）" "$(num_field "$REAPPLY_REJ" id)" "$RELATION_ELDER"
        REAPPLY_REJ_STATUS=$(num_field "$REAPPLY_REJ" status)
        if [ "$REAPPLY_REJ_STATUS" = "0" ] || [ "$REAPPLY_REJ_STATUS" = "1" ]; then
            pass "★R-2 拒绝后重新申请落态 status∈{0,1}（实际 $REAPPLY_REJ_STATUS）"
        else
            fail "★R-2 拒绝后重新申请落态非法（期望 0/1，实际 $REAPPLY_REJ_STATUS）"
        fi
        # 重复申请（此时 status∈{0,1}）→ 10005
        DUP_APPLY=$(api_as "$FAMILY_TOKEN" -X POST "$FAM_API" \
            -H "Idempotency-Key: d4-dup-pending-$D4_STAMP" \
            -d "{\"elderId\":$ELDER_ID,\"relation\":\"CHILD\",\"verifyMethod\":\"SCAN\",\"elderName\":\"张伟\",\"idCardNo\":\"$ID_CARD\"}")
        check "★R-2 重复申请 status∈{0,1}（code=10005）" "$(code_of "$DUP_APPLY")" "10005"
    fi
else
    skip "家属申请 + 老人拒绝（需 [7/7] ELDER_ID + ID_CARD + 家属令牌）"
fi

# ---- 9.5 ELDER 段（★C3 覆盖声明）：GET /elders 自读 → code=0（elder.user_id=me） ----
ELDER_SELF=$(api_as "$ELDER_TOKEN" -X GET "$ELDER_API?pageNum=1&pageSize=5")
check "ELDER 自读老人列表（code=0，data_scope=1）" "$(code_of "$ELDER_SELF")" "0"

# ---- 9.5b D4-R7（已修复）：ELDER 读**本人已发布**报告 → code=0（★C3 声明翻转，原「恒假」不再成立） ----
# 夹具说明（2026-10-13 实跑订正）：主流程评估单 $ORDER 的 elder_id=$ELDER（种子 id，dev 库**无该行**——
#   种子仅含账号不含老人档案，直绑 user_id 匹配 0 行 → 锚子查询恒空 → 假 10004，SMOKE-1013 实证）。
#   改为把报告 elder_id **临时**改指 [7/7] 建档老人 E1（id=$ELDER_ID，其 user_id 已在 9.2 经服务端绑定为
#   elder_dev 本人、加密字段齐全），段末按原值还原 elder_id 与报告 status——不还原会污染下一次运行（SMOKE-R1 同源）。
if [ -n "$ORDER" ] && [ -n "$ELDER_TOKEN" ] && [ -n "$ELDER_ID" ]; then
    ORIG_REPORT_ELDER=$("$MYSQL_BIN" $MYSQL_CONN yl_evaluation -N -B -e \
        "SELECT IFNULL(elder_id, 0) FROM eval_report WHERE order_id = $ORDER" 2>/dev/null)
    ORIG_STATUS=$("$MYSQL_BIN" $MYSQL_CONN yl_evaluation -N -B -e \
        "SELECT IFNULL(status, 0) FROM eval_report WHERE order_id = $ORDER" 2>/dev/null)
    "$MYSQL_BIN" $MYSQL_CONN yl_evaluation >/dev/null 2>&1 <<SQL
UPDATE eval_report SET elder_id = $ELDER_ID WHERE order_id = $ORDER;
SQL
    SELF_REPORT=$(api_as "$ELDER_TOKEN" -X GET "$API/assessments/$ORDER/report")
    check "D4-R7 ELDER 读本人已发布报告（code=0）" "$(code_of "$SELF_REPORT")" "0"
    check "D4-R7 本人报告状态（1=已发布）" "$(str_field "$SELF_REPORT" status)" "1"
    # ---- 9.5b-1 rR1a6L B5-后端：本人报告列表（GET /evaluation/reports）—— 与详情同一夹具、同一状态口径 ----
    REPORT_LIST=$(api_as "$ELDER_TOKEN" -X GET "$API/reports?pageNum=1&pageSize=10")
    check "rR1a6L 本人报告列表（code=0，data_scope=1）" "$(code_of "$REPORT_LIST")" "0"
    check_ge "rR1a6L 本人报告列表含已发布报告（total ≥ 1）" "$(num_field "$REPORT_LIST" total)" "1"
    if echo "$REPORT_LIST" | grep -q "\"reportNo\":\"$REPORT_NO\""; then
        pass "rR1a6L 本人报告列表命中本人已发布报告（reportNo=$REPORT_NO）"
    else
        fail "rR1a6L 本人报告列表未命中本人已发布报告（reportNo=$REPORT_NO）"
    fi
    if echo "$REPORT_LIST" | grep -Eq '"dimScores"|"dimensionScores"|"verifyHash"|"pdfCosKey"|"ruleSha"'; then
        fail "rR1a6L 报告列表下发了非最小字段（维度分/敏感列）——契约 ReportListItem 只允许 6 个字段"
    else
        pass "rR1a6L 报告列表为最小字段集（无维度分 / 无 verifyHash/pdfCosKey/ruleSha）"
    fi
    # 越权方向：他人/不存在的 orderId → 同码 10004（不泄露存在性，与家属域口径一致）
    CROSS_ORDER=$(api_as "$ELDER_TOKEN" -X GET "$API/assessments/999000000000000999/report")
    check "D4-R7 ELDER 读他人/不存在报告（业务码 10004）" "$(code_of "$CROSS_ORDER")" "10004"
    # 状态闸：本人报告临时置 status=2（已作废）→ 老人须读不到（同码 10004，M3）
    "$MYSQL_BIN" $MYSQL_CONN yl_evaluation >/dev/null 2>&1 <<SQL
UPDATE eval_report SET status = 2 WHERE order_id = $ORDER;
SQL
    VOID_SELF=$(api_as "$ELDER_TOKEN" -X GET "$API/assessments/$ORDER/report")
    check "D4-R7 状态闸：本人已作废报告不可读（业务码 10004）" "$(code_of "$VOID_SELF")" "10004"
    # ---- 9.5b-2 同一夹具（status=2）下：列表必须与详情同口径（VOID 不出现，且空态不报错） ----
    REPORT_LIST_VOID=$(api_as "$ELDER_TOKEN" -X GET "$API/reports?pageNum=1&pageSize=10")
    check "rR1a6L 作废后本人报告列表仍 code=0（空态不报错、不泄露存在性）" "$(code_of "$REPORT_LIST_VOID")" "0"
    if echo "$REPORT_LIST_VOID" | grep -q "\"reportNo\":\"$REPORT_NO\""; then
        fail "rR1a6L status=2（VOID）报告不得出现在本人报告列表（与详情状态闸同口径）"
    else
        pass "rR1a6L status=2（VOID）报告不出现在本人报告列表（同口径承重）"
    fi
    # 还原（elder_id 与 status 一并还原）
    "$MYSQL_BIN" $MYSQL_CONN yl_evaluation >/dev/null 2>&1 <<SQL
UPDATE eval_report SET status = $ORIG_STATUS, elder_id = $ORIG_REPORT_ELDER WHERE order_id = $ORDER;
SQL
    pass "D4-R7 夹具已还原（报告 status=$ORIG_STATUS、elder_id=$ORIG_REPORT_ELDER）"
else
    skip "D4-R7 ELDER 自读报告（需主流程 ORDER + 老人令牌 + [7/7] 建档老人）"
fi

# ---- 9.6 家属申请 + 机构审核 + 家属读三端点（另起一个无登录账号的老人 E2） ----
ID2="53010219900307$(( (RANDOM % 9000) + 1000 ))"
E2_RESP=$(api_as "$ASSESSOR_TOKEN" -X POST "$ELDER_API" -H "Idempotency-Key: d4-e2-$D4_STAMP" \
    -d "{\"name\":\"李强\",\"idCardNo\":\"$ID2\",\"gender\":1,\"birthDate\":\"1985-06-15\",\"orgId\":$ORG}")
E2_ID=$(num_field "$E2_RESP" id)
if [ -n "$E2_ID" ]; then
    APPLY2=$(api_as "$FAMILY_TOKEN" -X POST "$FAM_API" -H "Idempotency-Key: d4-apply2-$D4_STAMP" \
        -d "{\"elderId\":$E2_ID,\"relation\":\"CHILD\",\"verifyMethod\":\"SCAN\",\"elderName\":\"李强\",\"idCardNo\":\"$ID2\"}")
    REL2=$(num_field "$APPLY2" id)
    check "家属申请绑定（无登录账号老人，code=0）" "$(code_of "$APPLY2")" "0"
    check "无登录账号老人落态（1=待机构审核）" "$(num_field "$APPLY2" status)" "1"
    # ★C1 主断言：FAMILY 打 approve → 200 + 20003（直接钉住权限码本体订正）
    FAPPROVE=$(api_as "$FAMILY_TOKEN" -X POST "$FAM_API/$REL2/approve" -H "Idempotency-Key: d4-fap-$D4_STAMP")
    check "FAMILY 打 approve 被拒（业务码 20003，★C1 直接钉住）" "$(code_of "$FAPPROVE")" "20003"
    # 机构审核放行 {0,1} → 2（★C2 宽容化）
    APPROVE=$(api_as "$ADMIN_TOKEN" -X POST "$FAM_API/$REL2/approve" -H "Idempotency-Key: d4-ap-$D4_STAMP")
    check "机构审核放行（code=0）" "$(code_of "$APPROVE")" "0"
    check "审核后状态（2=生效）" "$(num_field "$APPROVE" status)" "2"
    # ---- 9.6b ★R-2 status=2（已生效）时重复申请 → 10005（不覆盖生效绑定） ----
    DUP_ACTIVE=$(api_as "$FAMILY_TOKEN" -X POST "$FAM_API" -H "Idempotency-Key: d4-dup-active-$D4_STAMP" \
        -d "{\"elderId\":$E2_ID,\"relation\":\"CHILD\",\"verifyMethod\":\"SCAN\",\"elderName\":\"李强\",\"idCardNo\":\"$ID2\"}")
    check "★R-2 status=2 重复申请（code=10005）" "$(code_of "$DUP_ACTIVE")" "10005"
    # 家属读三端点（无报告时为空，仍须 code=0）
    REPORTS=$(api_as "$FAMILY_TOKEN" -X GET "$FAM_API/$REL2/reports?pageNum=1&pageSize=5")
    check "家属读报告列表（code=0）" "$(code_of "$REPORTS")" "0"
    TREND=$(api_as "$FAMILY_TOKEN" -X GET "$FAM_API/$REL2/trend")
    check "家属读趋势（code=0）" "$(code_of "$TREND")" "0"
    CARE_RESP=$(api_as "$FAMILY_TOKEN" -X GET "$FAM_API/$REL2/care-plan")
    check "家属读照护方案（code=0，无方案为空方案）" "$(code_of "$CARE_RESP")" "0"
    # 越权：他人/不存在 relationId 读报告 → 同码 10004（不泄露存在性）
    CROSS_REL=$(api_as "$FAMILY_TOKEN" -X GET "$FAM_API/999000000000000999/reports?pageNum=1&pageSize=1")
    check "越权/不存在 relationId 读报告（业务码 10004）" "$(code_of "$CROSS_REL")" "10004"
else
    fail "D-4 第二个老人建档失败：$(echo "$E2_RESP" | head -c 200)"
fi

# ---- 9.7 解绑（敏感：OR 含 account:family:unbind ⇒ 强制第四闸；缺凭据 10008） ----
if [ -n "$REL2" ]; then
    UNB_NOSV=$(api_as "$FAMILY_TOKEN" -X POST "$FAM_API/$REL2/unbind" -H "Idempotency-Key: d4-unb-nosv-$D4_STAMP")
    check "解绑缺二次验证凭据（业务码 10008）" "$(code_of "$UNB_NOSV")" "10008"
    if [ -n "$SECOND_VERIFY_CODE" ]; then
        SV_UNB_JSON=$(api_as "$FAMILY_TOKEN" -X POST "$AUTH_API/second-verify" \
            -d "{\"method\":\"PHONE\",\"credential\":\"$SECOND_VERIFY_CODE\"}")
        SV_UNB=$(str_field "$SV_UNB_JSON" token)
        if [ -n "$SV_UNB" ]; then
            UNB=$(api_h "$FAMILY_TOKEN" "$SV_UNB" -X POST "$FAM_API/$REL2/unbind" -H "Idempotency-Key: d4-unb-$D4_STAMP")
            check "解绑带二次验证凭据（code=0）" "$(code_of "$UNB")" "0"
            # ---- 9.7b ★R-2 解绑后重新申请：复用原活性行（status 4→0/1，绝不 10005） ----
            REAPPLY_UNB=$(api_as "$FAMILY_TOKEN" -X POST "$FAM_API" \
                -H "Idempotency-Key: d4-reapply-unb-$D4_STAMP" \
                -d "{\"elderId\":$E2_ID,\"relation\":\"CHILD\",\"verifyMethod\":\"SCAN\",\"elderName\":\"李强\",\"idCardNo\":\"$ID2\"}")
            check "★R-2 解绑后重新申请（code=0，不得 10005）" "$(code_of "$REAPPLY_UNB")" "0"
            check "★R-2 解绑后重新申请复用原活性行（id 不变）" "$(num_field "$REAPPLY_UNB" id)" "$REL2"
            REAPPLY_UNB_STATUS=$(num_field "$REAPPLY_UNB" status)
            if [ "$REAPPLY_UNB_STATUS" = "0" ] || [ "$REAPPLY_UNB_STATUS" = "1" ]; then
                pass "★R-2 解绑后重新申请落态 status∈{0,1}（实际 $REAPPLY_UNB_STATUS）"
            else
                fail "★R-2 解绑后重新申请落态非法（期望 0/1，实际 $REAPPLY_UNB_STATUS）"
            fi
        else
            fail "家属二次验证换凭据失败：$(echo "$SV_UNB_JSON" | head -c 200)"
        fi
    else
        skip "解绑带凭据全链路（未设 SECOND_VERIFY_DEV_CODE）"
    fi
else
    skip "解绑（需 [9/9] REL2，即第二个绑定的 relationId）"
fi

# ---- 9.8 无令牌访问家属端点 → HTTP 401 + code=10002（CR-M2-007 回归） ----
FAM_NOAUTH=$(curl -s --noproxy '*' -m 30 -o /tmp/yl_smoke_d4_noauth.json -w '%{http_code}' -X GET "$FAM_API?pageNum=1&pageSize=1")
check "D-4 未携带凭据访问家属端点 HTTP 状态码（CR-M2-007）" "$FAM_NOAUTH" "401"
check "D-4 未携带凭据响应体业务码 R.code=10002" "$(code_of "$(cat /tmp/yl_smoke_d4_noauth.json)")" "10002"

# ---- 9.9 authLogout 六步（§1.3）：access 入黑名单 + refresh 吊销（双令牌同时失效） ----
# 用 family_dev 新签发的一对 A + R 验证；本段最后执行，登出后 A 即失效（不影响前面的 FAMILY_TOKEN）。
A_LOGIN=$(login "$FAMILY_USER" "$FAMILY_PWD")
A_TOK=$(token_of "$A_LOGIN")
R_TOK=$(str_field "$A_LOGIN" refreshToken)
S2=$(curl -s --noproxy '*' -m 30 -o /tmp/yl_smoke_d4_s2.json -w '%{http_code}' \
    -H "Authorization: Bearer $A_TOK" "$ELDER_API?pageNum=1&pageSize=1")
check "logout① access 有效 GET /elders HTTP 状态码" "$S2" "200"
check "logout① access 有效 R.code=0" "$(code_of "$(cat /tmp/yl_smoke_d4_s2.json)")" "0"
LOGOUT_CODE=$(curl -s --noproxy '*' -m 30 -o /tmp/yl_smoke_d4_logout.json -w '%{http_code}' \
    -X POST "$AUTH_API/logout" -H "Authorization: Bearer $A_TOK" -H 'Content-Type: application/json' \
    -d "{\"refreshToken\":\"$R_TOK\"}")
check "logout③ 登出 HTTP 状态码" "$LOGOUT_CODE" "200"
check "logout③ 登出 R.code=0" "$(code_of "$(cat /tmp/yl_smoke_d4_logout.json)")" "0"
S4=$(curl -s --noproxy '*' -m 30 -o /tmp/yl_smoke_d4_s4.json -w '%{http_code}' \
    -H "Authorization: Bearer $A_TOK" "$ELDER_API?pageNum=1&pageSize=1")
check "logout④ 旧 access 失效 HTTP 状态码（401）" "$S4" "401"
check "logout④ 旧 access 失效 R.code=10002" "$(code_of "$(cat /tmp/yl_smoke_d4_s4.json)")" "10002"
S5=$(curl -s --noproxy '*' -m 30 -o /tmp/yl_smoke_d4_s5.json -w '%{http_code}' \
    -X POST "$AUTH_API/refresh" -H 'Content-Type: application/json' \
    -d "{\"refreshToken\":\"$R_TOK\"}")
check "logout⑤ 旧 refresh 失效 HTTP 状态码（会话入口 permitAll → 200）" "$S5" "200"
check "logout⑤ 旧 refresh 失效 R.code=10002" "$(code_of "$(cat /tmp/yl_smoke_d4_s5.json)")" "10002"
S6=$(curl -s --noproxy '*' -m 30 -o /tmp/yl_smoke_d4_s6.json -w '%{http_code}' \
    -X GET "$ELDER_API?pageNum=1&pageSize=1")
check "logout⑥ 无令牌访问受保护端点 HTTP 状态码（401）" "$S6" "401"
check "logout⑥ 无令牌访问受保护端点 R.code=10002" "$(code_of "$(cat /tmp/yl_smoke_d4_s6.json)")" "10002"

echo ""
echo "=============================================================="
echo " 结果：通过 $PASS 项，失败 $FAIL 项，跳过 $SKIP 项"
echo "=============================================================="

echo ""
echo "清理联调数据（评估单 $ORDER / $ORDER2 / $ORDER3）..."
CONN="$MYSQL_CONN"
"$MYSQL_BIN" $CONN yl_evaluation <<SQL
DELETE FROM eval_report WHERE order_id IN ($ORDER,$ORDER2,$ORDER3);
DELETE FROM eval_answer WHERE order_id IN ($ORDER,$ORDER2,$ORDER3);
DELETE FROM eval_order WHERE id IN ($ORDER,$ORDER2,$ORDER3);
DELETE FROM eval_task WHERE id NOT IN (SELECT task_id FROM eval_order WHERE task_id IS NOT NULL);
DELETE FROM care_plan WHERE report_id NOT IN (SELECT id FROM eval_report);
DELETE FROM report_dimension_score WHERE report_id NOT IN (SELECT id FROM eval_report);
DELETE FROM eval_review_log WHERE order_id IN ($ORDER,$ORDER2,$ORDER3);
SQL
echo "  剩余业务数据：评估单 $("$MYSQL_BIN" $CONN -N -e 'SELECT COUNT(*) FROM yl_evaluation.eval_order;') 行"
# 报表与监管域（D-3）：清理本次冒烟产生的导出任务与当月监管上报行
"$MYSQL_BIN" $CONN -N -e \
    "DELETE FROM yl_evaluation.export_task WHERE task_no LIKE 'EX%'; " >/dev/null 2>&1
"$MYSQL_BIN" $CONN -N -e \
    "DELETE FROM yl_evaluation.supervise_report WHERE org_id=$ORG AND report_month=DATE_FORMAT(NOW(),'%Y-%m');" \
    >/dev/null 2>&1
echo "  已清理报表与监管域冒烟数据（export_task / supervise_report 当月）"
# 档案域（D-2）：清理本次冒烟建档行（audit_log 留痕按合规保留期保留，不清理）
if [ -n "$ELDER_ID" ]; then
    "$MYSQL_BIN" $CONN -N -e "DELETE FROM yl_evaluation.elder WHERE id=$ELDER_ID;" >/dev/null 2>&1
    echo "  已清理档案域冒烟数据（elder id=$ELDER_ID）"
fi
# 家属域（D-4）：清理本次冒烟产生的绑定行与第二个建档老人 E2
if [ -n "$ELDER_ID" ]; then
    "$MYSQL_BIN" $CONN -N -e "DELETE FROM yl_evaluation.elder_family_bind WHERE elder_id=$ELDER_ID;" >/dev/null 2>&1
fi
if [ -n "$E2_ID" ]; then
    "$MYSQL_BIN" $CONN -N -e "DELETE FROM yl_evaluation.elder_family_bind WHERE elder_id=$E2_ID;" >/dev/null 2>&1
    "$MYSQL_BIN" $CONN -N -e "DELETE FROM yl_evaluation.elder WHERE id=$E2_ID;" >/dev/null 2>&1
    echo "  已清理家属域冒烟数据（elder_family_bind / elder id=$E2_ID）"
fi
# 兜底：本次新建的 elder 若仍有 dev 账号绑定残留，一并解除（保持"跑完即整洁"）
"$MYSQL_BIN" $CONN -N -e \
    "UPDATE yl_evaluation.elder e JOIN yl_evaluation.sys_user u ON u.id=e.user_id SET e.user_id=NULL WHERE u.username LIKE '%\\_dev';" \
    >/dev/null 2>&1

[ "$FAIL" -eq 0 ] || exit 1
