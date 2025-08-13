# Multi-stage build
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

# Maven dependency 캐싱을 위해 pom.xml 먼저 복사
COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline

# 소스 코드 복사 및 빌드
COPY src ./src
RUN mvn -B -DskipTests package

# Runtime stage
FROM eclipse-temurin:17-jre-alpine

WORKDIR /spring-boot

# GitLab CI에서 빌드된 jar 파일과 Docker 내부에서 빌드된 jar 파일을 모두 처리
# GitLab CI artifacts가 있으면 그것을 우선 사용, 없으면 Docker 내부 빌드 결과 사용
COPY --from=build /app/target/*.jar app.jar

# GitLab CI에서 전달된 jar 파일이 있다면 덮어쓰기 (선택적)
COPY target/*SNAPSHOT.jar app.jar* 2>/dev/null || true

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/spring-boot/app.jar"]