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

# ▼▼▼▼▼▼▼▼▼▼ 이 부분이 가장 중요합니다! ▼▼▼▼▼▼▼▼▼▼
# 컨테이너가 시작될 때 항상 'prod' 프로파일을 사용하도록 환경 변수를 설정합니다.
# 이 라인을 Build Stage가 아닌, 최종 이미지를 만드는 Run Stage로 옮겼습니다. . 
ENV SPRING_PROFILES_ACTIVE=prod
# ▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲

COPY --from=builder /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]