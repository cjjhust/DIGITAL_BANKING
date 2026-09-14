package com.jinjing.banking.config;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Kafka 生产者拦截器：把当前 Span 的上下文注入到消息 Header，
 * 下游消费者据此把链路接起来。
 *
 * <p><b>为什么用静态 holder：</b>Kafka 通过 {@code interceptor.classes} 反射实例化拦截器，
 * 不经过 Spring 容器，拿不到注入的 Tracer/Propagator。所以这里保留一个无参构造器给 Kafka，
 * 由 Spring 构造的实例把 bean 存进静态引用，反射实例运行时取用。
 * （另一种做法是启用 Spring Kafka 的 observation，但那样就不需要这个拦截器了。）
 */
@Slf4j
@Component
public class KafkaTracingProducerInterceptor implements ProducerInterceptor<String, String> {

    /** Spring 容器里的实例在这里登记，供 Kafka 反射创建的实例使用 */
    private static volatile TracingSupport shared;

    private final Tracer injectedTracer;
    private final Propagator injectedPropagator;

    /** Kafka 反射实例化时使用 */
    public KafkaTracingProducerInterceptor() {
        this(null, null);
    }

    @Autowired
    public KafkaTracingProducerInterceptor(Tracer tracer, Propagator propagator) {
        this.injectedTracer = tracer;
        this.injectedPropagator = propagator;
        shared = new TracingSupport(tracer, propagator);
    }

    private record TracingSupport(Tracer tracer, Propagator propagator) {
    }

    @Override
    public ProducerRecord<String, String> onSend(ProducerRecord<String, String> record) {
        try {
            TracingSupport support = injectedTracer != null
                    ? new TracingSupport(injectedTracer, injectedPropagator)
                    : shared;

            if (support == null || support.tracer() == null || support.propagator() == null) {
                return record;
            }

            Span currentSpan = support.tracer().currentSpan();
            if (currentSpan == null || record.headers() == null) {
                return record;
            }

            Propagator.Setter<ProducerRecord<String, String>> setter =
                    (carrier, key, value) -> carrier.headers().add(key, value.getBytes(StandardCharsets.UTF_8));
            support.propagator().inject(currentSpan.context(), record, setter);
        } catch (Exception e) {
            // 追踪失败绝不能影响资金消息的发送
            log.warn("注入 Kafka trace header 失败（消息仍会正常发送）: {}", e.getMessage());
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
