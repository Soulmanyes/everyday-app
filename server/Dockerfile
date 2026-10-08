# Multi-stage build. Works on arm64 (Oracle Ampere A1) and amd64.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon buildFatJar

FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home app
USER app
WORKDIR /app
COPY --from=build /src/build/libs/everyday-server-all.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
