# One-shot database bootstrap image. Build the jar on the host first:
# ./gradlew :services:database-bootstrap:bootJar
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
RUN apk upgrade --no-cache
RUN addgroup -S grassland && adduser -S -G grassland -h /app grassland
COPY services/database-bootstrap/build/libs/database-bootstrap.jar /app/database-bootstrap.jar
USER grassland
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow", "-XX:MaxRAMPercentage=75", "-jar", "/app/database-bootstrap.jar"]
