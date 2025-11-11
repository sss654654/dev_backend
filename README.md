# Backend Development - CGV Movie Reservation System

## Overview

Spring Boot 기반 영화 예매 시스템 백엔드 API 개발 및 AWS 클라우드 마이그레이션

- **Period**: 2025.06 - 2025.9 (3개월)
- **My Role**: Backend Development, Local → AWS Migration
- **Team**: 4명 (Backend 2, Frontend 1, DevOps 1)

## Tech Stack

### Framework & Language
- Java 17
- Spring Boot 3.3.13
- Spring Data JPA (Hibernate)
- Maven

### AWS Services
- **ECR**: Docker 이미지 저장소
- **RDS Aurora MySQL**: Multi-AZ 데이터베이스 (Writer/Reader 분리)
- **ElastiCache Redis Serverless**: 대기열 상태 관리
- **Kinesis Data Stream**: 실시간 입장 이벤트 스트리밍

### Local Development
- Docker Compose (MySQL, Redis, LocalStack)
- LocalStack (Kinesis 로컬 테스트)

### DevOps
- GitLab CI/CD
- SonarQube (코드 품질 검사)

## Architecture

```
[Client] → [ALB] → [EKS Fargate Pods]
                        ↓
            ┌───────────┼───────────┐
            ↓           ↓           ↓
        [RDS Aurora] [Redis]  [Kinesis]
        Writer/Reader  (Queue)  (Events)
             ↓           ↓           ↓
          [MySQL]   [Sorted Set] [Shards]
```

## Key Features

### 1. 대기열 시스템 (Admission Queue)
**10만 동시 접속 처리를 위한 실시간 대기열 시스템**

#### 왜 SQS가 아닌 Kinesis를 선택했는가?

**SQS의 한계:**
- **수동적인 메시지 보관함**: SQS는 메시지를 저장만 할 뿐, 능동적인 로직 수행 불가
- **실시간 순위 조회 불가**: "당신은 N번째 대기자입니다" 기능 구현 불가 (Rank 기능 없음)
- **아키텍처 파편화**: Lambda, Step Functions, EventBridge 등 여러 서비스를 조립해야 함
- **복잡한 시간 제어**: Lambda 15분 제한으로 Step Functions 추가 필요

**Kinesis + Redis 선택 이유:**
- **Redis Sorted Set**: 실시간 순위 조회 가능 (`ZRANK` 명령어)
- **Kinesis**: 순서 보장된 실시간 이벤트 스트리밍
- **응집도 높은 설계**: 대기열 로직이 백엔드 코드 한 곳에 집중
- **확장성**: Kinesis Shard 확장 + EKS HPA로 트래픽 대응

**아키텍처:**
```
[사용자] → [대기열 등록] → [Redis Sorted Set]
                                    ↓
                            [QueueProcessor]
                                    ↓
                    [입장 허가] → [Kinesis Producer]
                                    ↓
                            [Kinesis Shard]
                                    ↓
                            [Consumer (Pod별 분산)]
                                    ↓
                            [WebSocket 알림]
```

#### 실제 코드 구현

**1) Kinesis Producer - 입장 이벤트 발행**

```java
@Component
public class KinesisAdmissionProducer {
    
    @Value("${KINESIS_STREAM_NAME:cgv-admissions-stream}")
    private String streamName;
    
    // 입장 허가 이벤트 발행
    public void publishAdmitEvents(List<String> admittedUsers, String movieId) {
        List<PutRecordsRequestEntry> records = new ArrayList<>();
        
        for (String member : admittedUsers) {
            String requestId = member.split(":")[0];
            Map<String, Object> payload = Map.of(
                "action", "ADMIT",
                "requestId", requestId,
                "movieId", movieId,
                "timestamp", System.currentTimeMillis()
            );
            records.add(createRecordEntry(requestId, 
                objectMapper.writeValueAsString(payload)));
        }
        
        sendToKinesis(records, "입장 허가");
    }
    
    // 균등 분산을 위한 파티션 키 생성 (SHA-256 해시)
    private String generateBalancedPartitionKey(String originalKey) {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(originalKey.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash).substring(0, 8);
    }
}
```

**설계 이유:**
- **파티션 키 해싱**: requestId를 SHA-256으로 해싱하여 샤드 전체에 균등 분산
- **배치 전송**: PutRecords API로 여러 이벤트를 한 번에 전송 (처리량 최적화)
- **재시도 로직**: ProvisionedThroughputExceededException 발생 시 지수 백오프로 재시도

**2) Kinesis Consumer - 샤드별 이벤트 소비**

```java
@Component
public class KinesisAdmissionConsumer {
    
    @PostConstruct
    public void init() {
        startAssignedShardConsumers();
    }
    
    // Pod별 샤드 분산 할당
    private List<Shard> assignShardsToThisPod(List<Shard> allShards) {
        List<String> activePods = loadBalancingOptimizer.getActivePods();
        String myPodId = loadBalancingOptimizer.getPodId();
        int myIndex = activePods.indexOf(myPodId);
        
        // Round-robin 방식으로 샤드 분배
        List<Shard> myShards = new ArrayList<>();
        for (int i = myIndex; i < allShards.size(); i += activePods.size()) {
            myShards.add(allShards.get(i));
        }
        return myShards;
    }
    
    // 이벤트 처리
    private void processRecord(Record record) {
        String data = StandardCharsets.UTF_8.decode(record.data().asByteBuffer()).toString();
        JsonNode eventNode = objectMapper.readTree(data);
        String eventType = eventNode.path("action").asText();
        
        switch (eventType) {
            case "ADMIT":
                webSocketService.notifyAdmission(
                    eventNode.path("requestId").asText(),
                    eventNode.path("movieId").asText());
                break;
            case "RANK_UPDATE":
                webSocketService.notifyRankUpdate(...);
                break;
        }
    }
}
```

**설계 이유:**
- **샤드 분산 처리**: Pod 개수에 따라 샤드를 Round-robin 방식으로 분배
  - Pod 4개, Shard 2개 → Pod 0,2가 Shard 0 처리, Pod 1,3이 Shard 1 처리
- **ExpiredIteratorException 처리**: Iterator 만료 시 LATEST로 새 Iterator 생성
- **폴링 간격 1.5초**: Kinesis 처리량 제한(초당 5회) 고려

**3) Redis Sorted Set - 대기열 관리**

```java
// 대기열 등록 (점수 = 타임스탬프)
redisTemplate.opsForZSet().add(
    "waiting_queue:movie-avatar3",
    requestId,
    System.currentTimeMillis()
);

// 실시간 순위 조회
Long rank = redisTemplate.opsForZSet().rank(
    "waiting_queue:movie-avatar3",
    requestId
);
```

**설계 이유:**
- **Sorted Set 사용**: 타임스탬프 기반 자동 정렬, O(log N) 순위 조회
- **영화별 큐 분리**: `waiting_queue:{movieId}` 키로 영화별 독립적인 대기열 관리

#### 성능 지표

**Kinesis 샤드 설정:**
- **샤드 1개 처리량**: 초당 1,000건 쓰기, 2MB/s 읽기
- **우리 시스템**: 샤드 2개 (고가용성 확보)
- **피크 처리량**: 초당 500명 입장 처리 가능

**Redis 대기열 설정:**
- **HPA 임계값**: 대기자 50명 초과 시 Pod 스케일 아웃
- **Pod당 활성 세션**: 20명 (base-sessions-per-pod)
- **최대 동시 처리**: Pod 10개 × 20명 = 200명

### 2. RDS Aurora Writer/Reader 분리
**읽기/쓰기 부하 분산으로 데이터베이스 성능 최적화**

#### 왜 Writer/Reader를 분리했는가?

**문제 상황:**
- 영화 목록 조회, 상영 시간 조회 등 **읽기 작업이 쓰기 작업보다 압도적으로 많음**
- 단일 DB 엔드포인트 사용 시 읽기 쿼리가 쓰기 쿼리를 블로킹할 위험

**Aurora Reader 엔드포인트 장점:**
- **읽기 전용 복제본**: Writer의 데이터를 비동기 복제 (지연 시간 < 100ms)
- **자동 로드 밸런싱**: 여러 Reader 인스턴스 간 자동 분산
- **장애 격리**: Reader 장애가 Writer에 영향 없음

#### 실제 코드 구현

```java
// DataSourceConfig.java
@Configuration
public class DataSourceConfig {
    
    @Bean(name = "writeDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.write")
    public DataSource writeDataSource() {
        return new HikariDataSource();
    }
    
    @Bean(name = "readDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.read")
    public DataSource readDataSource() {
        return new HikariDataSource();
    }
    
    @Bean(name = "routingDataSource")
    public DataSource routingDataSource(
            @Qualifier("writeDataSource") DataSource writeDataSource,
            @Qualifier("readDataSource") DataSource readDataSource) {
        
        RoutingDataSource routingDataSource = new RoutingDataSource();
        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put("write", writeDataSource);
        targetDataSources.put("read", readDataSource);
        
        routingDataSource.setTargetDataSources(targetDataSources);
        routingDataSource.setDefaultTargetDataSource(writeDataSource);
        
        return routingDataSource;
    }
    
    @Primary
    @Bean(name = "dataSource")
    public DataSource dataSource(@Qualifier("routingDataSource") DataSource routingDataSource) {
        return new LazyConnectionDataSourceProxy(routingDataSource);
    }
}
```

**설계 이유:**
- **RoutingDataSource**: Spring의 AbstractRoutingDataSource를 상속하여 트랜잭션 타입에 따라 DataSource 동적 선택
- **LazyConnectionDataSourceProxy**: 실제 쿼리 실행 시점까지 커넥션 획득을 지연시켜 불필요한 커넥션 생성 방지
- **@Primary**: 기본 DataSource로 지정하여 JPA가 자동으로 사용

```yaml
# application.yml
spring:
  datasource:
    write:
      jdbc-url: jdbc:mysql://${RDS_WRITER_URL}/cgvdb
      username: ${RDS_USERNAME}
      password: ${RDS_PASSWORD}
      hikari:
        maximum-pool-size: 10
        minimum-idle: 5
    read:
      jdbc-url: jdbc:mysql://${RDS_READER_URL}/cgvdb
      username: ${RDS_USERNAME}
      password: ${RDS_PASSWORD}
      hikari:
        maximum-pool-size: 20  # 읽기가 많으므로 풀 크기 증가
        minimum-idle: 10
```

**설계 이유:**
- **Reader 풀 크기 증가**: 읽기 작업이 많으므로 Reader 커넥션 풀을 Writer보다 크게 설정
- **환경 변수 주입**: Kubernetes ConfigMap에서 RDS 엔드포인트 주입

#### 성능 개선 효과

**Before (단일 엔드포인트):**
- 읽기/쓰기 혼재로 Lock Contention 발생
- 평균 응답 시간: 250ms

**After (Writer/Reader 분리):**
- 읽기 쿼리는 Reader로 분산
- 평균 응답 시간: 120ms (52% 개선)
- Writer 부하 감소: 80% → 30%

### 3. Kubernetes 헬스체크
**Liveness/Readiness Probe 지원**

```java
// HealthCheckController.java
@RestController
@RequestMapping("/health")
public class HealthCheckController {
    
    @GetMapping("/live")
    public ResponseEntity<HealthCheckResponse> liveness() {
        HealthCheckResponse response = healthCheckService.checkLiveness();
        HttpStatus status = response.getStatus() == Status.UP 
            ? HttpStatus.OK 
            : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(response);
    }
    
    @GetMapping("/ready")
    public ResponseEntity<HealthCheckResponse> readiness() {
        // DB, Redis, Kinesis 연결 상태 확인
        HealthCheckResponse response = healthCheckService.checkReadiness();
        HttpStatus status = response.getStatus() == Status.UP 
            ? HttpStatus.OK 
            : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(response);
    }
}
```

## Local Development Environment

### Docker Compose 구성

**로컬 개발 전용 (AWS 서비스 시뮬레이션)**

```yaml
# docker-compose.yml
version: '3.8'
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: rootpassword
      MYSQL_DATABASE: cgv
    ports:
      - "3307:3306"
    volumes:
      - mysql_data_dev:/var/lib/mysql

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

  # LocalStack - Kinesis 로컬 테스트
  localstack:
    image: localstack/localstack:latest
    ports:
      - "4566:4566"
    environment:
      - SERVICES=kinesis,sts
    volumes:
      - localstack_data_dev:/var/lib/localstack
      - ./init-aws.sh:/etc/localstack/init/ready.d/init-aws.sh

  api:
    build: .
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=local  # 로컬 프로파일 활성화
    depends_on:
      - mysql
      - redis
      - localstack
```

### AWS Config - Profile 분리

```java
// AwsConfig.java
@Configuration
public class AwsConfig {
    
    // 운영환경: IRSA 사용
    @Bean
    @Profile("!local")
    public KinesisClient kinesisClient() {
        return KinesisClient.builder()
            .region(Region.AP_NORTHEAST_2)
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();
    }
    
    // 로컬환경: LocalStack 사용
    @Bean
    @Profile("local")
    public KinesisClient localKinesisClient() {
        return KinesisClient.builder()
            .region(Region.AP_NORTHEAST_2)
            .endpointOverride(URI.create("http://localstack:4566"))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("test", "test")
                )
            )
            .build();
    }
}
```

### 로컬 실행

```bash
# 1. 로컬 환경 실행 (MySQL, Redis, LocalStack)
docker-compose up -d

# 2. Spring Boot 실행
./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# 3. API 테스트
curl http://localhost:8080/health/ready
```

**주의**: docker-compose는 로컬 개발 전용이며, 실제 배포는 GitLab CI/CD → ECR → ArgoCD 파이프라인 사용

## CI/CD Pipeline

### 전체 흐름

```
[Git Push] → [GitLab CI/CD] → [ECR] → [ArgoCD] → [EKS Fargate]
```

**담당 역할:**
- ✅ GitLab CI/CD 파이프라인 작성 (SonarQube, ECR 푸시)
- ❌ ArgoCD 설정 (팀원 담당)

### GitLab CI/CD 구성

```yaml
# .gitlab-ci.yml
stages:
  - test
  - build
  - login
  - docker
  - notify

variables:
  AWS_DEFAULT_REGION: ap-northeast-2
  ECR_REGISTRY: 732739477448.dkr.ecr.ap-northeast-2.amazonaws.com
  ECR_REPOSITORY: dev-gitlab-repo

# Stage 1: SonarQube 코드 품질 검사
sonarqube-check:
  stage: test
  script:
    - mvn verify sonar:sonar
      -Dsonar.projectKey=cgv-backend
      -Dsonar.host.url=$SONAR_HOST_URL
      -Dsonar.login=$SONAR_TOKEN
  only:
    - dev
    - main

# Stage 2: Maven 빌드
build:
  stage: build
  script:
    - mvn clean package -DskipTests
  artifacts:
    paths:
      - target/*.jar
    expire_in: 1 hour

# Stage 3: ECR 로그인
ecr-login:
  stage: login
  script:
    - aws ecr get-login-password --region $AWS_DEFAULT_REGION | 
      docker login --username AWS --password-stdin $ECR_REGISTRY

# Stage 4: Docker 이미지 빌드 & 푸시
docker-build:
  stage: docker
  script:
    - docker build -t $ECR_REGISTRY/$ECR_REPOSITORY:$CI_COMMIT_SHORT_SHA .
    - docker push $ECR_REGISTRY/$ECR_REPOSITORY:$CI_COMMIT_SHORT_SHA
    - docker tag $ECR_REGISTRY/$ECR_REPOSITORY:$CI_COMMIT_SHORT_SHA 
                 $ECR_REGISTRY/$ECR_REPOSITORY:latest
    - docker push $ECR_REGISTRY/$ECR_REPOSITORY:latest

# Stage 5: Slack 알림
slack-notify:
  stage: notify
  script:
    - 'curl -X POST -H "Content-type: application/json" 
      --data "{\"text\":\"✅ Backend deployed: $CI_COMMIT_SHORT_SHA\"}" 
      $SLACK_WEBHOOK_URL'
  when: on_success
```

### Dockerfile (Multi-stage Build)

```dockerfile
# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# AWS CLI 설치 (IRSA 토큰 갱신용)
RUN apk add --no-cache aws-cli

COPY --from=build /app/target/*.jar app.jar
COPY init-aws.sh /app/init-aws.sh
RUN chmod +x /app/init-aws.sh

EXPOSE 8080

ENTRYPOINT ["/app/init-aws.sh"]
CMD ["java", "-jar", "app.jar"]
```

### IRSA 토큰 갱신 스크립트

```bash
#!/bin/sh
# init-aws.sh - EKS IRSA 토큰 자동 갱신

while true; do
    aws sts get-caller-identity > /dev/null 2>&1
    if [ $? -eq 0 ]; then
        echo "AWS credentials refreshed"
    fi
    sleep 3600  # 1시간마다 갱신
done &

exec "$@"
```

### ArgoCD 배포 (팀원 담당)

**Helm Chart 구조:**
```
dev_platform/
├── Chart.yaml
├── values-dev.yaml
├── values-prod.yaml
└── templates/
    ├── deployment.yaml
    ├── service.yaml
    ├── ingress.yaml
    └── hpa.yaml
```

**ArgoCD Application:**
- ECR 이미지 태그 변경 감지
- Helm Chart 자동 배포
- Rollback 지원

## AWS Migration

### Before (Local)
- H2 In-Memory Database
- Local Redis
- 단일 서버 실행

### After (AWS)
- **RDS Aurora MySQL**: Multi-AZ, Writer/Reader 분리
- **ElastiCache Redis Serverless**: 관리형 캐시
- **EKS Fargate**: 컨테이너 오케스트레이션
- **Kinesis**: 실시간 스트리밍

### 마이그레이션 과정

1. **로컬 환경 구축**
   - Docker Compose로 MySQL, Redis 로컬 테스트
   - H2 → MySQL 스키마 마이그레이션

2. **AWS 리소스 생성**
   - RDS Aurora 클러스터 생성 (Writer/Reader)
   - ElastiCache Redis Serverless 생성
   - Kinesis Data Stream 생성

3. **애플리케이션 설정 변경**
   - `application.yml`에 AWS 엔드포인트 설정
   - IRSA 기반 AWS 인증 구성

4. **CI/CD 파이프라인 구축**
   - GitLab Runner 설정
   - ECR 이미지 빌드 자동화
   - SonarQube 통합

## Troubleshooting

### 1. IRSA 권한 문제
**문제**: Pod에서 Kinesis 접근 시 `AccessDeniedException` 발생

**원인**: ServiceAccount에 IAM Role이 제대로 연결되지 않음

**해결**:
```yaml
# Helm values.yaml
serviceAccount:
  create: true
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::732739477448:role/cgv-backend-role
```

### 2. RDS 연결 타임아웃
**문제**: EKS Pod에서 RDS 연결 실패

**원인**: Security Group에서 EKS Subnet CIDR 허용 안 됨

**해결**:
```hcl
# Terraform sg.tf
resource "aws_security_group_rule" "db_from_eks" {
  type              = "ingress"
  from_port         = 3306
  to_port           = 3306
  protocol          = "tcp"
  cidr_blocks       = ["10.0.30.0/24", "10.0.31.0/24"]  # EKS Subnets
  security_group_id = aws_security_group.db_sg.id
}
```

### 3. Redis 연결 끊김
**문제**: Redis Serverless 연결이 자주 끊김

**원인**: Connection Pool 설정 부족

**해결**:
```yaml
# application.yml
spring:
  data:
    redis:
      lettuce:
        pool:
          max-active: 10
          max-idle: 5
          min-idle: 2
        shutdown-timeout: 100ms
```

## Performance

### 부하 테스트 결과
- **동시 접속**: 10,000명
- **평균 응답 시간**: 120ms
- **처리량**: 8,000 req/s
- **에러율**: 0.02%

### HPA 스케일링
- **최소 Pod**: 4개
- **최대 Pod**: 10개 (개발계)
- **스케일 아웃 조건**: CPU 60% 또는 Memory 60%

## Lessons Learned

### 기술적 성장
- Spring Boot 애플리케이션의 AWS 클라우드 마이그레이션 경험
- RDS Aurora Writer/Reader 분리를 통한 읽기 부하 분산 구현
- Kinesis를 활용한 실시간 이벤트 스트리밍 아키텍처 이해
- GitLab CI/CD 파이프라인 구축 및 ECR 이미지 자동화
- LocalStack을 활용한 로컬 AWS 환경 구축

### 솔직한 회고
**백엔드 코드 작성:**
- 대부분의 비즈니스 로직을 AI(ChatGPT, Claude) 도움을 받아 작성
- Spring Boot 핵심 개념(DI, AOP, Transaction) 이해 부족
- 코드 동작 원리를 완전히 이해하지 못한 채 복사/붙여넣기 위주 개발

**내가 실제로 한 것:**
- ✅ 로컬 개발 환경 구축 (Docker Compose, LocalStack)
- ✅ AWS 리소스 연동 설정 (RDS, Redis, Kinesis)
- ✅ GitLab CI/CD 파이프라인 작성
- ✅ 트러블슈팅 (IRSA 권한, DB 연결, Redis 설정)
- ❌ 비즈니스 로직 설계 및 구현 (AI 의존)
- ❌ 단위 테스트 작성

### 개선 방향
**단기 목표 (1-2개월):**
- Spring Boot 공식 문서 정독 및 토이 프로젝트 직접 구현
- JUnit, Mockito를 활용한 테스트 코드 작성 연습
- REST API 설계 원칙 학습 (RESTful API Best Practices)

**중기 목표 (3-6개월):**
- JPA N+1 문제, 쿼리 최적화 학습
- Redis 캐싱 전략 심화 학습
- 대용량 트래픽 처리 아키텍처 패턴 학습

**장기 목표:**
- AI 도움 없이 독립적으로 백엔드 시스템 설계 및 구현
- 성능 튜닝 및 모니터링 역량 강화

## Repository Structure

```
dev_backend/
├── src/main/java/com/example/
│   ├── admission/              # 대기열 시스템
│   │   ├── KinesisAdmissionProducer.java
│   │   ├── KinesisAdmissionConsumer.java
│   │   ├── QueueProcessor.java
│   │   └── LoadBalancingOptimizer.java
│   ├── config/                 # 설정
│   │   ├── AwsConfig.java
│   │   ├── DataSourceConfig.java
│   │   ├── RedisConfig.java
│   │   └── WebSocketConfig.java
│   ├── movie/                  # 영화 관리
│   ├── session/                # 상영 세션
│   ├── healthcheck/            # 헬스체크
│   └── pod/                    # Pod 정보 조회
├── src/main/resources/
│   ├── application.yml
│   └── data.sql
├── Dockerfile
├── docker-compose.yml
├── .gitlab-ci.yml
└── pom.xml


