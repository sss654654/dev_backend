# Stage 1: Build the application with Gradle
# Java 17 버전을 명시하여 빌드 환경의 일관성을 보장합니다.
FROM amazoncorretto:17-alpine-jdk as builder
WORKDIR /app

# Gradle 관련 파일들을 먼저 복사하여 종속성 캐싱을 활용합니다.
COPY gradlew .
COPY gradle ./gradle
COPY build.gradle .
COPY settings.gradle .

# pom.xml이 있다면 Maven 프로젝트이므로 pom.xml을 복사합니다.
# 사용자님의 프로젝트는 Maven 기반이므로 아래 라인을 사용합니다.
COPY pom.xml .

# 소스 코드를 복사합니다.
COPY src ./src

# Maven Wrapper를 사용하여 애플리케이션을 빌드합니다.
RUN ./mvnw package -DskipTests

# Stage 2: Create the final, slim image
# JRE(Java Runtime Environment)만 포함된 더 가벼운 이미지 사용
FROM amazoncorretto:17-alpine-jre
WORKDIR /app

# 빌드 스테이지에서 생성된 JAR 파일만 최종 이미지로 복사합니다.
COPY --from=builder /app/target/*.jar app.jar

# EKS 환경에서 'prod' 프로파일을 사용하도록 환경 변수를 설정합니다.
# 이 설정에 따라 application.yml 내의 prod 프로파일이 활성화됩니다.
ENV SPRING_PROFILES_ACTIVE=prod

# 컨테이너가 시작될 때 실행할 명령
ENTRYPOINT ["java", "-jar", "app.jar"]