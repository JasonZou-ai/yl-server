#!/usr/bin/env bash
# =============================================================================
# 开关 A（读路径）每日冒烟巡检 · rR1a6L 验收期专用
# -----------------------------------------------------------------------------
# 依据：PM 签发 2026-10-16 §二.1 / §三.3「读路径开关 A 保持每日定时冒烟，异常立即触发灰度止损」
# 口径：凭据**只从文件读取，绝不回显**；脚本 fail-closed；日志落到调用方指定路径。
#
# 用法：
#   bash scripts/patrol-switchA-daily.sh [日志目录]
# 前置：后端 8080 已起；凭据文件存在（默认 /tmp/d4-run/env.sh，可用 ENVF 覆盖），
#       含 DB_PASSWORD / SECOND_VERIFY_DEV_CODE / DEV_LOGIN_PASSWORD；
#       中文 argv 编码 shim 位于 /tmp/d4-run/shim（本机 curl 会把非 ASCII 参数转 GBK，
#       否则 22 条假红）。
#
# ★ 断言口径（2026-10-16 订正，勿再按「固定 N」猜）：
#   环境变量**全齐备**（DEV_LOGIN_PASSWORD + MYSQL_BIN + MYSQL_PASSWORD +
#   SECOND_VERIFY_DEV_CODE + EXPORT_DIR）时 = **130 条**（smoke-report-review-chain.sh 实测）。
#   ⚠ 缺 EXPORT_DIR 时，B9 的「CSV 产物魔数 / CSV 首行表头 / CSV 公式注入转义」三条
#     被折叠为**一条** skip（脚本 L637-661 的 if/else 结构），计数变成 127 通过 + 1 跳过
#     = 128，**并非基线回退**，而是口径折叠 —— 但每日门禁因此**少验 2 条**，不可接受。
#   故本脚本**自动推导并强制** EXPORT_DIR，并对「有效断言数 != 130」判失败（fail-closed）。
#
# 退出码：
#   0 = 130 条全绿（0 失败 0 跳过）；非 0 = 异常（须立即同步 PM/CCB 并评估灰度止损）
# =============================================================================
set -uo pipefail

LOGDIR="${1:-/tmp/d4-run/patrol}"
REPO="${REPO:-/c/Users/Administrator/WorkBuddy/2026-09-18-11-08-33/stage3_开发/yl-server}"
ENVF="${ENVF:-/tmp/d4-run/env.sh}"
SHIM="${SHIM:-/tmp/d4-run/shim}"
EXPECT_TOTAL="${EXPECT_TOTAL:-130}"
STAMP="$(date '+%Y%m%d-%H%M%S')"
LOG="$LOGDIR/switchA-smoke-$STAMP.log"

mkdir -p "$LOGDIR" || exit 2

[ -f "$ENVF" ] || { echo "FATAL: 缺凭据文件 $ENVF（fail-closed，不半跑）" >&2; exit 2; }
[ -d "$REPO" ] || { echo "FATAL: 缺仓库 $REPO" >&2; exit 2; }

# 凭据只注入环境，不回显
set -a; . "$ENVF"; set +a

# ---- MYSQL_BIN 自动推导（未设时按常见路径探测） ----
if [ -z "${MYSQL_BIN:-}" ]; then
    for cand in /c/mysql/bin/mysql.exe \
                /c/tools/mysql-8.0.37-winx64/bin/mysql.exe \
                /c/tools/mysql-8.0.46-winx64/bin/mysql.exe \
                "$(command -v mysql 2>/dev/null)"; do
        [ -n "$cand" ] && [ -x "$cand" ] && { MYSQL_BIN="$cand"; break; }
    done
fi
export MYSQL_BIN="${MYSQL_BIN:-}"
export MYSQL_PASSWORD="${DB_PASSWORD:-}"
[ -x "$MYSQL_BIN" ] || { echo "FATAL: 未取得可用的 MYSQL_BIN（fail-closed）" >&2; exit 2; }
[ -n "$MYSQL_PASSWORD" ] || { echo "FATAL: 未取得 MYSQL_PASSWORD（fail-closed）" >&2; exit 2; }

# ---- EXPORT_DIR 自动推导：后端 yl.export.dir 留空时回退 ${java.io.tmpdir}/yl-exports ----
#    （application.yml:150 `dir: ${EXPORT_DIR:}`；留空 → java.io.tmpdir/yl-exports）
if [ -z "${EXPORT_DIR:-}" ]; then
    _tmp="${TEMP:-${TMP:-/tmp}}"
    # Windows 路径 C:\Users\... → Git Bash 路径 /c/Users/...（冒烟脚本用 bash 读文件，须 POSIX 路径）
    _tmp="$(printf '%s' "$_tmp" | sed -e 's|\\|/|g' -e 's|^\([A-Za-z]\):|/\L\1|')"
    EXPORT_DIR="$_tmp/yl-exports"
fi
export EXPORT_DIR
[ -d "$EXPORT_DIR/export" ] || {
    echo "FATAL: 导出目录不可读：$EXPORT_DIR/export（fail-closed；否则 B9 CSV 三条断言被折叠成一条 skip）" >&2
    exit 2
}

[ -n "${DEV_LOGIN_PASSWORD:-}" ] || { echo "FATAL: 未取得 DEV_LOGIN_PASSWORD（fail-closed）" >&2; exit 2; }
[ -n "${SECOND_VERIFY_DEV_CODE:-}" ] || { echo "FATAL: 未取得 SECOND_VERIFY_DEV_CODE（fail-closed）" >&2; exit 2; }
[ -d "$SHIM" ] && export PATH="$SHIM:$PATH"

echo "[patrol] 时间=$STAMP  仓库=$(cd "$REPO" && git rev-parse --short HEAD)  日志=$LOG"
echo "[patrol] MYSQL_BIN=$MYSQL_BIN  EXPORT_DIR=$EXPORT_DIR"
H=$(curl.exe --noproxy '*' -s -m 10 http://127.0.0.1:8080/api/v1/system/health 2>/dev/null || true)
case "$H" in *'"status":"UP"'*) echo "[patrol] 健康：UP" ;; *) echo "FATAL: 8080 健康检查非 UP：${H:0:120}" >&2; exit 2 ;; esac

cd "$REPO" || exit 2
bash scripts/smoke-report-review-chain.sh > "$LOG" 2>&1
RC=$?

echo "[patrol] 结果行："
grep -aE '通过 [0-9]+ 项|失败|✗|剩余业务数据' "$LOG" | tail -8

# ---- 口径自检：有效断言数必须等于 EXPECT_TOTAL（防「折叠 skip」造成的隐形降基线） ----
_LINE=$(grep -aE '^ 结果：通过 [0-9]+ 项，失败 [0-9]+ 项，跳过 [0-9]+ 项' "$LOG" | tail -1)
_P=$(printf '%s' "$_LINE" | sed -E 's/.*通过 ([0-9]+) 项.*/\1/')
_F=$(printf '%s' "$_LINE" | sed -E 's/.*失败 ([0-9]+) 项.*/\1/')
_S=$(printf '%s' "$_LINE" | sed -E 's/.*跳过 ([0-9]+) 项.*/\1/')
_T=$(( ${_P:-0} + ${_F:-0} + ${_S:-0} ))
echo "[patrol] 口径：通过=${_P:-?} 失败=${_F:-?} 跳过=${_S:-?} 有效断言总数=$_T（期望 $EXPECT_TOTAL）"

if [ "$RC" -eq 0 ] && [ "${_F:-1}" -eq 0 ] && [ "$_T" -eq "$EXPECT_TOTAL" ]; then
    echo "[patrol] PASS：读路径冒烟 $_T/$EXPECT_TOTAL 全绿"
    exit 0
fi

{
    echo "[patrol] ALERT 明细："
    [ "$RC" -ne 0 ] && echo "  · 冒烟脚本退出码非 0（RC=$RC）"
    [ "${_F:-1}" -ne 0 ] && echo "  · 存在失败断言（失败=${_F}）"
    [ "$_T" -ne "$EXPECT_TOTAL" ] && echo "  · 有效断言总数 $_T != $EXPECT_TOTAL（疑似环境变量缺失导致整段折叠/跳过）"
} >&2
echo "[patrol] ALERT：读路径冒烟异常 ⇒ 立即同步 PM/CCB 并评估灰度止损" >&2
exit 1
