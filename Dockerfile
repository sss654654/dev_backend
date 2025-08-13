FROM openjdk:17

COPY target/user-management-1.0.0.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java" -jar "app.jar"]