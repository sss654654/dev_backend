#!/bin/bash

# 사용법 체크
if [ $# -eq 0 ]; then
    echo "Usage: $0 <loop_count>"
    echo "Example: $0 10"
    exit 1
fi

# 루프 횟수를 첫 번째 인자로 받음
LOOP_COUNT=$1

# 숫자인지 확인
if ! [[ "$LOOP_COUNT" =~ ^[0-9]+$ ]]; then
    echo "Error: Please provide a valid number"
    exit 1
fi

echo "Starting API calls - Loop count: $LOOP_COUNT"
echo "========================================="

# UUID 생성 함수 (Linux/macOS 호환)
generate_uuid() {
    if command -v uuidgen > /dev/null 2>&1; then
        # macOS 또는 uuidgen이 있는 시스템
        echo $(uuidgen | tr '[:upper:]' '[:lower:]')
    elif [ -f /proc/sys/kernel/random/uuid ]; then
        # Linux 시스템
        cat /proc/sys/kernel/random/uuid
    else
        # 대체 방법: openssl 사용
        openssl rand -hex 16 | sed 's/\(..\)/\1-/g; s/.\{9\}/&-/; s/.\{14\}/&-/; s/.\{19\}/&-/; s/-$//'
    fi
}

# API 호출 함수
call_api() {
    local session_id=$1
    local request_id=$2
    local loop_num=$3
    
    echo "[$loop_num/$LOOP_COUNT] Calling API..."
    echo "  SessionID: $session_id"
    echo "  RequestID: $request_id"
    
    # API 호출
    response=$(curl -X POST \
        -H "Content-Type: application/json" \
        -d "{\"movieId\":\"movie-topgun2\",\"sessionId\":\"$session_id\",\"requestId\":\"$request_id\"}" \
        -w "HTTP_STATUS:%{http_code}" \
        -s \
        https://dev.api.peacemaker.kr/api/admission/enter)
    
    # HTTP 상태 코드 추출
    http_status=$(echo "$response" | grep -o "HTTP_STATUS:[0-9]*" | cut -d: -f2)
    response_body=$(echo "$response" | sed 's/HTTP_STATUS:[0-9]*$//')
    
    echo "  HTTP Status: $http_status"
    echo "  Response: $response_body"
    echo "  ---"
}

# 메인 루프
for i in $(seq 1 $LOOP_COUNT); do
    # 매번 새로운 UUID 생성
    SESSION_ID=$(generate_uuid)
    REQUEST_ID=$(generate_uuid)
    
    # API 호출
    call_api "$SESSION_ID" "$REQUEST_ID" "$i"
    
    # 마지막 루프가 아니면 잠시 대기 (서버 부하 방지)
    if [ $i -lt $LOOP_COUNT ]; then
        sleep 0
    fi
done

echo "========================================="
echo "Completed $LOOP_COUNT API calls"
