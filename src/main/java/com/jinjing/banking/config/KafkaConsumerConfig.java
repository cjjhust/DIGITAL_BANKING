package com.jinjing.banking.config;

import com.jinjing.banking.modules.account.dto.TransferRequest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.converter.JacksonJsonMessageConverter;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ConsumerFactory<Object, Object> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "banking-group");
        
        // 面试点：控制消费速率，防止压垮数据库
        // 每次只拉取 10 条数据，配合数据库的吞吐量
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        // 如果单笔交易处理极慢（比如涉及外部风控接口），需要调大这个时间，防止 Kafka 认为消费者挂了
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000); // 5分钟

        // 性能巅峰：使用 ByteArrayDeserializer 拿原始字节
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);

        // 使用 Object 泛型，解决与 ListenerContainerFactory 的类型匹配冲突
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransferRequest> kafkaListenerContainerFactory(
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<Object, Object> kafkaTemplate) { // 改为 Object，处理所有可能的错误消息
        ConcurrentKafkaListenerContainerFactory<String, TransferRequest> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // 设置并发量：如果 Kafka 有多个 Partition，可以开启多个线程并行处理
        // 这能显著提升数据库在高负载下的写入效率
        factory.setConcurrency(3);

        // 修复过时：JacksonJsonMessageConverter 是 4.0 时代的正统转换器
        // 它能自动识别 byte[] 负载并将其转换为监听器方法中指定的 TransferRequest 对象
        factory.setRecordMessageConverter(new JacksonJsonMessageConverter());

        // 修复点 1：创建死信队列恢复器，它使用 kafkaTemplate 来发送失败的消息
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);

        
        // 配置错误处理器：管理重试动作与死信路由
        // FixedBackOff(1000L, 3L) 意味着：如果 handleTransferEvent 抛出 RuntimeException，
        // 框架会每隔 1 秒重新调用该方法一次，最多尝试 3 次（1次初始 + 2次重试）。
        // 若 3 次尝试均告失败，则由 recoverer 将消息转发至 DLT 队列，不再自动重试。
        // 修复点 2：构造函数现在接收 (recoverer, backOff)
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
        
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD); // 记录级别确认
        return factory;
    }
}