# Lean runtime image. JARs are built by the host via gradlew (manage.ps1 does
# this for you) and copied in. Building Gradle inside the container was flaky
# on slow networks - the wrapper kept stalling on services.gradle.org.
#
# Compose drives this with JAR_FILE so the same Dockerfile produces both the
# trading and research images:
#   blackheart           -> JAR_FILE=build/libs/blackheart-trading-0.0.1-SNAPSHOT.jar
#   blackheart-research  -> JAR_FILE=build/libs/blackheart-research-0.0.1-SNAPSHOT.jar
ARG JAR_FILE=build/libs/blackheart-0.0.1-SNAPSHOT.jar

FROM eclipse-temurin:21-jre
ARG JAR_FILE
WORKDIR /app
COPY ${JAR_FILE} /app/app.jar

EXPOSE 8080

# ENTRYPOINT via shell so $JAVA_OPTS / $SERVER_PORT expand at start time.
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar --server.port=${SERVER_PORT:-8080}"]
