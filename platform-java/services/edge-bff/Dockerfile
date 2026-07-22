# edge-bff 镜像（jar 在宿主预构建，避免容器内 gradle 联网）。
# 构建前先在 platform-java/ 执行：./gradlew :services:edge-bff:bootJar
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd --system grassland && useradd --system --gid grassland --home-dir /app grassland
COPY services/edge-bff/build/libs/edge-bff.jar /app/edge-bff.jar
USER grassland
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/edge-bff.jar"]
