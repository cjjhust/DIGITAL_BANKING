package com.jinjing.banking.config;

import com.jinjing.banking.modules.account.dto.TransferRequest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.converter.JacksonJsonMessageConverter;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ConsumerFactory<String, TransferRequest> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "banking-group");
        
        // 面试点：控制消费速率，防止压垮数据库
        // 每次只拉取 10 条数据，配合数据库的吞吐量
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        // 如果单笔交易处理极慢（比如涉及外部风控接口），需要调大这个时间，防止 Kafka 认为消费者挂了
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000); // 5分钟

        // Producer 和 DLT 都使用 StringSerializer，这里必须保持类型一致。
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // 注册追踪拦截器：把当前 Span 写进消息 Header，否则异步链路会断在 Kafka 这一跳。
        // Kafka 是反射实例化拦截器的，无法注入 Spring bean，具体处理见 KafkaTracingProducerInterceptor。
        props.put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG,
                List.of(KafkaTracingProducerInterceptor.class.getName()));
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory);
        // 显式开启：这个 template 是手工 new 的，Boot 的 spring.kafka.template.observation-enabled
        // 只作用于自动配置的 bean，对本例无效。不开就不会产生 send span。
        template.setObservationEnabled(true);
        return template;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransferRequest> kafkaListenerContainerFactory(
            ConsumerFactory<String, TransferRequest> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<String, TransferRequest> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // 设置并发量：如果 Kafka 有多个 Partition，可以开启多个线程并行处理
        // 这能显著提升数据库在高负载下的写入效率
        factory.setConcurrency(3);

        // 必须与监听方法的 @Payload 类型一致：装了 JacksonJsonMessageConverter 就只能写 DTO，
        // 早期 analytics 消费者写成 String，结果每条消息都反序列化失败并进死信。
        factory.setRecordMessageConverter(new JacksonJsonMessageConverter());

        // 账本链路死信：目的地显式写成 "<topic>.DLT"（不依赖框架默认值，它可能变成 "<topic>-dlt"），
        // 并打上来源 header，便于 DLT 监听器判断"到底是谁失败了"。
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                dltRecoverer(kafkaTemplate, KafkaTopics.TRANSFER_DLT, KafkaTopics.ORIGIN_LEDGER),
                new FixedBackOff(1000L, 3L)));

        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD); // 记录级别确认
        // 显式开启 Micrometer 绑定：否则 Kafka 客户端指标只进 JMX，不进 Prometheus，
        // Grafana 上的 consumer lag 面板会一直是 No data（实测如此）。
        factory.getContainerProperties().setMicrometerEnabled(true);
        // [链路贯通] 开启监听器观测：消费端从消息 header 恢复父上下文。
        // 必须写在代码里 —— 这个工厂是手工 new 的，application.yml 里的
        // spring.kafka.listener.observation-enabled 只对 Boot 自动配置的工厂生效。
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    /**
     * 审计消费者专用容器工厂：同样的 JSON 转换器，但失败后进<b>独立的</b>审计死信主题。
     *
     * <p>这样做的原因：审计写 ClickHouse 失败并不代表转账失败，不应该和账本死信混在一个主题里，
     * 否则 DLT 监听器无法区分来源（曾经的实现就是因此把已 COMPLETED 的转账改成了 FAILED）。
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransferRequest> analyticsListenerContainerFactory(
            ConsumerFactory<String, TransferRequest> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<String, TransferRequest> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(1);
        factory.setRecordMessageConverter(new JacksonJsonMessageConverter());
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                dltRecoverer(kafkaTemplate, KafkaTopics.AUDIT_DLT, KafkaTopics.ORIGIN_ANALYTICS),
                new FixedBackOff(1000L, 3L)));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setMicrometerEnabled(true);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    /**
     * 构造死信恢复器：目的地与来源 header 都显式指定。
     */
    private static DeadLetterPublishingRecoverer dltRecoverer(KafkaTemplate<String, String> kafkaTemplate,
                                                             String dltTopic,
                                                             String origin) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(dltTopic, record.partition()));

        // 框架会自动加 kafka_dlt-* 系列 header（原始 topic/offset/异常类），
        // 我们再补一个业务可读的来源标识：消费者组名。
        recoverer.addHeadersFunction((record, exception) -> {
            Headers headers = new RecordHeaders();
            headers.add(KafkaTopics.ORIGIN_HEADER, origin.getBytes(StandardCharsets.UTF_8));
            return headers;
        });
        return recoverer;
    }

    /**
     * DLT 监听专用容器工厂：<b>不挂 JSON 消息转换器</b>。
     *
     * <p>死信里的 payload 很可能本来就是无法反序列化的坏消息，如果再经过
     * {@link JacksonJsonMessageConverter} 会二次失败，消息就会被困在重试循环里。
     * 这里直接以原始 String 交给监听器，保证兜底逻辑一定跑得起来。
     */
    @Bean
    @SuppressWarnings("unchecked")
    public ConcurrentKafkaListenerContainerFactory<String, String> dltListenerContainerFactory(
            ConsumerFactory<String, TransferRequest> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory((ConsumerFactory<String, String>) (ConsumerFactory<?, ?>) consumerFactory);
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }
}