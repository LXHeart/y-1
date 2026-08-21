# One-shot release migration job image. Build the jar on the host first:
# ./gradlew :services:release-migrator:bootJar
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
RUN apk upgrade --no-cache
RUN addgroup -S grassland && adduser -S -G grassland -h /app grassland
COPY services/release-migrator/build/libs/release-migrator.jar /app/release-migrator.jar
USER grassland
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow", "-XX:MaxRAMPercentage=75", "-jar", "/app/release-migrator.jar"]
