# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# 先拷贝 pom 预热依赖层（利用构建缓存）
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
# 再拷贝源码打包
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/training-crm-1.0.0.jar app.jar
EXPOSE 8080
# Redis 地址可用 REDIS_HOST/REDIS_PORT 覆盖（compose 中指向 redis 服务）
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
