-- KEYS[1]: active ZSET, KEYS[2]: waiting ZSET
-- ARGV[1]: maxActive, ARGV[2]: member "requestId:sessionId", ARGV[3]: now
local activeKey  = KEYS[1]
local waitingKey = KEYS[2]
local maxActive  = tonumber(ARGV[1])
local member     = ARGV[2]
local now        = tonumber(ARGV[3])

local activeCount = redis.call('ZCARD', activeKey)
if activeCount < maxActive then
  redis.call('ZADD', activeKey, now, member)
  return {1}
else
  redis.call('ZADD', waitingKey, now, member)
  local rank = redis.call('ZRANK', waitingKey, member)
  local totalWaiting = redis.call('ZCARD', waitingKey)
  return {2, rank + 1, totalWaiting}
end
