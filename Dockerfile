# 银龄守护后端 · 多阶段构建
# 构建：mvn package 产出 yl-bootstrap/target/yl-bootstrap.jar
# 注意：yl-bootstrap/pom.xml 配置了 <finalName>yl-bootstrap</finalName>，
#      产物无版本号后缀，故此处用 yl-bootstrap*.jar 通配（兼容去掉 finalName 的情况）
FROM eclipse-temurin:17-jre-jammy AS runtime

LABEL maintainer="yl-team" \
      description="yl-server - 老年人能力评估四端系统后端（GB/T 42195-2022）"

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -Dfile.encoding=UTF-8" \
    REPORT_PDF_FONT=/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc

WORKDIR /app
COPY yl-bootstrap/target/yl-bootstrap*.jar /app/app.jar

# 中文字体（部署前置项）：PDFBox 内置字体（Helvetica 等）**不含 CJK 字形**，
#   字体缺失时报告中文会被降级为占位文本并置 chineseEmbedded=false——不报错，但线上报告不可用。
#   字体须为 TrueType(glyf) outline：Noto CJK 是 CFF(OTF)，PDFBox 子集化不支持（OTF fonts do not
#   have a glyf table），故改用 fonts-wqy-zenhei（ttc 容器内为 TrueType outline），落点
#   /usr/share/fonts/truetype/wqy/wqy-zenhei.ttc 与 PdfFontLocator 候选一致；REPORT_PDF_FONT 显式指定。
#   自检：启动后 GET /api/v1/system/health 的 reportFont 字段应为 ready（见 HealthController）。
RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-wqy-zenhei \
    && rm -rf /var/lib/apt/lists/*

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone \
    && useradd -r -u 10001 yl && chown -R yl:yl /app
USER yl

EXPOSE 8080

# 健康检查：Spring Boot Actuator
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar --spring.profiles.active=${SPRING_PROFILES_ACTIVE:-prod}"]
