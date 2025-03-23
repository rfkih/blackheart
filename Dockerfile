# Stage 1: Build Stage
FROM gradle:8.7.0-jdk21-alpine AS build
WORKDIR /application

COPY build.gradle .
COPY settings.gradle .
COPY src src

RUN gradle build -x test

# Stage 2: Deployment Stage
FROM eclipse-temurin:21-jdk-alpine
WORKDIR /application

# Updated jar path here!
COPY --from=build /application/build/libs/*.jar /application/

ENV JAVA_OPTS=""
ENTRYPOINT exec java $JAVA_OPTS -jar /application/blackheart-0.0.1-SNAPSHOT.jar
EXPOSE 8080
