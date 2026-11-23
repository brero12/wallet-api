FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src src
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre
RUN useradd --system --create-home app
USER app
WORKDIR /app
COPY --from=build /src/build/libs/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]