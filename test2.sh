#!/bin/bash
# =================================================
# 방법 1: 개선된 test2.sh (병렬 처리 최적화)
# =================================================

#!/bin/bash
# 개선된 CGV 대기열 부하 테스트 - 100K 트래픽용

TOTAL_REQUESTS=10000
CONCURRENT_BATCH=1000    # 동시 실행할 배치 수
BATCH_SIZE=100          # 각 배치당 요청 수

echo "🚀 CGV 대기열 10만 트래픽 테스트 시작"
echo "총 요청: ${TOTAL_REQUESTS}, 동시 배치: ${CONCURRENT_BATCH}, 배치 크기: ${BATCH_SIZE}"

# UUID 생성 함수 최적화
generate_uuid() {
    # /dev/urandom 사용으로 성능 향상
    cat /dev/urandom | tr -dc 'a-f0-9' | fold -w 32 | head -n 1 | \
    sed 's/\(........\)\(....\)\(....\)\(....\)\(............\)/\1-\2-\3-\4-\5/'
}

# 단일 요청 함수
send_request() {
    local batch_id=$1
    local request_num=$2
    
    SESSION_ID=$(generate_uuid)
    REQUEST_ID=$(generate_uuid)
    
    response=$(curl -X POST \
        -H "Content-Type: application/json" \
        -d "{\"movieId\":\"movie-topgun2\",\"sessionId\":\"$SESSION_ID\",\"requestId\":\"$REQUEST_ID\"}" \
        -w "HTTP_STATUS:%{http_code}" \
        -s --max-time 5 \
        https://dev.api.peacemaker.kr/api/admission/enter 2>/dev/null)
    
    http_status=$(echo "$response" | grep -o "HTTP_STATUS:[0-9]*" | cut -d: -f2)
    
    # 간단한 로그 (성능상 최소화)
    if [[ "$http_status" == "202" ]]; then
        echo "[$batch_id-$request_num] 대기열 등록"
    elif [[ "$http_status" == "200" ]]; then
        echo "[$batch_id-$request_num] 즉시 입장"
    else
        echo "[$batch_id-$request_num] 오류: $http_status"
    fi
}

# 배치 실행 함수
run_batch() {
    local batch_id=$1
    local start_num=$(( (batch_id - 1) * BATCH_SIZE + 1 ))
    local end_num=$(( batch_id * BATCH_SIZE ))
    
    for i in $(seq $start_num $end_num); do
        send_request $batch_id $i &
        
        # 너무 많은 동시 연결 방지 (배치 내에서도 조절)
        if (( i % 20 == 0 )); then
            sleep 0.1
        fi
    done
    
    wait # 배치 내 모든 요청 대기
    echo "배치 $batch_id 완료 (요청 $start_num-$end_num)"
}

# 메인 실행
export -f generate_uuid send_request run_batch

# 전체 배치 수 계산
total_batches=$(( TOTAL_REQUESTS / BATCH_SIZE ))

echo "총 배치 수: $total_batches, 동시 실행: $CONCURRENT_BATCH"

# 배치 단위로 병렬 실행
for start_batch in $(seq 1 $CONCURRENT_BATCH $total_batches); do
    end_batch=$(( start_batch + CONCURRENT_BATCH - 1 ))
    if (( end_batch > total_batches )); then
        end_batch=$total_batches
    fi
    
    echo "🔄 배치 그룹 $start_batch-$end_batch 실행 중..."
    
    # CONCURRENT_BATCH 개씩 동시 실행
    for batch_id in $(seq $start_batch $end_batch); do
        run_batch $batch_id &
    done
    
    wait # 현재 배치 그룹 완료 대기
    echo "✅ 배치 그룹 $start_batch-$end_batch 완료"
    
    # 서버 부하 방지를 위한 짧은 대기
    sleep 1
done

echo "🎉 ${TOTAL_REQUESTS} 트래픽 테스트 완료!"