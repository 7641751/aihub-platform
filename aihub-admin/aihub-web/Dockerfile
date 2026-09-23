FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY aihub-admin ./aihub-admin
COPY aihub-gateway ./aihub-gateway
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl aihub-admin/aihub-web -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/aihub-admin/aihub-web/target/aihub-web-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
