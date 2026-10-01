# 构建阶段：Maven + JDK 17
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# 运行阶段：JRE 17
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/training-crm-1.0.0.jar app.jar
EXPOSE 8080
# Redis 地址通过环境变量注入（compose 中为 redis:6379）
ENV REDIS_HOST=localhost REDIS_PORT=6379
ENTRYPOINT ["java", "-jar", "app.jar"]
