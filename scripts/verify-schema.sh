#!/bin/sh
# =============================================================================
# DDL 落地验证：真实 MySQL 执行 + 结构断言 + 关键约束行为验证
#
# 关联任务：B1-4 数据库 ER 设计（任务 r4UcEv）
# 依据：docker/mysql/init/*.sql（01 建库 + 国标规则版本表；02 核心域 36 张表，含 ER 评审补丁 3 表）
#      docs/ADR/0003-id-timezone-fk-softdelete.md（ER-09 主键 / ER-12 时区 / ER-13 外键与软删除）
#
# 用途：在真实 MySQL 8 上执行初始化脚本并断言"结构真实落地 + 关键约束真实生效"，
#       避免"DDL 只存在于文件、从未被执行过"这类交付风险。
#
# 用法：
#   bash scripts/verify-schema.sh                     # 默认连 127.0.0.1:3306 root（空密码）
#   MYSQL_PORT=3307 MYSQL_PASSWORD=xxx bash scripts/verify-schema.sh
#   bash scripts/verify-schema.sh --drop               # 验证后删除测试库（默认保留）
#
# 环境变量：MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD / MYSQL_BIN
#
# 本地无 Docker 时，可用 MySQL 8 便携版（Windows x64）：
#   unzip mysql-8.0.37-winx64.zip -d /c/tools/
#   mysqld --initialize-insecure --basedir=<dir> --datadir=<datadir>
#   mysqld --basedir=<dir> --datadir=<datadir> --port=3306 --mysqlx=0
# =============================================================================

set -u

cd "$(dirname "$0")/.."

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"
MYSQL_BIN="${MYSQL_BIN:-mysql}"
SCHEMA_DIR="docker/mysql/init"
DB_NAME="yl_evaluation"

DROP_AFTER=0
for arg in "$@"; do
    case "$arg" in
        --drop) DROP_AFTER=1 ;;
        --keep) DROP_AFTER=0 ;;
    esac
done

if ! command -v "$MYSQL_BIN" >/dev/null 2>&1; then
    echo "✗ 未找到 mysql 客户端（$MYSQL_BIN）。请安装 MySQL 8 客户端，或用 MYSQL_BIN 指定绝对路径。" >&2
    exit 2
fi

# 空密码时不传 -p，避免 mysql 误认为需要交互输入密码
CONN_ARGS="-h $MYSQL_HOST -P $MYSQL_PORT -u $MYSQL_USER --protocol=TCP --default-character-set=utf8mb4"
if [ -n "$MYSQL_PASSWORD" ]; then
    CONN_ARGS="$CONN_ARGS -p$MYSQL_PASSWORD"
fi

# 查询：跳过表头，仅输出值
sql() {
    # shellcheck disable=SC2086
    "$MYSQL_BIN" $CONN_ARGS -N -B -e "$1" 2>/dev/null
}

# 执行：只关心退出码
exec_sql() {
    # shellcheck disable=SC2086
    "$MYSQL_BIN" $CONN_ARGS -e "$1" >/dev/null 2>&1
}

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

check_ge() { # desc / actual / min
    if [ "${2:-0}" -ge "${3:-0}" ] 2>/dev/null; then
        pass_msg "$1 = $2（≥ $3）"
    else
        fail_msg "$1 = ${2:-空}（期望 ≥ $3）"
    fi
}

echo "=============================================================="
echo " DDL 落地验证（B1-4 / r4UcEv）"
echo " 目标：$MYSQL_USER@$MYSQL_HOST:$MYSQL_PORT  库：$DB_NAME"
echo "=============================================================="
echo ""

# ---------- 0) 连通性 ----------
SERVER_VERSION=$(sql "SELECT VERSION();")
if [ -z "$SERVER_VERSION" ]; then
    echo "✗ 无法连接 MySQL（$MYSQL_USER@$MYSQL_HOST:$MYSQL_PORT）。请确认实例已启动、账号密码正确。" >&2
    exit 1
fi
echo "服务端版本：$SERVER_VERSION"
echo ""

# ---------- 1) 执行初始化脚本 ----------
echo "[1/7] 执行初始化脚本（先 DROP 保证干净重来）"
sql "DROP DATABASE IF EXISTS \`$DB_NAME\`;"
for f in "$SCHEMA_DIR"/*.sql; do
    [ -f "$f" ] || continue
    printf '  · %s ... ' "$f"
    if "$MYSQL_BIN" $CONN_ARGS < "$f" 2>/tmp/yl_schema_err.txt; then # shellcheck disable=SC2086
        echo "OK"
        PASS=$((PASS + 1))
    else
        echo "FAILED"
        sed 's/^/      /' /tmp/yl_schema_err.txt >&2
        FAIL=$((FAIL + 1))
    fi
done
echo ""

# ---------- 2) 表数量 ----------
echo "[2/7] 结构断言：表数量"
TOTAL_TABLES=$(sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME' AND table_type='BASE TABLE';")
EXPECTED_TABLES=$(grep -h -cE '^CREATE TABLE' "$SCHEMA_DIR"/*.sql | awk '{s+=$1} END {print s}')
check "表总数（与 DDL 定义数一致）" "$TOTAL_TABLES" "$EXPECTED_TABLES"
echo "      分域明细："
sql "SELECT CONCAT('        ', IFNULL(NULLIF(t.table_comment,''), '(无注释)'), ' × ', COUNT(*))
     FROM information_schema.tables t
     WHERE t.table_schema='$DB_NAME' AND t.table_type='BASE TABLE'
     GROUP BY t.table_comment ORDER BY COUNT(*) DESC;"
echo ""

# ---------- 3) 生成列：软删除 × 唯一键共存 ----------
echo "[3/7] 结构断言：生成列 active_uk（软删除与唯一键共存方案）"
GEN_COLS=$(sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DB_NAME' AND column_name='active_uk' AND generation_expression IS NOT NULL;")
check "生成列 active_uk 数量" "$GEN_COLS" "3"
echo "      明细："
sql "SELECT CONCAT('        ', table_name, ' → ', generation_expression)
     FROM information_schema.columns
     WHERE table_schema='$DB_NAME' AND column_name='active_uk' ORDER BY table_name;"
echo ""

# ---------- 4) 敏感字段密文列 / 索引 ----------
echo "[4/7] 结构断言：敏感字段密文列与索引"
ENC_COLS=$(sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DB_NAME' AND column_name LIKE '%\\_enc';")
check_ge "密文列（*_enc，AES-256-GCM）" "$ENC_COLS" "10"
check_ge "身份证摘要列 id_card_hash（HMAC-SHA256）" "$(sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DB_NAME' AND column_name='id_card_hash';")" "1"
check_ge "姓名摘要列 name_hash" "$(sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DB_NAME' AND column_name='name_hash';")" "1"
check_ge "手机号摘要列 phone_hash" "$(sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DB_NAME' AND column_name='phone_hash';")" "1"
IDX_COUNT=$(sql "SELECT COUNT(DISTINCT CONCAT(table_name,'.',index_name)) FROM information_schema.statistics WHERE table_schema='$DB_NAME';")
check_ge "索引总数" "$IDX_COUNT" "40"
# 红线一：身份证号 / 人脸特征在全库任何表都不得以明文列存在
PLAIN_ID_FACE=$(sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DB_NAME' AND column_name IN ('id_card','id_card_no','face_feature','id_face');")
check "明文身份证/人脸特征列（全库，必须为 0）" "$PLAIN_ID_FACE" "0"
# 红线二：个人信息类表中，姓名/手机/住址/健康史/用药 一律只允许密文列（*_enc）或摘要列（*_hash）
#        注：机构公开信息（如 org.address 机构地址）不属于个人敏感信息，不在本断言范围
PLAIN_PERSONAL=$(sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DB_NAME' AND table_name IN ('elder','staff','sys_user','elder_family_bind','elder_authorization') AND column_name IN ('real_name','name','phone','mobile','address','health_history','medication','id_card');")
check "个人信息表明文敏感列（必须为 0）" "$PLAIN_PERSONAL" "0"
echo ""

# ---------- 5) 国标规则域播种 ----------
echo "[5/7] 数据断言：GB/T 42195-2022 规则域播种"
check "规则版本 gb_rule_version" "$(sql "SELECT COUNT(*) FROM $DB_NAME.gb_rule_version;")" "1"
check "一级指标 gb_dimension" "$(sql "SELECT COUNT(*) FROM $DB_NAME.gb_dimension;")" "4"
check "等级阈值 gb_grade_threshold" "$(sql "SELECT COUNT(*) FROM $DB_NAME.gb_grade_threshold;")" "5"
check_ge "基规则 gb_rule" "$(sql "SELECT COUNT(*) FROM $DB_NAME.gb_rule;")" "3"
echo "      4 个一级指标："
sql "SELECT CONCAT('        · ', dimension_name, '  [', IFNULL(clause_no,'—'), ']') FROM $DB_NAME.gb_dimension ORDER BY order_no;"
echo "      5 档等级阈值："
sql "SELECT CONCAT('        · ', grade_name, '：', FORMAT(min_score,2), ' ~ ', FORMAT(max_score,2), ' 分') FROM $DB_NAME.gb_grade_threshold ORDER BY order_no;"
echo "      条款可回溯性（clause_no 非空条数）："
sql "SELECT CONCAT('        gb_dimension/grade/rule 合计 ',
       (SELECT COUNT(*) FROM $DB_NAME.gb_dimension WHERE clause_no IS NOT NULL)
     + (SELECT COUNT(*) FROM $DB_NAME.gb_rule     WHERE clause_no IS NOT NULL),
     ' 条带国标条款号');"
echo ""

# ---------- 6) 关键业务约束 ----------
echo "[6/7] 结构断言：关键业务约束"
STATUS_COL=$(sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DB_NAME' AND table_name='eval_order' AND column_name IN ('status','assessor_id','reviewer_id','rule_version_id');")
check "评估单状态机 + 录入/复核互斥 + 规则版本锁定字段" "$STATUS_COL" "4"
check_ge "埋点留存控制列 expire_at（PRD §9 保留≤180 天）" "$(sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DB_NAME' AND table_name='track_event' AND column_name='expire_at';")" "1"
check_ge "审计留痕表 audit_log" "$(sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME' AND table_name='audit_log';")" "1"

# ---- ER 三方评审补丁断言（2026-09-18 裁决：ER-03/04/05/06/07/08）----
echo "      ER 三方评审补丁（ER-03/04/05/06/07/08）："
check "ER-03 保留期列 retain_until（4 表）" "$(sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DB_NAME' AND column_name='retain_until';")" "4"
check_ge "ER-05 乐观锁列 row_version" "$(sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DB_NAME' AND column_name='row_version';")" "2"
check "ER-06 作答三语义列（answer_state + is_required）" "$(sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DB_NAME' AND column_name IN ('answer_state','is_required');")" "2"
check "ER-07 长期授权列 is_permanent" "$(sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DB_NAME' AND table_name='elder_authorization' AND column_name='is_permanent';")" "1"
check "ER-08 告知同意留痕表 consent_record" "$(sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME' AND table_name='consent_record';")" "1"
check "ER-04 照护任务表（care_task + care_task_log）" "$(sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME' AND table_name IN ('care_task','care_task_log');")" "2"

# ---- ADR-0003 全库口径断言（ER-09 主键 / ER-12 时区 / ER-13 外键与软删除）----
# 见 docs/ADR/0003-id-timezone-fk-softdelete.md
# 三项均为「全库级」约定：不由单表决定，违反即为口径漂移，必须阻断构建。
echo "      ADR-0003 全库口径（ER-09 / ER-12 / ER-13）："
AI_COLS=$(sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DB_NAME' AND extra LIKE '%auto_increment%';")
check "ER-09 自增列（业务表禁用，仅允许 gb_rule_version）" "$AI_COLS" "1"
TS_COLS=$(sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DB_NAME' AND data_type='timestamp';")
check "ER-12 TIMESTAMP 列（全库禁用，统一 DATETIME）" "$TS_COLS" "0"
DT_COLS=$(sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DB_NAME' AND data_type='datetime';")
check_ge "ER-12 DATETIME 列（统一时间列类型）" "$DT_COLS" "60"
FK_COUNT=$(sql "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema='$DB_NAME' AND constraint_type='FOREIGN KEY';")
check "ER-13 外键约束（全库禁用，引用完整性由应用层保证）" "$FK_COUNT" "0"
DEL_COLS=$(sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DB_NAME' AND column_name='deleted';")
check_ge "ER-13 软删除列 deleted" "$DEL_COLS" "19"
echo ""

# ---------- 7) 行为验证：软删除 × 唯一键 ----------
echo "[7/7] 行为验证：软删除后可重建绑定，未删除时唯一约束真实生效"
BASE_SQL="USE \`$DB_NAME\`;"
sql "${BASE_SQL} DELETE FROM elder_family_bind WHERE id BETWEEN 9001 AND 9003;"

INS1="INSERT INTO elder_family_bind (id, elder_id, family_user_id, relation, verify_method, status) VALUES (9001, 1001, 2001, 'CHILD', 'SCAN', 2);"
INS2="INSERT INTO elder_family_bind (id, elder_id, family_user_id, relation, verify_method, status) VALUES (9002, 1001, 2001, 'CHILD', 'SCAN', 2);"
INS3="INSERT INTO elder_family_bind (id, elder_id, family_user_id, relation, verify_method, status) VALUES (9003, 1001, 2001, 'CHILD', 'SCAN', 2);"

# 7.1 首个未删除绑定：应成功
if exec_sql "${BASE_SQL} $INS1"; then
    pass_msg "首条「未删除」绑定写入成功"
else
    fail_msg "首条「未删除」绑定写入失败（预期成功）"
fi

# 7.2 同键第二行 deleted=0：应被唯一键拒绝
if exec_sql "${BASE_SQL} $INS2"; then
    fail_msg "同键重复「未删除」绑定未被拒绝（唯一约束失效！）"
else
    pass_msg "同键重复「未删除」绑定被唯一键拒绝（约束生效）"
fi

# 7.3 生成列在 deleted=1 时应为 NULL（NULL 互不相等，历史记录可并存）
sql "${BASE_SQL} UPDATE elder_family_bind SET deleted = 1 WHERE id = 9001;"
GEN_VALUE=$(sql "${BASE_SQL} SELECT IFNULL(active_uk, 'NULL') FROM elder_family_bind WHERE id = 9001;")
check "deleted=1 行 active_uk 取值" "$GEN_VALUE" "NULL"

# 7.4 软删除后重建同键绑定：应成功
if exec_sql "${BASE_SQL} $INS3"; then
    pass_msg "软删除后重建同键绑定成功（历史记录可追溯，新绑定不受阻）"
else
    fail_msg "软删除后重建同键绑定失败（生成列方案未按预期工作）"
fi

# 7.5 收尾：软删除行 + 新行并存
ROW_COUNT=$(sql "${BASE_SQL} SELECT COUNT(*) FROM elder_family_bind WHERE elder_id=1001 AND family_user_id=2001;")
check "同键历史记录并存条数（1 条已删除 + 1 条生效）" "$ROW_COUNT" "2"

sql "${BASE_SQL} DELETE FROM elder_family_bind WHERE id BETWEEN 9001 AND 9003;"
echo ""

# ---------- 汇总 ----------
echo "=============================================================="
echo " 结果：通过 $PASS 项，失败 $FAIL 项"
echo "=============================================================="

if [ "$DROP_AFTER" = "1" ]; then
    sql "DROP DATABASE IF EXISTS \`$DB_NAME\`;"
    echo "已删除测试库 $DB_NAME。"
else
    echo "已保留库 $DB_NAME（清理方式：bash scripts/verify-schema.sh --drop）"
fi

[ "$FAIL" -eq 0 ] || exit 1
exit 0
