#!/bin/bash
# Redis CROSSSLOT 오류 해결을 위한 키 정리 스크립트

echo "🔧 Redis CROSSSLOT 오류 해결을 위한 키 정리 시작..."

# Redis 연결 설정 (환경에 맞게 수정)
REDIS_HOST=${REDIS_HOST:-cgv-redis-cluster-gfbhur.serverless.apn2.cache.amazonaws.com}
REDIS_PORT=${REDIS_PORT:-6379}
REDIS_CLI="redis6-cli -c -h cgv-redis-cluster-gfbhur.serverless.apn2.cache.amazonaws.com --tls"

echo "📡 Redis 서버 연결 확인: $REDIS_HOST:$REDIS_PORT"

# Redis 연결 테스트
if ! $REDIS_CLI ping > /dev/null 2>&1; then
    echo "❌ Redis 서버에 연결할 수 없습니다. 호스트와 포트를 확인하세요."
    echo "💡 로컬 환경이면: brew services start redis 또는 redis-server 실행"
    echo "💡 Docker 환경이면: docker run -d -p 6379:6379 redis:alpine"
    exit 1
fi

echo "✅ Redis 서버 연결 성공"

# 1. 기존 패턴의 키들 확인 및 삭제
echo ""
echo "🔍 기존 CROSSSLOT 문제 키 패턴 삭제 중..."

echo "📋 기존 active_sessions 키 삭제:"
OLD_ACTIVE_KEYS=$($REDIS_CLI keys "active_sessions:movie:*")
if [ -n "$OLD_ACTIVE_KEYS" ]; then
    echo "$OLD_ACTIVE_KEYS" | while read key; do
        if [ -n "$key" ]; then
            echo "🗑️ 삭제: $key"
            $REDIS_CLI del "$key"
        fi
    done
else
    echo "  (삭제할 키 없음)"
fi

echo "📋 기존 waiting_queue 키 삭제:"
OLD_WAITING_KEYS=$($REDIS_CLI keys "waiting_queue:movie:*")
if [ -n "$OLD_WAITING_KEYS" ]; then
    echo "$OLD_WAITING_KEYS" | while read key; do
        if [ -n "$key" ]; then
            echo "🗑️ 삭제: $key"
            $REDIS_CLI del "$key"
        fi
    done
else
    echo "  (삭제할 키 없음)"
fi

# 2. 관련 메타데이터 키들도 정리
echo ""
echo "🧹 관련 메타데이터 키 정리:"
$REDIS_CLI del "active_movies" "waiting_movies"
echo "✅ active_movies, waiting_movies 키 삭제 완료"

# 3. Hash Tag 패턴 키들 확인 (새로운 패턴)
echo ""
echo "🔍 새로운 Hash Tag 패턴 키 상태 확인:"
NEW_SESSION_KEYS=$($REDIS_CLI keys "sessions:*")
if [ -n "$NEW_SESSION_KEYS" ]; then
    echo "📋 현재 sessions:* 키들:"
    echo "$NEW_SESSION_KEYS"
    
    echo ""
    read -p "새 패턴 키들도 삭제하시겠습니까? (테스트 목적) [y/N]: " clean_new
    if [ "$clean_new" = "y" ] || [ "$clean_new" = "Y" ]; then
        echo "$NEW_SESSION_KEYS" | while read key; do
            if [ -n "$key" ]; then
                echo "🗑️ 삭제: $key"
                $REDIS_CLI del "$key"
            fi
        done
    fi
else
    echo "  (새 패턴 키 없음)"
fi

# 4. 정리 후 상태 확인
echo ""
echo "📊 정리 후 현재 상태:"
echo "  전체 키 개수: $($REDIS_CLI dbsize)"
echo "  sessions:* 키: $($REDIS_CLI keys "sessions:*" | wc -l)개"
echo "  active_sessions:* 키: $($REDIS_CLI keys "active_sessions:*" | wc -l)개"
echo "  waiting_queue:* 키: $($REDIS_CLI keys "waiting_queue:*" | wc -l)개"

echo ""
echo "🎉 Redis 키 정리 완료!"
echo ""
echo "💡 다음 단계:"
echo "  1. 코드에서 Hash Tag 키 생성 메서드를 적용"
echo "  2. Spring Boot 애플리케이션 재시작"
echo "  3. 프론트엔드에서 대기열 테스트"
echo ""
echo "🔧 Hash Tag 키 예시:"
echo "  기존: active_sessions:movie:movie-topgun2"
echo "  신규: sessions:{movie-topgun2}:active"
