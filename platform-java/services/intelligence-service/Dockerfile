# intelligence-service 镜像（jar 在宿主预构建，避免容器内 gradle 联网）。
# 构建前先在 platform-java/ 执行：./gradlew :services:intelligence-service:bootJar
FROM eclipse-temurin:25-jre-alpine
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:${PATH}"
WORKDIR /app
RUN apk upgrade --no-cache \
    && apk add --no-cache nodejs chromium ffmpeg wget \
    && apk add --no-cache --virtual .playwright-install npm \
    && npm install --global --omit=dev playwright-core@1.55.0 \
    && mkdir -p /opt/playwright-driver \
    && ln -s /usr/local/lib/node_modules/playwright-core /opt/playwright-driver/package \
    && ln -s /usr/bin/node /opt/playwright-driver/node \
    && apk del .playwright-install \
    && addgroup -S grassland \
    && adduser -S -G grassland -h /app grassland \
    && mkdir -p /var/lib/grassland-media/tmp \
    && chown -R grassland:grassland /var/lib/grassland-media
COPY services/intelligence-service/build/libs/intelligence-service.jar /app/intelligence-service.jar
USER grassland
EXPOSE 8086
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow", "-Dplaywright.cli.dir=/opt/playwright-driver", "-Djava.io.tmpdir=/var/lib/grassland-media/tmp", "-XX:MaxRAMPercentage=75", "-jar", "/app/intelligence-service.jar"]
