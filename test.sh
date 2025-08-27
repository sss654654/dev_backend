#!/bin/bash
# 실제 사용자처럼 쿠키를 유지하며 대기열을 테스트하는 스크립트

# 사용법 체크
if [ $# -eq 0 ]; then
    echo "Usage: $0 <loop_count>"
    echo "Example: $0 500"
    exit 1
fi

LOOP_COUNT=$1
API_BASE_URL="https://dev.api.peacemaker.kr"
COOKIE_JAR="cookies.txt" # 쿠키를 저장할 파일

echo "🚀 대기열 테스트 시작 - 총 ${LOOP_COUNT}명의 '실제 사용자' 시뮬레이션"
echo "========================================="

# 1. 최초 세션 발급 (사용자 1명이 브라우저를 켠 상황)
echo "🍪 세션 쿠키 발급 중..."
curl -c "$COOKIE_JAR" -X POST -s "${API_BASE_URL}/api/sessions/issue"
echo "✅ 세션 쿠키 저장 완료: $COOKIE_JAR"
echo ""

# UUID 생성 함수
generate_uuid() {
    if command -v uuidgen > /dev/null 2>&1; then
        echo $(uuidgen | tr '[:upper:]' '[:lower:]')
    else
        openssl rand -hex 16 | sed 's/\(..\)/\1-/g; s/.\{9\}/&-/; s/.\{14\}/&-/; s/.\{19\}/&-/; s/-$//'
    fi
}

# API 호출 함수 (쿠키 사용)
call_api() {
    local i=$1
    local request_id=$(generate_uuid)
    
    # ❗️ 중요: -b "$COOKIE_JAR" 옵션으로 저장된 쿠키를 모든 요청에 포함
    response=$(curl -X POST \
        -H "Content-Type: application/json" \
        -d "{\"movieId\":\"movie-topgun2\",\"requestId\":\"$request_id\"}" \
        -w "HTTP_STATUS:%{http_code}" \
        -s \
        -b "$COOKIE_JAR" \
        "${API_BASE_URL}/api/admission/enter")
    
    http_status=$(echo "$response" | grep -o "HTTP_STATUS:[0-9]*" | cut -d: -f2)
    response_body=$(echo "$response" | sed 's/HTTP_STATUS:[0-9]*$//')
    
    if [[ "$http_status" == "202" ]]; then
        rank=$(echo "$response_body" | grep -o '"myRank":[0-9]*' | cut -d: -f2)
        echo "[$i]  queuing - rank: $rank"
    elif [[ "$http_status" == "200" ]]; then
        echo "[$i] success - immediate entry"
    else
        echo "[$i] error - HTTP $http_status, Body: $response_body"
    fi
}

# 병렬 처리를 위해 함수를 export
export -f call_api
export -f generate_uuid
export COOKIE_JAR
export API_BASE_URL

# GNU Parallel을 사용하여 병렬 실행 (설치 필요: brew install parallel)
# seq $LOOP_COUNT | parallel -j 50 --eta call_api {}

# parallel이 없는 경우, 기존 방식 사용 (단, 더 느림)
BATCH_SIZE=50
for i in $(seq 1 $LOOP_COUNT); do
    call_api "$i" &
    if (( i % BATCH_SIZE == 0 )); then
        wait
        echo "--- Batch of $BATCH_SIZE completed ---"
    fi
done
wait


echo "========================================="
echo "✅ 총 $LOOP_COUNT명 처리 완료"

# 임시 쿠키 파일 삭제
rm "$COOKIE_JAR"