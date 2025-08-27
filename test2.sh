#!/bin/bash
# 대기열 테스트용 - 1500명으로 늘려서 확실히 대기열 상황 만들기

if [ $# -eq 0 ]; then
    echo "Usage: $0 <loop_count>"
    echo "Example: $0 1500"
    exit 1
fi

LOOP_COUNT=$1

if ! [[ "$LOOP_COUNT" =~ ^[0-9]+$ ]]; then
    echo "Error: Please provide a valid number"
    exit 1
fi

echo "🚀 대기열 테스트 시작 - 총 ${LOOP_COUNT}명"
echo "현재 최대 세션: 1000명 (2 Pod × 500세션)"
echo "========================================="

# UUID 생성 함수
generate_uuid() {
    if command -v uuidgen > /dev/null 2>&1; then
        echo $(uuidgen | tr '[:upper:]' '[:lower:]')
    elif [ -f /proc/sys/kernel/random/uuid ]; then
        cat /proc/sys/kernel/random/uuid
    else
        openssl rand -hex 16 | sed 's/\(..\)/\1-/g; s/.\{9\}/&-/; s/.\{14\}/&-/; s/.\{19\}/&-/; s/-$//'
    fi
}

# 병렬 처리로 빠르게 대기열 상황 만들기
call_api_batch() {
    local batch_start=$1
    local batch_end=$2
    
    for i in $(seq $batch_start $batch_end); do
        SESSION_ID=$(generate_uuid)
        REQUEST_ID=$(generate_uuid)
        
        response=$(curl -X POST \
            -H "Content-Type: application/json" \
            -d "{\"movieId\":\"movie-topgun2\",\"sessionId\":\"$SESSION_ID\",\"requestId\":\"$REQUEST_ID\"}" \
            -w "HTTP_STATUS:%{http_code}" \
            -s \
            https://dev.api.peacemaker.kr/api/admission/enter)
        
        http_status=$(echo "$response" | grep -o "HTTP_STATUS:[0-9]*" | cut -d: -f2)
        response_body=$(echo "$response" | sed 's/HTTP_STATUS:[0-9]*$//')
        
        # 대기열에 들어간 경우만 로그 출력
        if [[ "$http_status" == "202" ]]; then
            rank=$(echo "$response_body" | grep -o '"myRank":[0-9]*' | cut -d: -f2)
            echo "[$i] 대기열 등록 - 순위: $rank"
        elif [[ "$http_status" == "200" ]]; then
            echo "[$i] 즉시 입장"
        else
            echo "[$i] 오류 - HTTP $http_status"
        fi
    done
}

# 배치 크기 설정 (동시에 처리할 요청 수)
BATCH_SIZE=50
total_batches=$(( (LOOP_COUNT + BATCH_SIZE - 1) / BATCH_SIZE ))

echo "�� 배치 처리: ${total_batches}개 배치 × ${BATCH_SIZE}명씩"

for batch in $(seq 1 $total_batches); do
    batch_start=$(( (batch - 1) * BATCH_SIZE + 1 ))
    batch_end=$(( batch * BATCH_SIZE ))
    
    if [ $batch_end -gt $LOOP_COUNT ]; then
        batch_end=$LOOP_COUNT
    fi
    
    echo "🔄 배치 $batch/$total_batches 처리 중... ($batch_start-$batch_end)"
    
    # 백그라운드로 배치 실행
    call_api_batch $batch_start $batch_end &
    
    # 배치 간 짧은 대기 (서버 부하 방지)
    sleep 1
done

# 모든 백그라운드 작업 완료 대기
wait

echo "========================================="
echo "✅ 총 $LOOP_COUNT명 처리 완료"
echo "💡 이제 웹에서 접속하면 대기열에 들어갈 것입니다!"
