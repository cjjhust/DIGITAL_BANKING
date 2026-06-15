package com.jinjing.banking.config;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Kafka 生产者拦截器，用于将 Micrometer Tracing 的上下文注入到 Kafka 消息的 Header 中。
 * 这样，下游消费者可以从 Header 中提取 Trace Context，实现全链路追踪。
 */
@Component
@RequiredArgsConstructor
public class KafkaTracingProducerInterceptor implements ProducerInterceptor<String, String> {

    private final Tracer tracer;
    private final Propagator propagator;

    @Override
    public ProducerRecord<String, String> onSend(ProducerRecord<String, String> record) {
        // 获取当前 Span 的上下文
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            Propagator.Setter<ProducerRecord<String, String>> setter = 
                (carrier, key, value) -> carrier.headers().add(key, value.getBytes());
            propagator.inject(currentSpan.context(), record, setter);
        }
        return record;
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        // 不做任何处理
    }

    @Override
    public void close() {
        // 不做任何处理
    }

    @Override
    public void configure(Map<String, ?> configs) {
        // 不做任何处理
    }
}
