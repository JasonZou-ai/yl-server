#!/usr/bin/env bash
# 保留期链路 · 突变验证驱动（开发工具，非门禁；收编自 D5-R1 的一次性脚本）。
#
# 作用：逐条把 4 个 retention Mapper 的手写 SQL 条件"打坏"→ 跑 DB 级门禁 → 期望变红 → 还原 → md5 校验，
# 以此证明 `scripts/verify-retention-chain.sh`（RetentionChainDbIT）对这些条件**有区分力**。
# 这是"证明门禁有区分力"的唯一办法（D5-R1 教训：mock 单测对硬编码 SQL 零区分力）。
#
# ★ 不是门禁：不参与 mvn verify / CI。真正的门禁是 scripts/verify-retention-chain.sh。
#
# 前置：MySQL / Redis 在跑；yl-server 实例**已停**（否则 fat jar 被锁，编译产物替换失败）。
# 凭据：默认读 ${MUT_CRED_FILE:-/tmp/d5-run/db.sh}（须导出 MYSQL_PASSWORD；REDIS_PASSWORD 可空）。
# 日志：默认落 ${MUT_LOG_DIR:-/tmp/d5-run}。
#
# 用法：bash scripts/dev/run-retention-mutations.sh
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPO_ROOT}"

CRED_FILE="${MUT_CRED_FILE:-/tmp/d5-run/db.sh}"
if [ ! -f "${CRED_FILE}" ]; then
  echo "!!! 凭据文件不存在：${CRED_FILE}（用 MUT_CRED_FILE 指定）" >&2
  echo "    内容示例：MYSQL_PASSWORD='...'  REDIS_PASSWORD=''" >&2
  exit 2
fi
# shellcheck source=/dev/null
. "${CRED_FILE}"
export DB_PASSWORD="${MYSQL_PASSWORD}"
export REDIS_PASSWORD="${REDIS_PASSWORD:-}"

LOG_DIR="${MUT_LOG_DIR:-/tmp/d5-run}"
mkdir -p "${LOG_DIR}"

PY="${PYTHON_BIN:-python}"
MUTATOR="${SCRIPT_DIR}/mutate-retention-sql.py"

A="yl-modules/yl-module-archive/src/main/java/cn/yl/modules/archive/mapper/ElderFamilyBindMapper.java"
S="yl-modules/yl-module-supervise/src/main/java/cn/yl/modules/supervise/mapper/SuperviseReportMapper.java"
E="yl-modules/yl-module-evaluation/src/main/java/cn/yl/modules/evaluation/mapper/EvalOrderMapper.java"
T="yl-modules/yl-module-account/src/main/java/cn/yl/modules/account/mapper/SysUserThirdPartyMapper.java"

md5of() { md5sum "$1" | cut -c1-8; }
BASE_A=$(md5of "$A"); BASE_S=$(md5of "$S"); BASE_E=$(md5of "$E"); BASE_T=$(md5of "$T")
echo "基线 md5: A=$BASE_A S=$BASE_S E=$BASE_E T=$BASE_T"

edit() { # key  mutate|revert
  "${PY}" "${MUTATOR}" "$1" "$2" "${REPO_ROOT}"
}

run_one() { # key
  local key="$1"
  echo; echo "############ $key ############"
  printf 'mutate: '; edit "$key" mutate
  mvn -o -P db-it -pl yl-bootstrap -am test -Dtest=RetentionChainDbIT \
      -Dsurefire.failIfNoSpecifiedTests=false > "${LOG_DIR}/mut-$key.log" 2>&1
  echo "[$key] gate exit=$?"
  grep -a "<<< FAILURE" "${LOG_DIR}/mut-$key.log" | grep -oaE "RetentionChainDbIT\.[a-zA-Z0-9_]+" | sort -u
  grep -a "BUILD SUCCESS\|BUILD FAILURE" "${LOG_DIR}/mut-$key.log" | tail -1
  printf 'revert: '; edit "$key" revert
  echo "md5: A=$(md5of "$A") S=$(md5of "$S") E=$(md5of "$E") T=$(md5of "$T")"
  if [ "$(md5of "$A")" != "$BASE_A" ] || [ "$(md5of "$S")" != "$BASE_S" ] || \
     [ "$(md5of "$E")" != "$BASE_E" ] || [ "$(md5of "$T")" != "$BASE_T" ]; then
    echo "!!! md5 不一致，中止 !!!"; exit 3
  fi
}

for k in M1 M2 M3 M4 M5; do run_one "$k"; done

echo; echo "==== 终态 md5（须=基线）===="
echo "A=$(md5of "$A")/$BASE_A S=$(md5of "$S")/$BASE_S E=$(md5of "$E")/$BASE_E T=$(md5of "$T")/$BASE_T"
