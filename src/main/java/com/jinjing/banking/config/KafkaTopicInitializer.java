package com.jinjing.banking.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 显式确保 Kafka 主题存在、且分区数符合声明。
 *
 * <h2>为什么不用 {@code NewTopic} + {@code KafkaAdmin}</h2>
 *
 * <p>2026-09-15 用全新克隆实测发现：本项目的 {@code NewTopic} 声明<b>从未生效</b>。
 * 实际建主题的是 broker 的 {@code auto.create.topics.enable}（用默认
 * {@code num.partitions=1}），所以 {@code concurrency=3} 一直配着 1 个分区。
 * 旧环境之所以看起来正常，是因为当初手工执行过一次
 * {@code kafka-topics --alter --partitions 3} —— 那一步不在仓库里，无法复现。
 *
 * <p>更根本的原因是 {@code NewTopic} 只能「创建」，<b>不能修改已存在的主题</b>。
 * 一旦被 auto-create 抢先建错，无论等多久都不会自动纠正。
 *
 * <p>所以这里改成显式 ensure，覆盖三种情况：
 * <ol>
 *   <li>主题不存在 → 按声明创建</li>
 *   <li>主题存在但分区数不足 → 扩容（这正是当初要手工 {@code --alter} 做的事）</li>
 *   <li>分区数已足够 → 不动，保持幂等</li>
 * </ol>
 *
 * <h2>执行时机</h2>
 *
 * <p>实现 {@link SmartInitializingSingleton}：它在
 * {@code SmartInitializingSingleton.afterSingletonsInstantiated()} 阶段执行，
 * 早于 Kafka 监听容器（{@code SmartLifecycle.start()}）启动 ——
 * 保证消费者第一次请求元数据时主题已经就位，不会再出现
 * {@code UNKNOWN_TOPIC_OR_PARTITION}。
 *
 * <p>失败策略：Kafka 不可用时<b>只记 ERROR 不中断启动</b>。这样容器编排可以正常
 * 拉起应用，运维能从日志看到问题；若直接抛异常，只会得到一个反复重启的容器，
 * 反而更难排查。
 */
@Component
@Slf4j
public class KafkaTopicInitializer implements SmartInitializingSingleton {

    /** 主题名 → 期望分区数。用 LinkedHashMap 保证日志顺序稳定，便于对照。 */
    private static final Map<String, Integer> REQUIRED_TOPICS = new LinkedHashMap<>();

    static {
        // 3 个分区是为了匹配消费者侧 concurrency=3：一个分区只能被一个消费者线程消费，
        // 分区数少于并发数时，多出来的线程会一直空闲。
        REQUIRED_TOPICS.put(KafkaTopics.TRANSFER, 3);
        // 死信主题是人工兜底通道，流量极低，1 个分区足够且能保证顺序。
        REQUIRED_TOPICS.put(KafkaTopics.TRANSFER_DLT, 1);
        REQUIRED_TOPICS.put(KafkaTopics.AUDIT_DLT, 1);
    }

    private static final int ADMIN_TIMEOUT_SECONDS = 10;

    /**
     * 直接读配置项，不注入 {@code KafkaProperties}。
     *
     * <p>Boot 4 把 Kafka 自动配置拆成了独立模块，{@code KafkaProperties} 从
     * {@code org.springframework.boot.autoconfigure.kafka} 搬到了别的包。
     * 这里只需要一个 bootstrap 地址，用 {@code @Value} 取更稳 —— 不会因为
     * 框架小版本的包路径变动而编译失败。
     */
    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, Object> config = Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        try (AdminClient admin = AdminClient.create(config)) {
            Set<String> existing = admin.listTopics().names()
                    .get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("Kafka 主题检查：期望 {} 个，broker 上已有 {} 个", REQUIRED_TOPICS.size(), existing.size());

            createMissing(admin, existing);
            enlargeUnderPartitioned(admin, existing);
        } catch (Exception e) {
            // 不中断启动：让容器能起来，问题在日志里可见
            log.error("Kafka 主题初始化失败（broker 不可达或权限不足）：{}", e.getMessage(), e);
        }
    }

    private void createMissing(AdminClient admin, Set<String> existing) throws Exception {
        List<NewTopic> missing = new ArrayList<>();
        for (Map.Entry<String, Integer> e : REQUIRED_TOPICS.entrySet()) {
            if (!existing.contains(e.getKey())) {
                missing.add(new NewTopic(e.getKey(), e.getValue(), (short) 1));
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        admin.createTopics(missing).all().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        missing.forEach(t -> log.info("已创建 Kafka 主题 {}（{} 个分区）", t.name(), t.numPartitions()));
    }

    /**
     * 修正「已存在但分区数偏少」的主题。
     *
     * <p>这是 {@code NewTopic} 做不到的事，也是这个类存在的核心理由：
     * 主题一旦被 auto-create 按默认值建出来，声明式的 {@code NewTopic} 永远不会纠正它。
     *
     * <p>注意：Kafka 只支持增加分区，<b>不支持减少</b>。所以这里只在「实际 &lt; 期望」时扩容。
     */
    private void enlargeUnderPartitioned(AdminClient admin, Set<String> existing) throws Exception {
        Map<String, NewPartitions> toEnlarge = new LinkedHashMap<>();

        for (Map.Entry<String, Integer> e : REQUIRED_TOPICS.entrySet()) {
            String topic = e.getKey();
            int wanted = e.getValue();
            if (!existing.contains(topic)) {
                continue;
            }
            int actual = admin.describeTopics(List.of(topic))
                    .allTopicNames().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .get(topic).partitions().size();

            if (actual < wanted) {
                toEnlarge.put(topic, NewPartitions.increaseTo(wanted));
                log.warn("Kafka 主题 {} 分区数不足（实际 {} < 期望 {}），将扩容。"
                                + "通常说明它曾被 broker 的 auto-create 按默认值建出来。",
                        topic, actual, wanted);
            } else if (actual > wanted) {
                // 不报警只提示：Kafka 无法减少分区，多出来的分区不影响正确性
                log.info("Kafka 主题 {} 分区数多于声明（实际 {} > 期望 {}），保持不动（Kafka 不支持减少分区）",
                        topic, actual, wanted);
            }
        }

        if (!toEnlarge.isEmpty()) {
            admin.createPartitions(toEnlarge).all().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            toEnlarge.keySet().forEach(t -> log.info("已扩容 Kafka 主题 {} 到 {} 个分区",
                    t, REQUIRED_TOPICS.get(t)));
        }
    }
}
