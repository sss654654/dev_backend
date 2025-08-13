stages:
  - build
  - docker

default:
  tags: [cwave]

build:
  stage: build
  image: maven:3.9-eclipse-temurin-17
  cache:
    paths:
      - .m2/repository
  script:
    - mvn -B -DskipTests clean package
  artifacts:
    paths:
      - target/*.jar
    expire_in: 7 days

docker-build:
  stage: docker
  image: docker:24.0.5
  services:
    - docker:24.0.5-dind
  variables:
    DOCKER_TLS_CERTDIR: ""
  script:
    - echo "$DOCKERHUB_PASSWORD" | docker login -u "$DOCKERHUB_USERNAME" --password-stdin
    - IMAGE="cwave-api"
    - TAG="$CI_COMMIT_SHORT_SHA"
    - docker build -t "$DOCKERHUB_USERNAME/$IMAGE:$TAG" .
    - docker push "$DOCKERHUB_USERNAME/$IMAGE:$TAG"
  needs:
    - build