# Build stage: uses the committed Gradle wrapper so the image builds exactly like CI and local builds.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 1001 app
WORKDIR /app
COPY --from=build /workspace/build/libs/usage-rating.jar app.jar
USER app
EXPOSE 8080
# exec form so SIGTERM reaches the JVM directly and Spring's graceful shutdown runs.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
