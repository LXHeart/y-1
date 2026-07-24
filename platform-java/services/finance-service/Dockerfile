# finance-service 镜像（jar 在宿主预构建，避免容器内 gradle 联网）。
# 构建前先在 platform-java/ 执行：./gradlew :services:finance-service:bootJar
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd --system grassland && useradd --system --gid grassland --home-dir /app grassland
COPY services/finance-service/build/libs/finance-service.jar /app/finance-service.jar
USER grassland
EXPOSE 8084
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/finance-service.jar"]
