FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY aihub-admin ./aihub-admin
COPY aihub-gateway ./aihub-gateway
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl aihub-gateway -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/aihub-gateway/target/aihub-gateway-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
