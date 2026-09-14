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
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.Span;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxProcessor {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
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
                // [链路贯通的关键] 用受理时落库的 traceId/spanId 恢复「远程父上下文」。
                //
                // 只新建 span 是不够的：后台线程没有原来那笔 HTTP 请求的 ThreadLocal，
                // 如果直接 nextSpan()，中继会开一条**全新的 trace**，
                // 结果是「HTTP 受理」和「异步中继 + 消费」变成两条互不相干的链路
                // （实测：Zipkin 里 20 条 trace 有 19 条只有 1 个 span）。
                //
                // 库里存的 traceId/spanId 就是干这个用的：Span.Builder.setParent(...)
                // 让新 span 直接挂到原来那笔业务请求下面，一条 trace 从头贯到尾。
                Span.Builder spanBuilder = tracer.spanBuilder()
                        .name("outbox.relay")
                        .tag("transactionId", event.getAggregateId())
                        .tag("kafka.topic", event.getTopic());

                boolean hasRemoteParent = StringUtils.hasText(event.getTraceId())
                        && StringUtils.hasText(event.getSpanId());
                if (hasRemoteParent) {
                    spanBuilder.setParent(tracer.traceContextBuilder()
                            .traceId(event.getTraceId())
                            .spanId(event.getSpanId())
                            .sampled(true)
                            .build());
                } else {
                    spanBuilder.setNoParent();
                }

                Span relaySpan = spanBuilder.start();
                try (Tracer.SpanInScope scope = tracer.withSpan(relaySpan);
                     BaggageInScope baggage = tracer.createBaggageInScope("transactionId", event.getAggregateId())) {

                    if (!hasRemoteParent) {
                        log.warn("Event {} has no stored trace context; relay span starts a new trace", event.getAggregateId());
                    }
                    log.info("Relaying event {} to Kafka topic {}", event.getAggregateId(), event.getTopic());

                    try {
                        // 核心修正：必须同步等待结果 (.get())
                        // 在 Outbox 模式下，必须确保在当前数据库事务提交前完成 Kafka 确认和记录删除。
                        // 如果使用异步回调，事务会提前提交并释放 SKIP LOCKED 锁，导致下一秒的定时任务重复抓取该记录。
                        kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload()).get();
                        outboxEventRepository.delete(event);
                    } catch (Exception ex) {
                        relaySpan.error(ex);
                        throw new RuntimeException("Kafka relay failed for " + event.getAggregateId(), ex);
                    }
                } finally {
                    relaySpan.end();
                }
            } catch (Exception e) {
                log.error("Critical error in OutboxProcessor for event {}: {}", event.getAggregateId(), e.getMessage());
            }
        }
    }
}