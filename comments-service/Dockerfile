FROM eclipse-temurin:11-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:11-jre
WORKDIR /app
RUN mkdir -p /data
COPY --from=build /workspace/build/libs/*.jar app.jar
ENV COMMENTS_DB_PATH=/data/comments.db
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
