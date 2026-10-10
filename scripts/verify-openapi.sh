#!/bin/sh
# =============================================================================
# 契约门禁：openapi/yl-api.yaml 的结构自洽性 + 与实现的路径漂移（静态自检）
#
# 关联：docs/change/D1-openapi-contract-delta.md（D-1 契约盘点）
#       yl-bootstrap 的 cn.yl.bootstrap.contract.OpenApiContractTest（权威版，随 `mvn verify` 跑）
#
# 为什么需要本脚本（除 Java 版之外）：
#   ① 改契约的人（含前端）不必装 Maven 就能自检；② 与 Java 版同源口径、互为冗余，
#   任一侧被误删或被绕过时另一侧仍会变红。
#
# 断言（5 组）：
#   1) 每个操作必须声明 x-required-permission
#   2) 每个操作必须声明 x-data-scope.mode，且取值 ∈ {public,self,org,relation,all}
#   3) mode=public 必须配 none（公开端点不得要求权限码）
#   4) 敏感权限点（4 个）必须标 x-second-verify: true，且非敏感点不得标
#   5) 契约 x-status: implemented 的操作集合 必须与控制器端点集合**完全相等**（双向漂移）
#
# 不覆盖（各有归属）：
#   · 权限码是否真实存在于库 → scripts/verify-schema.sh（需要连库）
#   · YAML 语法解析 → OpenApiContractTest（SnakeYAML 真解析；本脚本只做文本级自检）
#
# 用法：bash scripts/verify-openapi.sh
# =============================================================================

set -u

cd "$(dirname "$0")/.."

SPEC="openapi/yl-api.yaml"
TAB=$(printf '\t')

PASS=0
FAIL=0

pass_msg() {
    echo "  ✓ $1"
    PASS=$((PASS + 1))
}

fail_msg() {
    echo "  ✗ $1"
    FAIL=$((FAIL + 1))
}

check() { # desc / actual / expected
    if [ "$2" = "$3" ]; then
        pass_msg "$1 = $2"
    else
        fail_msg "$1 = $2（期望 $3）"
    fi
}

echo "=============================================================="
echo " 契约门禁（D-1）：$SPEC"
echo "=============================================================="
echo ""

if [ ! -f "$SPEC" ]; then
    echo "✗ 契约文件不存在：$SPEC" >&2
    exit 2
fi

# ---------- 0) 抽取操作（制表符分隔，因权限码本身含 `|` 不可作分隔符）----------
#  VERB <TAB> PATH <TAB> PERM <TAB> MODE <TAB> STATUS <TAB> SECOND_VERIFY
OPS=$(awk '
    BEGIN { path = ""; verb = ""; perm = ""; mode = ""; status = ""; sv = "" }
    function flush() {
        if (verb != "") {
            printf "%s\t%s\t%s\t%s\t%s\t%s\n", toupper(verb), path, perm, mode, status, (sv == "" ? "-" : sv)
        }
        verb = ""; perm = ""; mode = ""; status = ""; sv = ""
    }
    /^  \// { flush(); path = $1; sub(/:$/, "", path); next }
    /^    (get|post|put|delete|patch):/ {
        flush(); verb = $1; sub(/:$/, "", verb); next
    }
    /^      x-required-permission:/ {
        v = $0; sub(/^[ \t]*x-required-permission:[ \t]*/, "", v); perm = v; next
    }
    /^      x-data-scope:/ {
        rest = $0; sub(/^[ \t]*x-data-scope:[ \t]*/, "", rest)
        if (rest ~ /mode:/) { sub(/.*mode:[ \t]*/, "", rest); sub(/[^A-Za-z].*$/, "", rest); mode = rest }
        next
    }
    /^        mode:/ {
        m = $0; sub(/^[ \t]*mode:[ \t]*/, "", m); sub(/[^A-Za-z].*$/, "", m); mode = m; next
    }
    /^      x-status:/ {
        v = $0; sub(/^[ \t]*x-status:[ \t]*/, "", v); sub(/[ \t].*$/, "", v); status = v; next
    }
    /^      x-second-verify:/ {
        v = $0; sub(/^[ \t]*x-second-verify:[ \t]*/, "", v); sub(/[ \t].*$/, "", v); sv = v; next
    }
    /^[^ \t]/ { flush() }
    END { flush() }
' "$SPEC")

TOTAL=$(printf '%s\n' "$OPS" | grep -c "$TAB" || true)
IMPLEMENTED_LIST=$(printf '%s\n' "$OPS" | awk -F'\t' '$5 == "implemented" { print $1 " " $2 }')
IMPLEMENTED_COUNT=$(printf '%s\n' "$IMPLEMENTED_LIST" | grep -c '/' || true)
PLANNED_COUNT=$(printf '%s\n' "$OPS" | awk -F'\t' '$5 == "planned"' | grep -c "$TAB" || true)

echo "契约操作总数：$TOTAL（implemented $IMPLEMENTED_COUNT / planned $PLANNED_COUNT）"
echo ""

# ---------- 1) x-required-permission 齐备 ----------
echo "[1/5] 扩展键：x-required-permission"
MISSING_PERM=$(printf '%s\n' "$OPS" | awk -F'\t' '$3 == "" { print "      · " $1 " " $2 }')
check "缺 x-required-permission 的操作数" "$(printf '%s\n' "$MISSING_PERM" | grep -c '·' || true)" "0"
[ -z "$MISSING_PERM" ] || printf '%s\n' "$MISSING_PERM"
echo ""

# ---------- 2) x-data-scope.mode 齐备且取值合法 ----------
echo "[2/5] 扩展键：x-data-scope.mode ∈ {public,self,org,relation,all}"
BAD_MODE=$(printf '%s\n' "$OPS" | awk -F'\t' '
    $4 != "public" && $4 != "self" && $4 != "org" && $4 != "relation" && $4 != "all" {
        print "      · " $1 " " $2 " → mode=" ($4 == "" ? "(缺失)" : $4)
    }')
check "mode 缺失或越界的操作数" "$(printf '%s\n' "$BAD_MODE" | grep -c '·' || true)" "0"
[ -z "$BAD_MODE" ] || printf '%s\n' "$BAD_MODE"
echo ""

# ---------- 3) mode=public ⟹ 权限码 none ----------
echo "[3/5] 一致性：mode=public 必须配 none"
BAD_PUBLIC=$(printf '%s\n' "$OPS" | awk -F'\t' '$4 == "public" && $3 != "none" { print "      · " $1 " " $2 " → perm=" $3 }')
check "public 却声明权限码的操作数" "$(printf '%s\n' "$BAD_PUBLIC" | grep -c '·' || true)" "0"
[ -z "$BAD_PUBLIC" ] || printf '%s\n' "$BAD_PUBLIC"
echo ""

# ---------- 4) 敏感点 ⟺ x-second-verify ----------
# 敏感权限点清单（sys_permission.need_second_verify = 1，共 4 个）——与 03_seed_rbac.sql、SensitivePermissions 同源
echo "[4/5] 第四闸：敏感权限点必须标 x-second-verify: true"
SENSITIVE_CODES="data:export evaluation:order:void account:family:unbind data:reveal"
SENSITIVE_PATTERN="data:export|evaluation:order:void|account:family:unbind|data:reveal"
BAD_SV=""
for code in $SENSITIVE_CODES; do
    hit=$(printf '%s\n' "$OPS" | awk -F'\t' -v c="$code" '$3 ~ c && $6 != "true" { print "      · " $1 " " $2 " → perm=" $3 }')
    [ -z "$hit" ] || BAD_SV="$BAD_SV
$hit"
done
BAD_SV=$(printf '%s\n' "$BAD_SV" | grep '·' || true)
NOT_SENSITIVE_BUT_FLAGGED=$(printf '%s\n' "$OPS" | awk -F'\t' -v p="$SENSITIVE_PATTERN" '
    $6 == "true" && $3 !~ p { print "      · " $1 " " $2 " → perm=" $3 }')
check "敏感点漏标 x-second-verify 的条数" "$(printf '%s\n' "$BAD_SV" | grep -c '·' || true)" "0"
check "非敏感点误标 x-second-verify 的条数" "$(printf '%s\n' "$NOT_SENSITIVE_BUT_FLAGGED" | grep -c '·' || true)" "0"
[ -z "$BAD_SV" ] || printf '%s\n' "$BAD_SV"
[ -z "$NOT_SENSITIVE_BUT_FLAGGED" ] || printf '%s\n' "$NOT_SENSITIVE_BUT_FLAGGED"
echo ""

# ---------- 5) 漂移：契约 implemented ⟺ 控制器端点 ----------
echo "[5/5] 漂移：契约 x-status=implemented ↔ 控制器端点（双向核对）"
CONTROLLER_ENDPOINTS=$(awk '
    FNR == 1 { prefix = "" }
    prefix == "" && /@RequestMapping\(/ {
        s = $0; sub(/^.*@RequestMapping\([ \t]*"/, "", s); sub(/".*$/, "", s); prefix = s; next
    }
    /^[ \t]+@(Get|Post|Put|Delete|Patch)Mapping/ {
        verb = $0; sub(/^[ \t]+@/, "", verb); sub(/Mapping.*$/, "", verb)
        sub2 = $0
        p = ""
        if (sub2 ~ /Mapping\(/) { p = sub2; sub(/^.*Mapping\([ \t]*"/, "", p); sub(/".*$/, "", p) }
        print toupper(verb) " " prefix p
    }
' $(find . -name '*Controller.java' -not -path './target/*' -not -path '*/target/*' | sort) | sort -u | grep '/')

CONTRACT_IMPL=$(printf '%s\n' "$IMPLEMENTED_LIST" | grep '/' | sort -u)

# 双向核对（用临时文件承载结果，避免 while 子壳变量不回传）
MISSING_IN_SPEC_FILE=/tmp/yl_openapi_missing_in_spec.txt
MISSING_IN_CODE_FILE=/tmp/yl_openapi_missing_in_code.txt
: > "$MISSING_IN_SPEC_FILE"
: > "$MISSING_IN_CODE_FILE"
printf '%s\n' "$CONTROLLER_ENDPOINTS" | while read -r line; do
    [ -z "$line" ] && continue
    printf '%s\n' "$CONTRACT_IMPL" | grep -Fxq "$line" || printf '      · %s\n' "$line"
done > "$MISSING_IN_SPEC_FILE"
printf '%s\n' "$CONTRACT_IMPL" | while read -r line; do
    [ -z "$line" ] && continue
    printf '%s\n' "$CONTROLLER_ENDPOINTS" | grep -Fxq "$line" || printf '      · %s\n' "$line"
done > "$MISSING_IN_CODE_FILE"
MISSING_IN_SPEC=$(grep '·' "$MISSING_IN_SPEC_FILE" || true)
MISSING_IN_CODE=$(grep '·' "$MISSING_IN_CODE_FILE" || true)

check "控制器端点数" "$(printf '%s\n' "$CONTROLLER_ENDPOINTS" | grep -c '/' || true)" "$IMPLEMENTED_COUNT"
check "契约缺登的已实现端点（错误路径/漏登）条数" "$(printf '%s\n' "$MISSING_IN_SPEC" | grep -c '·' || true)" "0"
check "契约多登的 implemented 端点（未实现却标 implemented）条数" "$(printf '%s\n' "$MISSING_IN_CODE" | grep -c '·' || true)" "0"
[ -z "$MISSING_IN_SPEC" ] || printf '    契约缺登：\n%s\n' "$MISSING_IN_SPEC"
[ -z "$MISSING_IN_CODE" ] || printf '    契约多登：\n%s\n' "$MISSING_IN_CODE"
echo ""

# ---------- 汇总 ----------
echo "=============================================================="
echo " 契约门禁结果：通过 $PASS 项，失败 $FAIL 项（契约操作 $TOTAL，其中 implemented $IMPLEMENTED_COUNT）"
echo "=============================================================="

[ "$FAIL" -eq 0 ] || exit 1
exit 0
