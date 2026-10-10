#!/usr/bin/env bash
# =============================================================================
# 保留期链路 · 手写 SQL 的 DB 级回归门禁（闭环 D5-R1）
#
# 覆盖：4 个 retention Mapper 的 SQL 幂等 / 守卫条件 —— 这是既有单测（Fake/mock 端口）
#       永不触达的盲区：
#         T1 ER-14  sys_user_third_party#purgeExpired  retain_until IS NOT NULL AND < cutoff（物理删除）
#         T2 ER-03  eval_order#archiveExpired          ... AND status = 'PUBLISHED' → ARCHIVED
#         T3 D-5③   supervise_report#archiveExpired      archived_at IS NULL AND retain_until < cutoff
#         T4 D-5④   elder_family_bind#expireExpired      status = 2 AND deleted = 0 AND auth_end_at < cutoff
#         T5 静态   4 个 Mapper 手写 SQL 的关键 WHERE 条件存在性
#         T6 语义   到期时间列 IS NULL ⇒ 行永不被删除 / 封存 / 流转（三张表）
#
# 突变证明：scripts/dev/run-retention-mutations.sh（M1–M5，开发工具非门禁）——
#           逐条打坏 SQL 后本门禁必须变红，以此证明本门禁有区分力。
#
# 前置：MySQL 在跑、Redis 在跑（本门禁为真连库集成测试，不是单测）。
# 凭据：只从「凭据文件」读，脚本内**不内联任何口令**。
#       默认凭据文件 /tmp/d5-run/db.sh（内含 export MYSQL_PASSWORD=...）；
#       可用环境变量 D5_CRED_FILE 指定其它路径。
#       ★ 切勿把口令写进本脚本或命令行参数（会触发敏感信息审批/超时）。
#
# 实现：启用 yl-bootstrap 的 `db-it` profile（surefire includes 收窄为 **/*IT.java），
#       只跑 cn.yl.bootstrap.retention.RetentionChainDbIT；默认 `mvn verify` 不受影响。
#
# 用法：bash scripts/verify-retention-chain.sh
# 退出码：全绿 0；任一断言失败 / 环境不可用 1。
# =============================================================================

set -u

cd "$(dirname "$0")/.."

CRED_FILE="${D5_CRED_FILE:-/tmp/d5-run/db.sh}"

if [ ! -f "${CRED_FILE}" ]; then
    echo "✗ 凭据文件不存在：${CRED_FILE}"
    echo "  请创建它并写入：export MYSQL_PASSWORD='<db-password>'（勿把口令内联进本脚本）"
    exit 1
fi

# shellcheck disable=SC1090
. "${CRED_FILE}"

# 由凭据文件映射到应用读取的环境变量（DB_PASSWORD 供 application-dev.yml 的 ${DB_PASSWORD}）。
if [ -z "${MYSQL_PASSWORD:-}" ]; then
    echo "✗ 凭据文件 ${CRED_FILE} 未导出 MYSQL_PASSWORD"
    exit 1
fi
export DB_PASSWORD="${MYSQL_PASSWORD}"
# 本地 Redis 无密码：显式置空，避免 application-dev.yml 的默认口令与实况不符。
export REDIS_PASSWORD="${REDIS_PASSWORD:-}"

echo "== 保留期链路 DB 级门禁（db-it profile）=="
echo "   MySQL: ${MYSQL_HOST:-127.0.0.1}:${MYSQL_PORT:-3306}  凭据文件: ${CRED_FILE}"
echo

mvn -o -P db-it -pl yl-bootstrap -am test
STATUS=$?

echo
if [ "${STATUS}" -eq 0 ]; then
    echo "✓ 保留期链路 DB 级门禁：全绿（RetentionChainDbIT）"
else
    echo "✗ 保留期链路 DB 级门禁：失败（见上方 surefire 输出）"
fi
exit "${STATUS}"
