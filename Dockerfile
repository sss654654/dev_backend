FROM openjdk:17

COPY target/user-management-1.0.0.jar app.jar

EXPOSE 8080

RUN ["java" -jar "app.jar"]