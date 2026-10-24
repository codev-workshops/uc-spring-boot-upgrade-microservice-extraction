# syntax=docker/dockerfile:1
# Spring Boot backend: build the jar with Gradle 7.4 / JDK 11, run it on a JRE 11.
FROM gradle:7.4-jdk11 AS build
WORKDIR /workspace
COPY build.gradle ./
COPY gradle ./gradle
COPY src ./src
RUN gradle bootJar --no-daemon -x test \
    && cp build/libs/*-SNAPSHOT.jar /workspace/app.jar

FROM eclipse-temurin:11-jre
# Runs as UID/GID 1000 (the base image's unprivileged user); the chart sets fsGroup 1000.
RUN mkdir -p /app /data && chown 1000:1000 /data
COPY --from=build /workspace/app.jar /app/app.jar
# The default datasource (jdbc:sqlite:dev.db) is relative to the working directory,
# so the SQLite file lands in /data, where the Helm chart mounts the PVC.
WORKDIR /data
USER 1000:1000
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
