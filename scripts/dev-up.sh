#!/usr/bin/env bash
# 银龄守护后端 · 本地一键启动
#   1) 拉起 MySQL8 / Redis7 / RocketMQ5 容器
#   2) 等待健康检查通过
#   3) 启动 Spring Boot 应用（dev profile）
#
# 前置：Docker Desktop 已启动；JDK 17 与 Maven 3.9+ 已安装（或使用 ./mvnw）
set -euo pipefail

cd "$(dirname "$0")/.."

echo "==> [1/3] 启动中间件容器 ..."
docker compose up -d

echo "==> [2/3] 等待容器健康 ..."
for i in $(seq 1 60); do
  mysql_ok=$(docker inspect -f '{{.State.Health.Status}}' yl-mysql 2>/dev/null || echo "starting")
  redis_ok=$(docker inspect -f '{{.State.Health.Status}}' yl-redis 2>/dev/null || echo "starting")
  if [ "$mysql_ok" = "healthy" ] && [ "$redis_ok" = "healthy" ]; then
    echo "    MySQL/Redis 已就绪"
    break
  fi
  echo "    等待中 ... mysql=$mysql_ok redis=$redis_ok ($i/60)"
  sleep 3
done

echo "==> [3/3] 启动应用（dev）..."
mvn -B -ntp -pl yl-bootstrap -am spring-boot:run -Dspring-boot.run.profiles=dev -DskipCheckstyle=true

echo "完成后可访问："
echo "  健康检查   http://127.0.0.1:8080/api/v1/system/health"
echo "  接口文档   http://127.0.0.1:8080/doc.html"
