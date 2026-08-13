# marketplace-service 镜像（jar 在宿主预构建，避免容器内 gradle 联网）。
# 构建前先在 platform-java/ 执行：./gradlew :services:marketplace-service:bootJar
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
RUN apk upgrade --no-cache && apk add --no-cache wget
RUN addgroup -S grassland && adduser -S -G grassland -h /app grassland
COPY services/marketplace-service/build/libs/marketplace-service.jar /app/marketplace-service.jar
USER grassland
EXPOSE 8083
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow", "-Dio.grpc.netty.shaded.io.netty.noUnsafe=true", "-XX:MaxRAMPercentage=75", "-jar", "/app/marketplace-service.jar"]
