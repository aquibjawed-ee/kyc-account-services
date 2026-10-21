# Builds one module of the multi-module project: docker build --build-arg MODULE=account-creation-service .
FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /build
COPY . .
RUN mvn -B -q -pl ${MODULE} -am package -DskipTests

FROM eclipse-temurin:21-jre
ARG MODULE
WORKDIR /app
COPY --from=build /build/${MODULE}/target/${MODULE}-*-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
