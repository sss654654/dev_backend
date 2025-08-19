#!/bin/bash

# AWS CLI가 LocalStack을 바라보도록 설정합니다.
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=ap-northeast-2

echo " Kinesis 스트림 생성을 시작합니다..."

# cgv-admissions-stream 이라는 이름의 Kinesis 스트림을 1개의 샤드(shard)로 생성합니다.
aws kinesis create-stream \
    --stream-name cgv-admissions-stream \
    --shard-count 1

# 스트림이 'ACTIVE' 상태가 될 때까지 기다립니다. (선택사항이지만 안정성을 위해 추천)
aws kinesis wait stream-exists \
    --stream-name cgv-admissions-stream

echo " Kinesis 스트림이 성공적으로 생성되었습니다."