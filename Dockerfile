FROM maven:3.9.9-eclipse-temurin-8 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:8-jre-alpine
WORKDIR /app
COPY --from=build /workspace/target/hmdp-pro-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java","-XX:+UseContainerSupport","-jar","/app/app.jar"]
