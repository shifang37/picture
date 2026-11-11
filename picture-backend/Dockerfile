FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
# 先只解析依赖：源码改动不会让这一层缓存失效，避免每次构建都重新下载全部依赖
RUN mvn -B --no-transfer-progress -Dmaven.artifact.threads=16 \
    -Dmaven.test.skip=true dependency:resolve
COPY src src
RUN mvn -B --no-transfer-progress -Dmaven.artifact.threads=16 \
    -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
WORKDIR /app
# 升级基础镜像中已修复的 OS 包，避免 Trivy 因可修复高危漏洞让流水线失败
RUN apt-get update \
    && apt-get upgrade -y \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --home-dir /app --no-create-home --shell /usr/sbin/nologin app

COPY --from=build --chown=app:app /app/target/picture-backend-*.jar app.jar

# 容器内存的 75% 作为堆上限，避免 JVM 按宿主机内存取默认值；可在 compose 里用 JAVA_OPTS 覆盖
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport"
# 非 root 运行：应用只写 /tmp 和 stdout，不需要 /app 的写权限
USER app

# 8123 业务端口；8124 Actuator 管理端口（observability profile，不映射到宿主机）
EXPOSE 8123 8124
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
