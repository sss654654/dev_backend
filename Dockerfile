FROM eclipse-temurin:17-jre-alpine

WORKDIR /spring-boot

COPY build/libs/*SNAPSHOT.jar app.jar

ENTRYPOINT ["java", "-jar", "/spring-boot/app.jar"]