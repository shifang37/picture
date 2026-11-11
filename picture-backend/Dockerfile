FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src src
RUN mvn -B --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/target/picture-backend-0.0.1-SNAPSHOT.jar app.jar
# 8123 业务端口；8124 Actuator 管理端口（observability profile，不映射到宿主机）
EXPOSE 8123 8124
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
