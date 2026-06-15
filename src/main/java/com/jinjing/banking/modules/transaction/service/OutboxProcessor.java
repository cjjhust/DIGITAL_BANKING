package com.jinjing.banking.modules.transaction.service;

import com.jinjing.banking.modules.transaction.entity.OutboxEvent;
import com.jinjing.banking.modules.transaction.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.BaggageInScope;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxProcessor {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObservationRegistry observationRegistry;
    private final Tracer tracer;

    /**
     * 业内生产环境最优方案：
     * 1. 高频轮询 (1s)，保证交易流转的准实时性。
     * 2. 配合 SKIP LOCKED 实现分布式实例间的任务抢占，无需额外的 Redis 锁开销。
     */
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void processOutbox() {
        // 1. 批量抓取（限制 100 条），防止大事务导致数据库长连接占用
        List<OutboxEvent> events = outboxEventRepository.fetchPendingEvents(PageRequest.of(0, 100));
        
        if (events.isEmpty()) return;

        for (OutboxEvent event : events) {
            try {
                // 硬核点：利用 Observation 手动恢复 Trace 链路
                // 即使是后台任务，也能在日志中看到与前端请求一致的 TraceID
                Observation.createNotStarted("outbox.relay", observationRegistry)
                    .highCardinalityKeyValue("transactionId", event.getAggregateId())
                    .observe(() -> {
                        // 这一步就是把“死”的数据变成“活”的上下文
                        // 恢复 Baggage，确保中继发送时依然带着业务单号
                        // 修正：使用 getBaggage 代替已弃用的 createBaggage
                        try (BaggageInScope baggage = tracer.getBaggage("transactionId").makeCurrent(event.getAggregateId())) {
                            log.info("Relaying event {} to Kafka topic {}", event.getAggregateId(), event.getTopic());
                            
                            try {
                                // 核心修正：必须同步等待结果 (.get())
                                // 在 Outbox 模式下，必须确保在当前数据库事务提交前完成 Kafka 确认和记录删除。
                                // 如果使用异步回调，事务会提前提交并释放 SKIP LOCKED 锁，导致下一秒的定时任务重复抓取该记录。
                                kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload()).get();
                                outboxEventRepository.delete(event);
                            } catch (Exception ex) {
                                throw new RuntimeException("Kafka relay failed for " + event.getAggregateId(), ex);
                            }
                        }
                    });
            } catch (Exception e) {
                log.error("Critical error in OutboxProcessor for event {}: {}", event.getAggregateId(), e.getMessage());
            }
        }
    }
}