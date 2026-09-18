# 银龄守护后端 · 多阶段构建
# 构建：mvn package 产出 yl-bootstrap/target/yl-bootstrap-*.jar
FROM eclipse-temurin:17-jre-jammy AS runtime

LABEL maintainer="yl-team" \
      description="yl-server - 老年人能力评估四端系统后端（GB/T 42195-2022）"

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -Dfile.encoding=UTF-8"

WORKDIR /app
COPY yl-bootstrap/target/yl-bootstrap-*.jar /app/app.jar

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone \
    && useradd -r -u 10001 yl && chown -R yl:yl /app
USER yl

EXPOSE 8080

# 健康检查：Spring Boot Actuator
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar --spring.profiles.active=${SPRING_PROFILES_ACTIVE:-prod}"]
