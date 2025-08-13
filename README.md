# User Management Backend

Spring Boot 기반의 사용자 관리 백엔드 API 서버입니다.

## 기술 스택

- **Spring Boot 3.2.0**
- **Spring Data JPA (Hibernate)**
- **H2 Database** (개발용)
- **Maven 3.9.5**
- **Java 17**

## 주요 기능

- 사용자 CRUD 작업 (생성, 조회, 수정, 삭제)
- 이메일 기반 사용자 검색
- 사용자 존재 여부 확인
- 자동 생성/수정 시간 관리 (JPA Auditing)

## API 엔드포인트

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/api/users` | 모든 사용자 조회 |
| GET | `/api/users/{id}` | 특정 사용자 조회 |
| GET | `/api/users/email/{email}` | 이메일로 사용자 조회 |
| POST | `/api/users` | 새 사용자 생성 |
| PUT | `/api/users/{id}` | 사용자 정보 수정 |
| DELETE | `/api/users/{id}` | 사용자 삭제 |
| GET | `/api/users/{id}/exists` | 사용자 존재 여부 확인 |
| GET | `/api/users/email/{email}/exists` | 이메일 존재 여부 확인 |

## 실행 방법

### 1. 프로젝트 빌드
```bash
./mvnw clean compile
```

### 2. 애플리케이션 실행
```bash
./mvnw spring-boot:run
```

### 3. H2 데이터베이스 콘솔 접속
- URL: http://localhost:8080/h2-console
- JDBC URL: `jdbc:h2:mem:testdb`
- Username: `sa`
- Password: `password`

### 4. API 테스트 예제

#### 모든 사용자 조회
```bash
curl -X GET http://localhost:8080/api/users
```

#### 새 사용자 생성
```bash
curl -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"name": "홍길동", "email": "hong.gildong@example.com"}'
```

#### 사용자 수정
```bash
curl -X PUT http://localhost:8080/api/users/1 \
  -H "Content-Type: application/json" \
  -d '{"name": "홍길동", "email": "hong.updated@example.com"}'
```

#### 사용자 삭제
```bash
curl -X DELETE http://localhost:8080/api/users/1
```

## 프로젝트 구조

```
src/
├── main/
│   ├── java/com/example/usermanagement/
│   │   ├── UserManagementApplication.java    # 메인 애플리케이션 클래스
│   │   ├── entity/
│   │   │   └── User.java                     # 사용자 엔티티
│   │   ├── repository/
│   │   │   └── UserRepository.java           # 사용자 레포지토리
│   │   ├── service/
│   │   │   └── UserService.java              # 사용자 서비스
│   │   └── controller/
│   │       └── UserController.java           # REST API 컨트롤러
│   └── resources/
│       ├── application.yml                   # 애플리케이션 설정
│       └── data.sql                         # 초기 데이터
└── test/                                    # 테스트 코드 (향후 추가)
```

## 초기 데이터

애플리케이션 실행 시 다음 사용자들이 자동으로 생성됩니다:

- 김철수 (kim.chulsoo@example.com)
- 이영희 (lee.younghee@example.com)
- 박민수 (park.minsu@example.com)
- 최지은 (choi.jieun@example.com)
- 정우진 (jung.woojin@example.com)

## 개발 환경 설정

### Java 17 확인
```bash
java --version
```

### 포트 변경 (필요시)
`src/main/resources/application.yml` 파일에서 `server.port` 값을 변경하세요.

### 데이터베이스 변경 (MySQL/PostgreSQL)
`application.yml` 파일의 datasource 설정을 변경하고 해당 데이터베이스 드라이버를 `pom.xml`에 추가하세요.

## 빌드 후, 실행
```bash
  mvn clean package

  java -jar target/user-management-1.0.0.jar
```