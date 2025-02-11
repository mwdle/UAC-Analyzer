# Build stage
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY ./src ./src
COPY ./pom.xml ./pom.xml
RUN mvn clean package

# Run stage
FROM eclipse-temurin:21
COPY --from=build /app/target/*.jar /opt/uac-analyzer/app.jar
WORKDIR /opt/uac-analyzer