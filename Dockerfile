# 1. Build Stage
FROM maven:3.9-eclipse-temurin-17 as builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# 2. Runtime Stage with AWS CLI
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# ✅ AWS CLI v2 설치
RUN apt-get update && \
    apt-get install -y curl unzip && \
    curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip" && \
    unzip awscliv2.zip && \
    ./aws/install && \
    rm -rf awscliv2.zip aws && \
    apt-get remove -y curl unzip && \
    apt-get autoremove -y && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# JAR 파일 복사
COPY --from=builder /app/target/*.jar app.jar

# 환경 변수
ENV SPRING_PROFILES_ACTIVE=prod
ENV AWS_REGION=ap-northeast-2

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]