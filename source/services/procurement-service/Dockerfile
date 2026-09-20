FROM maven:3.9.12-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B verify
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install --yes --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && useradd --system --uid 10001 --create-home appuser
WORKDIR /app
COPY --from=build /workspace/target/procurement-service-*.jar app.jar
USER 10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 CMD ["curl","--fail","--silent","http://127.0.0.1:8080/actuator/health/liveness"]
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/app/app.jar"]
