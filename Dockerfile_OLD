FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-alpine

WORKDIR /spring-boot

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/spring-boot/app.jar"]