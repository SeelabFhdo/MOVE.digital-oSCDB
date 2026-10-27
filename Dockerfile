# Builds the OSCDB application image. Used by compose.deploy.yaml.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 oscdb
WORKDIR /app
COPY --from=build /src/target/oscdb-*.jar app.jar
USER oscdb
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
