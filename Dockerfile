# Dockerfile
FROM maven:3.9-eclipse-temurin-17 as builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
# dev 프로파일로 빌드
RUN mvn clean package -DskipTests -Pdev

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar

# 환경 변수 dev로 설정
ENV SPRING_PROFILES_ACTIVE=dev
ENV AWS_REGION=ap-northeast-2

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "-Dspring.profiles.active=dev", "app.jar"]