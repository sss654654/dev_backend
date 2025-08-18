#FROM openjdk:17
#COPY target/*.jar app.jar
#EXPOSE 8080
#CMD ["java", "-jar" ,"app.jar"]

# 1. Build Stage: 코드를 컴파일하고 .jar 파일을 만드는 단계
FROM maven:3.9-eclipse-temurin-17 as builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# 2. Run Stage: 빌드된 .jar 파일을 실행하는 단계
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]