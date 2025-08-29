package com.example.admission;

import com.example.admission.ws.WebSocketUpdateService;
import com.example.admission.service.LoadBalancingOptimizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.*;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class KinesisAdmissionConsumer {

    private static final Logger logger = LoggerFactory.getLogger(KinesisAdmissionConsumer.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${KINESIS_STREAM_NAME:cgv-admissions-stream}")
    private String streamName;

    @Value("${KINESIS_CONSUMER_ENABLED:true}")
    private boolean consumerEnabled;

    private final Map<String, ScheduledExecutorService> shardConsumers = new ConcurrentHashMap<>();
    private final Map<String, String> shardIterators = new ConcurrentHashMap<>();

    private final KinesisClient kinesisClient;
    private final WebSocketUpdateService webSocketService;
    private final LoadBalancingOptimizer loadBalancingOptimizer;
    private volatile boolean isRunning = false;

    public KinesisAdmissionConsumer(WebSocketUpdateService webSocketService,
                                      KinesisClient kinesisClient,
                                      LoadBalancingOptimizer loadBalancingOptimizer) {
        this.webSocketService = webSocketService;
        this.kinesisClient = kinesisClient;
        this.loadBalancingOptimizer = loadBalancingOptimizer;
    }

    @PostConstruct
    public void init() {
        if (!consumerEnabled) {
            logger.info("Kinesis 컨슈머가 비활성화되어 있습니다.");
            return;
        }
        this.isRunning = true;
        startAssignedShardConsumers();
    }

    private void startAssignedShardConsumers() {
        try {
            DescribeStreamRequest describeRequest = DescribeStreamRequest.builder()
                .streamName(streamName)
                .build();
            DescribeStreamResponse response = kinesisClient.describeStream(describeRequest);

            List<Shard> allShards = response.streamDescription().shards();
            logger.info("전체 샤드 수: {}", allShards.size());

            List<Shard> myShards = assignShardsToThisPod(allShards);
            logger.info("이 Pod({})이 담당할 샤드: {} (총 {}개)",
                       loadBalancingOptimizer.getPodId(),
                       myShards.stream().map(Shard::shardId).collect(Collectors.toList()),
                       myShards.size());

            for (Shard shard : myShards) {
                startConsumerForShard(shard);
            }

        } catch (Exception e) {
            logger.error("샤드 Consumer 시작 실패", e);
        }
    }

    private List<Shard> assignShardsToThisPod(List<Shard> allShards) {
        try {
            List<String> activePods = loadBalancingOptimizer.getLoadBalancingStatus()
                .get("activePods") instanceof List ?
                (List<String>) loadBalancingOptimizer.getLoadBalancingStatus().get("activePods") :
                Arrays.asList(loadBalancingOptimizer.getPodId());

            String myPodId = loadBalancingOptimizer.getPodId();
            int myIndex = activePods.indexOf(myPodId);

            if (myIndex == -1) {
                logger.warn("현재 Pod이 활성 목록에 없음, 첫 번째 샤드만 처리");
                return allShards.isEmpty() ? Collections.emptyList() :
                       Collections.singletonList(allShards.get(0));
            }

            List<Shard> myShards = new ArrayList<>();
            for (int i = myIndex; i < allShards.size(); i += activePods.size()) {
                myShards.add(allShards.get(i));
            }

            logger.info("샤드 분산 결과: 전체 Pod {}개, 내 순서 {}, 담당 샤드 {}개",
                       activePods.size(), myIndex, myShards.size());

            return myShards;

        } catch (Exception e) {
            logger.error("샤드 분산 실패, 첫 번째 샤드만 처리", e);
            return allShards.isEmpty() ? Collections.emptyList() :
                   Collections.singletonList(allShards.get(0));
        }
    }

    private void startConsumerForShard(Shard shard) {
        String shardId = shard.shardId();
        try {
            GetShardIteratorRequest request = GetShardIteratorRequest.builder()
                .streamName(streamName)
                .shardId(shardId)
                .shardIteratorType(ShardIteratorType.LATEST)
                .build();

            String iterator = kinesisClient.getShardIterator(request).shardIterator();
            shardIterators.put(shardId, iterator);

            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r ->
                new Thread(r, "kinesis-consumer-" + shardId));
            shardConsumers.put(shardId, executor);

            long pollInterval = 1500; // Recommended: Increase interval to reduce load
            executor.scheduleWithFixedDelay(() -> pollRecordsForShard(shardId),
                0, pollInterval, TimeUnit.MILLISECONDS);

            logger.info("샤드 Consumer 시작: {} ({}ms 간격)", shardId, pollInterval);

        } catch (Exception e) {
            logger.error("샤드 {} Consumer 시작 실패", shardId, e);
        }
    }

    private void pollRecordsForShard(String shardId) {
        if (!isRunning) return;

        String iterator = shardIterators.get(shardId);
        if (iterator == null) {
            logger.warn("ShardId {}에 대한 Iterator가 없습니다. 폴링을 건너뜁니다.", shardId);
            return;
        }

        int maxRetries = 3;
        int retryCount = 0;

        while (retryCount < maxRetries && isRunning) {
            try {
                GetRecordsRequest request = GetRecordsRequest.builder()
                    .shardIterator(iterator)
                    .limit(50)
                    .build();

                GetRecordsResponse response = kinesisClient.getRecords(request);

                if (!response.records().isEmpty()) {
                    response.records().forEach(this::processRecord);
                    logger.debug("샤드 {} - {}건 처리", shardId, response.records().size());
                }

                String nextIterator = response.nextShardIterator();
                if (nextIterator != null) {
                    shardIterators.put(shardId, nextIterator);
                } else {
                    logger.warn("샤드 {}가 닫혔습니다. 해당 Consumer 스레드를 종료합니다.", shardId);
                    ScheduledExecutorService executor = shardConsumers.remove(shardId);
                    if (executor != null) {
                        executor.shutdown();
                    }
                    shardIterators.remove(shardId);
                    return;
                }
                break; // Success, exit retry loop

            } catch (ExpiredIteratorException e) {
                logger.warn("샤드 {}의 Iterator가 만료되었습니다. 새 Iterator를 가져옵니다.", shardId);
                try {
                    GetShardIteratorRequest newIteratorRequest = GetShardIteratorRequest.builder()
                        .streamName(streamName)
                        .shardId(shardId)
                        .shardIteratorType(ShardIteratorType.LATEST)
                        .build();
                    iterator = kinesisClient.getShardIterator(newIteratorRequest).shardIterator();
                    shardIterators.put(shardId, iterator);
                    logger.info("샤드 {}의 새 Iterator를 성공적으로 가져왔습니다.", shardId);
                    retryCount = 0; // Reset retry count after getting a new iterator
                    continue; // Immediately retry with the new iterator
                } catch (Exception newIteratorEx) {
                    logger.error("샤드 {}의 새 Iterator를 가져오는 데 실패했습니다.", shardId, newIteratorEx);
                    break; // Break the loop if we can't get a new iterator
                }
            } catch (ProvisionedThroughputExceededException e) {
                retryCount++;
                long waitTime = Math.min(1000L * retryCount, 5000L);
                logger.warn("샤드 {} 처리량 초과, {}ms 대기 후 재시도 {}/{}",
                           shardId, waitTime, retryCount, maxRetries);
                try {
                    Thread.sleep(waitTime);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (ResourceNotFoundException e) {
                logger.error("Kinesis 스트림을 찾을 수 없음: {}", streamName, e);
                this.isRunning = false;
                return;
            } catch (Exception e) {
                retryCount++;
                if (retryCount >= maxRetries) {
                    logger.error("샤드 {} 폴링 최종 실패", shardId, e);
                    return;
                }
                logger.warn("샤드 {} 폴링 오류, 재시도 {}/{}", shardId, retryCount, maxRetries, e);
                try {
                    Thread.sleep(200L * retryCount);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void processRecord(software.amazon.awssdk.services.kinesis.model.Record record) {
        try {
            String data = StandardCharsets.UTF_8.decode(record.data().asByteBuffer()).toString();
            JsonNode eventNode = objectMapper.readTree(data);
            String eventType = eventNode.path("action").asText();

            switch (eventType) {
                case "ADMIT":
                    webSocketService.notifyAdmission(
                        eventNode.path("requestId").asText(),
                        eventNode.path("movieId").asText());
                    break;
                case "RANK_UPDATE":
                    webSocketService.notifyRankUpdate(
                        eventNode.path("requestId").asText(),
                        "WAITING",
                        eventNode.path("rank").asLong(),
                        eventNode.path("totalWaiting").asLong());
                    break;
                case "STATS_UPDATE":
                    webSocketService.broadcastQueueStats(
                        eventNode.path("movieId").asText(),
                        eventNode.path("totalWaiting").asLong());
                    break;
                default:
                    logger.debug("알 수 없는 이벤트 타입: {}", eventType);
                    break;
            }
        } catch (Exception e) {
            logger.error("Kinesis 레코드 처리 실패", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        logger.info("Kinesis Consumer 종료 시작...");
        this.isRunning = false;

        shardConsumers.values().forEach(executor -> {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn("Executor 정상 종료 실패, 강제 종료 중...");
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        });

        shardConsumers.clear();
        shardIterators.clear();
        logger.info("Kinesis Consumer 종료 완료");
    }

    public Map<String, Object> getConsumerStats() {
        return Map.of(
            "isRunning", isRunning,
            "activeShardConsumers", shardConsumers.size(),
            "shardIds", shardIterators.keySet(),
            "podId", loadBalancingOptimizer.getPodId()
        );
    }
}