-- cgv_test_optimized.lua - 5000명 대기열 부하테스트 최적화 버전
local counter = 0
local success_count = 0
local queue_count = 0
local error_count = 0
local connection_errors = 0
local timeout_errors = 0

-- 🔥 성능 개선: 랜덤 분산을 위한 시드 설정
math.randomseed(os.time())

function request()
    counter = counter + 1
    
    -- 🎯 더 현실적인 세션/요청 ID 생성 (UUID 형식)
    local session_id = string.format("sess-%08x-%04x-%04x", 
        counter, math.random(1000, 9999), math.random(1000, 9999))
    local request_id = string.format("req-%08x-%04x-%04x", 
        counter, math.random(1000, 9999), math.random(1000, 9999))
    
    local body = string.format([[{
        "movieId": "movie-topgun2",
        "sessionId": "%s",
        "requestId": "%s"
    }]], session_id, request_id)
    
    -- 🚀 성능 개선: Keep-Alive 연결 사용
    return wrk.format("POST", nil, {
        ["Content-Type"] = "application/json",
        ["Connection"] = "keep-alive",
        ["User-Agent"] = "wrk-load-test"
    }, body)
end

function response(status, headers, body)
    if status == 200 then
        success_count = success_count + 1
        if success_count % 50 == 0 then -- 더 자주 리포트
            print("✅ 즉시 입장: " .. success_count .. "건")
        end
        
    elseif status == 202 then
        queue_count = queue_count + 1
        
        -- 🎯 순위 정보 더 정확하게 파싱
        local rank = string.match(body or "", '"myRank":(%d+)')
        local total_waiting = string.match(body or "", '"totalWaiting":(%d+)')
        
        if queue_count % 100 == 0 then
            local rank_info = rank and ("순위: " .. rank) or "순위: 미확인"
            local total_info = total_waiting and ("/" .. total_waiting) or ""
            print("📋 대기열 진입: " .. queue_count .. "건 (" .. rank_info .. total_info .. ")")
        end
        
    elseif status == 500 then
        error_count = error_count + 1
        print("❌ 서버 오류 500: " .. (body and string.sub(body, 1, 100) or "응답 없음"))
        
    elseif status == 503 then
        error_count = error_count + 1
        print("⚠️ 서비스 과부하 503: 서버가 요청을 처리할 수 없음")
        
    elseif status == 502 then
        error_count = error_count + 1
        print("🔌 Bad Gateway 502: 업스트림 서버 연결 실패")
        
    elseif status == 0 then
        connection_errors = connection_errors + 1
        if connection_errors % 25 == 0 then
            print("🔌 연결 실패: " .. connection_errors .. "건 (네트워크 오류)")
        end
        
    else
        error_count = error_count + 1
        if error_count % 25 == 0 then
            print("⚠️ 기타 오류 " .. status .. ": " .. error_count .. "건")
        end
    end
end

function done(summary, latency, requests)
    print("\n=== 🎯 5000명 대기열 부하테스트 결과 ===")
    print("📊 요청 통계:")
    print("  총 요청: " .. summary.requests)
    print("  완료된 요청: " .. (success_count + queue_count + error_count))
    print("")
    
    print("🎫 입장/대기열 결과:")
    print("  ✅ 즉시 입장: " .. success_count .. "건")
    print("  📋 대기열 진입: " .. queue_count .. "건")
    print("  🎯 총 성공: " .. (success_count + queue_count) .. "건")
    print("")
    
    print("❌ 오류 분석:")
    print("  서버/앱 오류: " .. error_count .. "건")
    print("  연결 실패: " .. connection_errors .. "건")
    
    -- 네트워크 오류 세부 분석
    local total_errors = 0
    if type(summary.errors) == "number" then
        total_errors = summary.errors
    elseif type(summary.errors) == "table" then
        local connect_err = summary.errors.connect or 0
        local read_err = summary.errors.read or 0
        local write_err = summary.errors.write or 0
        local status_err = summary.errors.status or 0
        local timeout_err = summary.errors.timeout or 0
        
        total_errors = connect_err + read_err + write_err + status_err + timeout_err
        
        if total_errors > 0 then
            print("  🔍 네트워크 오류 세부:")
            if connect_err > 0 then print("    연결 오류: " .. connect_err .. "건") end
            if read_err > 0 then print("    읽기 오류: " .. read_err .. "건") end
            if write_err > 0 then print("    쓰기 오류: " .. write_err .. "건") end
            if timeout_err > 0 then print("    타임아웃: " .. timeout_err .. "건") end
        end
    end
    
    print("  총 네트워크 오류: " .. total_errors .. "건")
    print("")
    
    -- 성능 지표
    local success_rate = (success_count + queue_count) / summary.requests * 100
    local error_rate = (error_count + total_errors) / summary.requests * 100
    
    print("📈 성능 지표:")
    print("  성공률: " .. string.format("%.2f", success_rate) .. "%")
    print("  오류율: " .. string.format("%.2f", error_rate) .. "%")
    print("  평균 지연시간: " .. string.format("%.2f", latency.mean/1000) .. "ms")
    print("  95% 지연시간: " .. string.format("%.2f", (latency.p95 or latency.percentile_95 or 0)/1000) .. "ms")
    print("  99% 지연시간: " .. string.format("%.2f", (latency.p99 or latency.percentile_99 or 0)/1000) .. "ms")
    print("  최대 지연시간: " .. string.format("%.2f", latency.max/1000) .. "ms")
    print("")
    
    -- 🔥 대기열 시스템 특화 분석
    print("🎪 대기열 시스템 분석:")
    if queue_count > 0 then
        local queue_rate = queue_count / summary.requests * 100
        print("  대기열 진입률: " .. string.format("%.1f", queue_rate) .. "%")
        print("  예상 대기자 처리량: ~" .. math.ceil(queue_count / 60) .. "명/초")
    end
    
    if success_count > 0 then
        local immediate_rate = success_count / summary.requests * 100
        print("  즉시 입장률: " .. string.format("%.1f", immediate_rate) .. "%")
    end
    print("")
    
    -- 🚨 시스템 상태 진단 및 권장사항
    print("🏥 시스템 상태 진단:")
    if error_rate > 50 then
        print("🚨 심각: 시스템 과부하 상태")
        print("   📍 권장 조치:")
        print("     1. 서버 인스턴스 증설 필요")
        print("     2. Redis 클러스터 확장 검토")
        print("     3. WebSocket 연결 수 제한")
        print("     4. 대기열 처리 속도 최적화")
        
    elseif error_rate > 25 then
        print("⚠️ 경고: 높은 부하 감지")
        print("   📍 권장 조치:")
        print("     1. 모니터링 강화")
        print("     2. 오토스케일링 설정 확인")
        print("     3. 대기열 배치 크기 조정")
        
    elseif error_rate > 10 then
        print("🔶 주의: 부하 증가 감지")
        print("   📍 권장 조치:")
        print("     1. 성능 메트릭 모니터링")
        print("     2. 캐시 히트율 확인")
        
    elseif success_rate > 95 then
        print("✅ 우수: 시스템이 안정적으로 동작")
        if queue_count > success_count * 2 then
            print("   💡 대기열 시스템이 효과적으로 부하를 분산하고 있습니다.")
        end
        
    else
        print("🔍 양호: 추가 최적화 여지 있음")
    end
    
    -- 실시간 대기열 업데이트 테스트 결과
    if queue_count > 100 then
        print("")
        print("📡 실시간 업데이트 테스트 평가:")
        print("  대기열 사용자 " .. queue_count .. "명이 순위 업데이트를 받게 됩니다.")
        print("  WebSocket 연결 부하: ~" .. math.ceil(queue_count * 1.2) .. " 동시 연결")
        print("  예상 순위 업데이트 빈도: " .. math.ceil(queue_count / 100) .. "회/초")
        
        if queue_count > 3000 then
            print("  🔥 고부하 환경: WebSocket 배치 처리 권장")
        end
    end
    
    print("\n🎯 테스트 완료 - " .. os.date("%Y-%m-%d %H:%M:%S"))
end