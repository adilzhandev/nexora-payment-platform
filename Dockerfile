FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
COPY nexora-common ./nexora-common
COPY nexora-core ./nexora-core
COPY nexora-events ./nexora-events
COPY nexora-api ./nexora-api
COPY nexora-audit ./nexora-audit
RUN chmod +x gradlew && ./gradlew --no-daemon --max-workers=2 :nexora-api:bootJar :nexora-audit:bootJar

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S nexora && adduser -S -G nexora nexora
WORKDIR /app
ARG MODULE=nexora-api
COPY --from=build --chown=nexora:nexora /workspace/${MODULE}/build/libs/${MODULE}.jar application.jar
USER nexora
ENV AUDIT_PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 CMD wget -qO- http://127.0.0.1:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "application.jar"]
