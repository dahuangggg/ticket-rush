FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -q -DskipTests dependency:go-offline
COPY src src
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
ENV SERVER_PORT=8081
COPY --from=build /workspace/target/*.jar /app/ticket-rush.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/ticket-rush.jar"]
