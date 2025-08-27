#!/bin/bash

# Redis 키 정리 스크립트
# WRONGTYPE 오류를 해결하기 위해 문제가 있는 키들을 정리합니다.

echo "🔧 Redis WRONGTYPE 오류 해결을 위한 키 정리 시작..."

# Redis 연결 설정 (환경에 맞게 수정)
REDIS_HOST=${REDIS_HOST:-localhost}
REDIS_PORT=${REDIS_PORT:-6379}
REDIS_CLI="redis-cli -h $REDIS_HOST -p $REDIS_PORT"

echo "📡 Redis 서버 연결 확인: $REDIS_HOST:$REDIS_PORT"

# Redis 연결 테스트
if ! $REDIS_CLI ping > /dev/null 2>&1; then
    echo "❌ Redis 서버에 연결할 수 없습니다. 호스트와 포트를 확인하세요."
    exit 1
fi

echo "✅ Redis 서버 연결 성공"

# 1. 문제가 있는 키 패턴 확인
echo ""
echo "🔍 문제 키 패턴 확인 중..."

PROBLEMATIC_KEYS=""

# active_sessions 키들 확인
echo "📋 active_sessions 키 타입 확인:"
for key in $($REDIS_CLI keys "active_sessions:movie:*"); do
    if [ -n "$key" ]; then
        key_type=$($REDIS_CLI type "$key")
        echo "  $key -> $key_type"
        
        if [ "$key_type" != "zset" ] && [ "$key_type" != "none" ]; then
            echo "  ⚠️ 타입 불일치 감지: $key (예상: zset, 실제: $key_type)"
            PROBLEMATIC_KEYS="$PROBLEMATIC_KEYS $key"
        fi
    fi
done

# waiting_queue 키들 확인  
echo "📋 waiting_queue 키 타입 확인:"
for key in $($REDIS_CLI keys "waiting_queue:movie:*"); do
    if [ -n "$key" ]; then
        key_type=$($REDIS_CLI type "$key")
        echo "  $key -> $key_type"
        
        if [ "$key_type" != "zset" ] && [ "$key_type" != "none" ]; then
            echo "  ⚠️ 타입 불일치 감지: $key (예상: zset, 실제: $key_type)"
            PROBLEMATIC_KEYS="$PROBLEMATIC_KEYS $key"
        fi
    fi
done

# 2. 문제 키 정리
if [ -n "$PROBLEMATIC_KEYS" ]; then
    echo ""
    echo "🧹 문제 키 정리 시작..."
    echo "삭제할 키 목록:$PROBLEMATIC_KEYS"
    
    read -p "정말 삭제하시겠습니까? (y/N): " confirm
    
    if [ "$confirm" = "y" ] || [ "$confirm" = "Y" ]; then
        for key in $PROBLEMATIC_KEYS; do
            echo "🗑️ 삭제 중: $key"
            $REDIS_CLI del "$key"
            if [ $? -eq 0 ]; then
                echo "✅ 삭제 완료: $key"
            else
                echo "❌ 삭제 실패: $key"
            fi
        done
        echo "🎉 문제 키 정리 완료"
    else
        echo "❌ 사용자가 삭제를 취소했습니다."
    fi
else
    echo "✅ 문제가 있는 키를 찾지 못했습니다. 모든 키가 올바른 타입입니다."
fi

# 3. 추가 정리 옵션들
echo ""
echo "🔧 추가 정리 옵션:"
echo "1. 모든 대기열 관련 키 삭제 (전체 초기화)"
echo "2. 만료된 세션 키만 정리"
echo "3. 정리 완료"

read -p "선택하세요 (1-3): " cleanup_option

case $cleanup_option in
    1)
        echo "⚠️ 모든 대기열 관련 키를 삭제합니다..."
        read -p "정말 전체 초기화하시겠습니까? (y/N): " full_confirm
        
        if [ "$full_confirm" = "y" ] || [ "$full_confirm" = "Y" ]; then
            echo "🧹 전체 대기열 키 삭제 중..."
            
            # 모든 관련 키 패턴 삭제
            $REDIS_CLI del active_movies waiting_movies
            
            for key in $($REDIS_CLI keys "active_sessions:*"); do
                $REDIS_CLI del "$key" && echo "🗑️ 삭제: $key"
            done
            
            for key in $($REDIS_CLI keys "waiting_queue:*"); do
                $REDIS_CLI del "$key" && echo "🗑️ 삭제: $key"
            done
            
            for key in $($REDIS_CLI keys "load_balancer:*"); do
                $REDIS_CLI del "$key" && echo "🗑️ 삭제: $key"
            done
            
            echo "🎉 전체 초기화 완료"
        else
            echo "❌ 전체 초기화 취소"
        fi
        ;;
    2)
        echo "⏰ 만료된 세션 정리 중..."
        
        # 30분 이상 된 세션들 정리 (현재시간 - 30분)
        current_time=$(date +%s)
        thirty_minutes_ago=$((current_time * 1000 - 30 * 60 * 1000))
        
        for key in $($REDIS_CLI keys "active_sessions:*"); do
            expired_count=$($REDIS_CLI zremrangebyscore "$key" 0 $thirty_minutes_ago)
            if [ "$expired_count" -gt 0 ]; then
                echo "🧹 $key: ${expired_count}개 만료 세션 정리"
            fi
        done
        
        for key in $($REDIS_CLI keys "waiting_queue:*"); do
            expired_count=$($REDIS_CLI zremrangebyscore "$key" 0 $thirty_minutes_ago)
            if [ "$expired_count" -gt 0 ]; then
                echo "🧹 $key: ${expired_count}개 만료 대기자 정리"
            fi
        done
        
        echo "✅ 만료 세션 정리 완료"
        ;;
    3)
        echo "🎯 정리 작업 완료"
        ;;
    *)
        echo "❌ 잘못된 선택입니다."
        ;;
esac

# 4. 정리 후 상태 확인
echo ""
echo "📊 정리 후 현재 상태:"

echo ""
echo "📋 활성 영화 목록:"
active_movies=$($REDIS_CLI smembers active_movies)
if [ -n "$active_movies" ]; then
    echo "$active_movies"
else
    echo "  (없음)"
fi

echo ""
echo "📋 대기 중인 영화 목록:"
waiting_movies=$($REDIS_CLI smembers waiting_movies)
if [ -n "$waiting_movies" ]; then
    echo "$waiting_movies"
else
    echo "  (없음)"
fi

echo ""
echo "📋 현재 키 통계:"
total_active_keys=$($REDIS_CLI keys "active_sessions:*" | wc -l)
total_waiting_keys=$($REDIS_CLI keys "waiting_queue:*" | wc -l)
total_lb_keys=$($REDIS_CLI keys "load_balancer:*" | wc -l)

echo "  활성 세션 키: ${total_active_keys}개"
echo "  대기열 키: ${total_waiting_keys}개" 
echo "  부하분산 키: ${total_lb_keys}개"

echo ""
echo "🎉 Redis 키 정리 스크립트 실행 완료!"
echo ""
echo "💡 참고사항:"
echo "  - Spring Boot 애플리케이션을 재시작하면 키가 자동으로 재생성됩니다"
echo "  - WRONGTYPE 오류가 계속 발생하면 Redis 버전을 확인하세요"
echo "  - 운영 환경에서는 백업 후 실행하세요"