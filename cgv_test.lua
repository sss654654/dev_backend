-- cgv_test.lua
local counter = 0

function request()
    counter = counter + 1
    local session_id = "session-" .. counter .. "-" .. math.random(10000, 99999)
    local request_id = "request-" .. counter .. "-" .. math.random(10000, 99999)
    
    local body = string.format([[{
        "movieId": "movie-topgun2",
        "sessionId": "%s",
        "requestId": "%s"
    }]], session_id, request_id)
    
    return wrk.format("POST", nil, {
        ["Content-Type"] = "application/json"
    }, body)
end

function response(status, headers, body)
    if status == 200 then
        print("즉시 입장: " .. body)
    elseif status == 202 then
        print("대기열 진입: " .. body)
    else
        print("오류 " .. status .. ": " .. body)
    end
end

function done(summary, latency, requests)
    print("\n=== 테스트 완료 ===")
    print("총 요청: " .. summary.requests)
    print("성공 요청: " .. (summary.requests - summary.errors))
    print("에러: " .. summary.errors)
    print("평균 지연시간: " .. string.format("%.2f", latency.mean/1000) .. "ms")
    print("최대 지연시간: " .. string.format("%.2f", latency.max/1000) .. "ms")
end
