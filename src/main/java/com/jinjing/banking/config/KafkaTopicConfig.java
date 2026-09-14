package com.jinjing.banking.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 主题声明：显式建出需要的分区数，不再依赖 Kafka 的 auto-create 默认值。
 *
 * <p><b>为什么必须显式声明：</b>{@code KafkaConsumerConfig} 把消费者并发设成了
 * {@code concurrency = 3}，但 Kafka 的并行单位是 <b>partition</b>——同一个消费组内
 * 一个 partition 只能被一个消费者线程消费。auto-create 建出来的主题默认只有 1 个分区，
 * 于是 3 个线程里只有 1 个真正在工作，另外 2 个纯闲置（实测：
 * {@code kafka-topics --describe} 显示 {@code PartitionCount: 1}）。
 *
 * <p>所以「并发度」这个参数要生效，前提是分区数 ≥ 并发度，而分区数必须在主题侧声明，
 * 消费者怎么调都补不回来。
 *
 * <p><b>注意：{@link NewTopic} 只在主题不存在时生效</b>，不会调整已存在主题的分区数。
 * 对已有集群需要手工扩容（分区只能增不能减）：
 * <pre>
 * docker exec banking-kafka kafka-topics --bootstrap-server kafka:9092 \
 *   --alter --topic banking-transfers --partitions 3
 * </pre>
 * 扩容前要清楚一个代价：<b>增加分区会打乱 key → partition 的映射</b>，
 * 原本「同一笔转账的先后消息落在同一分区、因此有序」的保证会失效。
 * 本项目用 {@code aggregateId} 作为消息 key，扩容后同一 aggregateId 仍固定映射到
 * 某个分区，只是历史消息可能落在别的分区上，因此扩容应在低峰期做。
 */
@Configuration
public class KafkaTopicConfig {

    /** 消费者并发度：与 {@code KafkaConsumerConfig} 中的 {@code setConcurrency(3)} 保持一致 */
    private static final int TRANSFER_PARTITIONS = 3;

    /** 死信主题单分区：死信是异常路径，量小且需要人工有序排查 */
    private static final int DLT_PARTITIONS = 1;

    private static final int REPLICAS = 1;

    @Bean
    public NewTopic transferTopic() {
        return TopicBuilder.name(KafkaTopics.TRANSFER)
                .partitions(TRANSFER_PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }

    @Bean
    public NewTopic transferDltTopic() {
        return TopicBuilder.name(KafkaTopics.TRANSFER_DLT)
                .partitions(DLT_PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }

    @Bean
    public NewTopic auditDltTopic() {
        return TopicBuilder.name(KafkaTopics.AUDIT_DLT)
                .partitions(DLT_PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }
}
