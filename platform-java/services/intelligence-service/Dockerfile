# intelligence-service 镜像（jar 在宿主预构建，避免容器内 gradle 联网）。
# 构建前先在 platform-java/ 执行：./gradlew :services:intelligence-service:bootJar
FROM eclipse-temurin:25-jre AS java-runtime
FROM node:20-bookworm-slim
COPY --from=java-runtime /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:${PATH}"
WORKDIR /app
RUN printf 'Acquire::Retries "5";\n' > /etc/apt/apt.conf.d/80retries \
    && apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates
RUN sed -i 's|http://deb.debian.org|https://mirrors.ustc.edu.cn|g; s|http://security.debian.org|https://mirrors.ustc.edu.cn|g' \
      /etc/apt/sources.list.d/debian.sources \
    && apt-get update \
    && apt-get install -y --no-install-recommends chromium ffmpeg \
    && npm install --global --omit=dev playwright-core@1.55.0 \
    && mkdir -p /opt/playwright-driver \
    && ln -s /usr/lib/node_modules/playwright-core /opt/playwright-driver/package \
    && ln -s /usr/bin/node /opt/playwright-driver/node \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system grassland \
    && useradd --system --gid grassland --home-dir /app grassland \
    && mkdir -p /var/lib/grassland-media/tmp \
    && chown -R grassland:grassland /var/lib/grassland-media
RUN ln -sfn /usr/local/lib/node_modules/playwright-core /opt/playwright-driver/package \
    && ln -sfn /usr/local/bin/node /opt/playwright-driver/node
COPY services/intelligence-service/build/libs/intelligence-service.jar /app/intelligence-service.jar
USER grassland
EXPOSE 8086
ENTRYPOINT ["java", "-Dplaywright.cli.dir=/opt/playwright-driver", "-Djava.io.tmpdir=/var/lib/grassland-media/tmp", "-XX:MaxRAMPercentage=75", "-jar", "/app/intelligence-service.jar"]
