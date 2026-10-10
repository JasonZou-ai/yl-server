#!/usr/bin/env bash
# verify-like-ci.sh —— 在容器里复刻 CI 的完全相同命令（同 JDK 镜像、fresh m2、同参数）。
#
# 为什么存在（第 5 盲区的结构性闭环，2026-10-10 用户裁定）：本机门禁与 CI 门禁曾长期不是同一盏灯
# （钩子未接线 + SpotBugs 判定分歧，5 轮推送才收敛）。本机 mvn -o verify 绿对 CI 是否绿
# 没有预测力；要让「本机绿」具备预测力，本机验证就必须物理上与 CI 是同一件事——本脚本
# 用 maven:3.9-eclipse-temurin-17 容器 + 独立 fresh m2 卷复刻 CI 环境。
#
# 用法：
#   bash scripts/verify-like-ci.sh static   # static-analysis 作业同款：compile spotbugs:check
#   bash scripts/verify-like-ci.sh full     # build-and-test 作业同款：verify（含全部测试，容器内补装中文字体）
#
# 说明：
# - m2 用命名卷 yl-ci-fresh-m2 模拟 CI 全新缓存；卷跨次复用，二次运行免重下依赖。
#   需要彻底重置缓存时：docker volume rm yl-ci-fresh-m2
# - 本机仓库目录直接挂载进容器（只读依赖下载写在卷里，构建产物写在仓库 target/，与 CI 一致）。
set -euo pipefail

cd "$(dirname "$0")/.."

MODE="${1:-static}"
IMAGE="maven:3.9-eclipse-temurin-17"

case "$MODE" in
  static)
    CMD="mvn -B -ntp -DskipTests compile spotbugs:check"
    ;;
  full)
    # 与 CI build-and-test 作业同款 verify + 与 CI 一致的中文字体
    # （ReportPdfServiceTest 的中文文本抽取断言依赖 fonts-wqy-zenhei，口径见 ci.yml）
    CMD="apt-get update -qq && apt-get install -y -qq --no-install-recommends fonts-wqy-zenhei >/dev/null && mvn -B -ntp verify -DskipCheckstyle=true -DskipSpotbugs=true"
    ;;
  *)
    echo "用法: $0 [static|full]" >&2
    exit 2
    ;;
esac

echo "== verify-like-ci: mode=$MODE image=$IMAGE =="
# MSYS_NO_PATHCONV：Git Bash 会把 -v/-w 里的 POSIX 路径（/ws）映射成本地 Git 安装目录，
# 导致「the working directory ... is invalid」；禁用转换后 docker desktop 原生解析 Windows 路径。
MSYS_NO_PATHCONV=1 docker run --rm \
  -v "$(pwd -W 2>/dev/null || pwd):/ws" \
  -w /ws \
  -v yl-ci-fresh-m2:/root/.m2 \
  "$IMAGE" bash -lc "$CMD"
