FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
# app.jar로 이름 고정 + 빌드 산출물 확인
RUN mvn -B clean package -DskipTests -DfinalName=app && ls -al target

FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /app/target/app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]