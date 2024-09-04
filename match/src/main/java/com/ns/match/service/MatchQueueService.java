package com.ns.match.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ns.common.SubTask;
import com.ns.common.Task;
import com.ns.common.TaskUseCase;
import com.ns.match.dto.MatchResponse;
import com.ns.match.dto.MatchUserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.kafka.core.reactive.ReactiveKafkaConsumerTemplate;
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.kafka.sender.SenderResult;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchQueueService implements ApplicationRunner {
    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;
    private final String MATCH_WAIT_KEY ="users:queue:%s:wait";
    private final String MATCH_WAIT_KEY_FOR_SCAN ="users:queue:*:wait";

    private static final Long MAX_ALLOW_USER_COUNT = 2L;

    @Value("${scheduler.enabled}")
    private Boolean scheduling = false;

    @Value("${spring.var.matchExpireTime}")
    private int expireTime;

    private final ObjectMapper mapper = new ObjectMapper();

    private final ReactiveKafkaConsumerTemplate<String, Task> TaskRequestConsumerTemplate;
    private final ReactiveKafkaConsumerTemplate<String, Task> TaskResponseConsumerTemplate;
    private final ReactiveKafkaProducerTemplate<String, Task> taskProducerTemplate;

    private final TaskUseCase taskUseCase;

    private final ConcurrentHashMap<String, Task> taskResults = new ConcurrentHashMap<>();
    private final int MAX_TASK_RESULT_SIZE = 5000;


    public Mono<Void> sendTask(String topic, Task task){
        log.info("send ["+topic+"]: "+task.toString());
        String key = task.getTaskID();
        return taskProducerTemplate.send(topic, key, task).then();
    }


    public Mono<String> registerMatchQueue(final String queue, final Long userId) {
        String lockKey = "lock:" + queue;
        String lockValue = UUID.randomUUID().toString();

        return reactiveRedisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, Duration.ofSeconds(10))
                .flatMap(locked -> {
                    if (!locked) {
                        return Mono.just("fail");
                    }

                    List<SubTask> subTasks = Collections.singletonList(
                            taskUseCase.createSubTask(
                                    "MatchUserByMembershipId",
                                    String.valueOf(userId),
                                    SubTask.TaskType.membership,
                                    SubTask.TaskStatus.ready,
                                    userId
                            )
                    );

                    Task task = taskUseCase.createTask(
                            "Match Request",
                            String.valueOf(userId),
                            subTasks
                    );

                    return sendTask("task.membership.response", task)
                            .then(waitForMatchTaskResult(task.getTaskID(),queue,userId))
                            .doFinally(signal -> releaseLock(lockKey, lockValue).subscribe());
                });
    }

    private Mono<String> waitForMatchTaskResult(String taskId, String queue, Long userId) {
        return Mono.defer(() -> {
            return Mono.fromCallable(() -> {
                        while (true) {
                            Task resultTask = taskResults.get(taskId);
                            if (resultTask != null) {
                                MatchUserResponse matchResponse = mapper.convertValue(resultTask.getSubTaskList().get(0), MatchUserResponse.class);
                                return matchResponse;
                            }
                            Thread.sleep(50);
                        }
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .timeout(Duration.ofSeconds(3))
                    .flatMap(matchResponse -> handleMatchResponse(matchResponse, queue, userId))
                    .onErrorResume(e -> Mono.error(new RuntimeException("waitForMatchTaskResult error : ", e)));
        });
    }

    private Mono<String> handleMatchResponse(MatchUserResponse user, String queue, Long userId) {
        if (!user.getSpaceCode().isEmpty()) {
            return Mono.just("fail");
        }

        Long elo = user.getElo();
        String name = user.getName();
        String member = userId + ":" + name;

        return reactiveRedisTemplate.opsForZSet().add(MATCH_WAIT_KEY.formatted(queue), member, elo.doubleValue())
                .flatMap(result -> reactiveRedisTemplate.expire(MATCH_WAIT_KEY.formatted(queue), Duration.ofSeconds(expireTime)))
                .thenReturn("{\"userId\":\"" + userId + "\", \"name\":\"" + name + "\", \"elo\":\"" + elo + "\"}");
    }

    private Flux<Task> waitForTaskResult(String taskId) {

        return TaskRequestConsumerTemplate
                .receiveAutoAck()
                .filter(record -> taskId.equals(record.key()))
                .doOnNext(record -> log.info("TaskRequestConsumerTemplate received : Key = {}, Value = {}", record.key(), record.value()))
                .map(record -> record.value())
                .onErrorResume(throwable -> {
                    log.error("Error occurred: {}", throwable.getMessage());
                    return Flux.empty();
                });
    }



    private Mono<Boolean> releaseLock(String lockKey, String lockValue) {
        return reactiveRedisTemplate.opsForValue().get(lockKey)
                .flatMap(currentValue -> {
                    if (lockValue.equals(currentValue)) {
                        return reactiveRedisTemplate.opsForValue().delete(lockKey).map(deleted -> deleted != null && deleted);
                    }
                    return Mono.just(false);
                });
    }


    public Mono<Void> cancelMatchQueue(Long userId) {
        String lockKey = "lock:cancelMatchQueue:" + userId;
        String lockValue = UUID.randomUUID().toString();
        return reactiveRedisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, Duration.ofSeconds(10))
                .flatMap(locked -> {
                    if (!locked) {
                        return Mono.error(new RuntimeException("Unable to acquire lock"));
                    }

                    List<SubTask> subTasks = new ArrayList<>();
                    subTasks.add(
                            taskUseCase.createSubTask("MatchUserByMembershipId",
                                    String.valueOf(userId),
                                    SubTask.TaskType.membership,
                                    SubTask.TaskStatus.ready,
                                    userId));

                    Task task = taskUseCase.createTask(
                            "Match Request",
                            String.valueOf(userId),
                            subTasks);

                    return sendTask("task.membership.response", task)
                            .thenMany(waitForTaskResult(task.getTaskID()))
                            .flatMap(result -> Flux.fromIterable(result.getSubTaskList())
                                    .filter(subTaskItem -> subTaskItem.getStatus().equals(SubTask.TaskStatus.success)))
                            .flatMap(subTaskItem ->
                                    processCancelTaskResult(subTaskItem, userId)
                                    .doFinally(signal -> releaseLock(lockKey, lockValue).subscribe())
                            ).then();

                });
    }

    private Mono<Void> processCancelTaskResult(SubTask subTask, Long userId) {
        MatchUserResponse matchResponse = mapper.convertValue(subTask.getData(), MatchUserResponse.class);
        return handleCancelMatchResponse(matchResponse, userId);
    }

    private Mono<Void> handleCancelMatchResponse(MatchUserResponse user, Long userId) {
        String name = user.getName();
        String member = userId + ":" + name;

        return Flux.concat(
                reactiveRedisTemplate.keys(MATCH_WAIT_KEY.formatted("*"))
                        .flatMap(key -> reactiveRedisTemplate.opsForZSet().remove(key, member)),
                reactiveRedisTemplate.keys(MATCH_WAIT_KEY_FOR_SCAN.formatted("*"))
                        .flatMap(key -> reactiveRedisTemplate.opsForZSet().remove(key, member))
        ).then();
    }



    //몇번째 순위로 대기중인지 반환
    public Mono<Long> getRank(final String queue, final Long userId) {
        String lockKey = "lock:getRank:" + userId;
        String lockValue = UUID.randomUUID().toString();

        return reactiveRedisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, Duration.ofSeconds(10))
                .flatMap(locked -> {
                    if (!locked) {
                        return Mono.just(-1L); // 잠금 획득 실패 시 -1 반환
                    }

                    return reactiveRedisTemplate.opsForValue().get("*:" + userId)
                            .flatMap(nickname -> {
                                if (nickname == null) {
                                    return Mono.just(-1L);
                                }

                                String member = nickname + ":" + userId;
                                return reactiveRedisTemplate.opsForZSet().rank(MATCH_WAIT_KEY.formatted(queue), member)
                                        .defaultIfEmpty(-1L)
                                        .map(rank -> rank >= 0 ? rank + 1 : rank)
                                        .doFinally(signal -> releaseLock(lockKey, lockValue).subscribe());
                            });
                });
    }


    public enum MatchStatus {
        MATCH_FOUND,
        MATCHING,
        NO_MATCH
    }

    public Mono<Tuple2<MatchStatus, MatchResponse>> getMatchResponse(Long memberId) {
        String key = "matchInfo:" + memberId;
        String lockKey = "lock:getMatchResponse:" + memberId;
        String lockValue = UUID.randomUUID().toString();

        return reactiveRedisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, Duration.ofSeconds(10))
                .flatMap(locked -> {
                    if (!locked) {
                        return Mono.error(new RuntimeException("Unable to acquire lock"));
                    }

                    return reactiveRedisTemplate.opsForValue().get(key)
                            .flatMap(matchResponseStr -> {
                                try {
                                    MatchResponse matchResponse = mapper.readValue(matchResponseStr, MatchResponse.class);

                                    List<SubTask> subTasks = new ArrayList<>();
                                    subTasks.add(taskUseCase.createSubTask(
                                            "MatchCodeUpdate",
                                            String.valueOf(memberId),
                                            SubTask.TaskType.membership,
                                            SubTask.TaskStatus.ready,
                                            matchResponse.getSpaceId()
                                    ));

                                    Task task = taskUseCase.createTask("Match Request", null, subTasks);

                                    return sendTask("task.membership.response", task)
                                            .then(reactiveRedisTemplate.unlink(key))
                                            .then(Mono.just(Tuples.of(MatchStatus.MATCH_FOUND, matchResponse)));

                                } catch (JsonProcessingException e) {
                                    log.error("getMatchResponse JsonProcessingException: ", e);
                                    return Mono.error(e);
                                }
                            })
                            .switchIfEmpty(
                                    getRank("match", memberId)
                                            .flatMap(rank -> {
                                                if (rank > -1) {
                                                    return Mono.just(Tuples.of(MatchStatus.MATCHING, new MatchResponse()));
                                                } else {
                                                    return Mono.just(Tuples.of(MatchStatus.NO_MATCH, new MatchResponse()));
                                                }
                                            })
                            )
                            .doFinally(signal -> releaseLock(lockKey, lockValue).subscribe());
                });
    }



    @Scheduled(initialDelay = 5000, fixedDelay = 1000)
    public void scheduleMatchUser() {
        if (!scheduling) {
            log.info("passed scheduling..");
            return;
        }

        String lockKey = "lock:scheduleMatchUser";
        String lockValue = UUID.randomUUID().toString();

        reactiveRedisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, Duration.ofSeconds(10))
                .flatMap(locked -> {
                    if (!locked) {
                        log.info("Another instance is already running");
                        return Mono.empty();
                    }

                    return reactiveRedisTemplate.scan(ScanOptions.scanOptions()
                                    .match(MATCH_WAIT_KEY_FOR_SCAN)
                                    .count(100).build())
                            .map(key -> key.split(":")[2])
                            .flatMap(queue -> reactiveRedisTemplate.opsForZSet()
                                    .range(MATCH_WAIT_KEY.formatted(queue), Range.closed(0L, MAX_ALLOW_USER_COUNT - 1))
                                    .collectList()
                                    .flatMap(members -> {
                                        if (members.size() == MAX_ALLOW_USER_COUNT) {
                                            String spaceId = UUID.randomUUID().toString();
                                            MatchResponse matchResponse = MatchResponse.fromMembers(spaceId, members);
                                            String MatchResponseJson = createMatchResponseJson(matchResponse);
                                            members.forEach(memberId -> {
                                                try {
                                                    String membershipId = memberId.split(":")[0];
                                                    String json = mapper.writeValueAsString(matchResponse);
                                                    reactiveRedisTemplate.opsForValue().set("matchInfo:" + membershipId, json).subscribe();
                                                } catch (JsonProcessingException e) {
                                                    log.error("scheduleMatchUser JsonProcessingException : ", e);
                                                }
                                            });

                                            List<SubTask> subTasks = new ArrayList<>();

                                            subTasks.add(
                                                    taskUseCase.createSubTask("Match response",
                                                            null,
                                                            SubTask.TaskType.match,
                                                            SubTask.TaskStatus.ready,
                                                            matchResponse));

                                            return sendTask("task.match.response", taskUseCase.createTask(
                                                    "Match response",
                                                    null,
                                                            subTasks))
                                                    .then(RemoveMembersFromQueue(queue, members))
                                                    .doOnSuccess(result -> log.info("Kafka message sent and members removed from Redis successfully."))
                                                    .doOnError(error -> log.error("Error during Kafka send or Redis operation: " + error.getMessage()))
                                                    .subscribeOn(Schedulers.boundedElastic());
                                        } else {
                                            return Mono.empty();
                                        }
                                    }))
                            .then()
                            .doFinally(signal -> releaseLock(lockKey, lockValue).subscribe());
                }).subscribe();
    }

    private String createMatchResponseJson(MatchResponse response) {
        try {
            return mapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            log.error("createMatchResponseJson JsonProcessingException : ", e);
            return "";
        }
    }


    private Mono<Void> RemoveMembersFromQueue(String queue,List<String> members) {
        return Flux.fromIterable(members)
                .flatMap(member -> reactiveRedisTemplate.opsForZSet().remove(String.format(MATCH_WAIT_KEY, queue), member))
                .then();
    }


    @Override
    public void run(ApplicationArguments args){

        this.TaskResponseConsumerTemplate
                .receiveAutoAck()
                .doOnNext(r -> {
                    Task task = r.value();

                    List<SubTask> subTasks = task.getSubTaskList();

                    for(var subtask : subTasks){

                        log.info("TaskResponseConsumerTemplate received : "+subtask.toString());
                        try {
                            switch (subtask.getSubTaskName()) {
                                default:
                                    log.warn("Unknown subtask: {}", subtask.getSubTaskName());
                                    break;
                            }
                        } catch (Exception e) {
                            log.error("Error processing subtask {}: {}", subtask.getSubTaskName(), e.getMessage());
                        }
                    }
                })
                .doOnError(e -> log.error("Error receiving: " + e))
                .subscribe();

        this.TaskRequestConsumerTemplate
                .receiveAutoAck()
                .doOnNext(r -> {
                    Task task = r.value();
                    taskResults.put(task.getTaskID(),task);

                    if(taskResults.size() > MAX_TASK_RESULT_SIZE){
                        taskResults.clear();
                        log.info("taskResults clear.");
                    }

                })
                .doOnError(e -> log.error("Error receiving: " + e))
                .subscribe();
    }

}