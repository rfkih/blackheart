# ---- Stage 1: Build ---------------------------------------------------------
FROM gradle:8.7.0-jdk21-alpine AS build
WORKDIR /application

# If you use Kotlin DSL, keep these lines.
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src
# Build only the Spring Boot jar (fewer artifacts than 'gradle build')
RUN gradle bootJar -x test

# ---- Stage 2: Runtime -------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy the boot jar (only one is produced by 'bootJar')
COPY --from=build /application/build/libs/*.jar /app/app.jar

ENV JAVA_OPTS=""
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
