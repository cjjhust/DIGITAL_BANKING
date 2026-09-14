package com.jinjing.banking.config;

/**
 * Kafka 主题与 header 常量。
 *
 * <p>把这些字符串集中到一处，是为了避免"发送端靠框架默认值、接收端手写字符串"导致的不一致
 * ——本项目的死信主题就曾经因此对不上（框架发到 {@code banking-transfers-dlt}，
 * 监听器却在等 {@code banking-transfers.DLT}，272 条死信静默堆积）。
 */
public final class KafkaTopics {

    /** 转账主主题 */
    public static final String TRANSFER = "banking-transfers";

    /** 账本链路的死信主题（banking-group 消费失败） */
    public static final String TRANSFER_DLT = "banking-transfers.DLT";

    /**
     * 审计链路的死信主题（analytics-group 消费失败）。
     * 审计失败不该污染业务死信：业务死信意味着"账本可能出错"，需要人工介入；
     * 审计失败只意味着"分析数据少了一条"，重放或丢弃即可，处理方式和紧急程度都不同。
     */
    public static final String AUDIT_DLT = "banking-transfers.audit-DLT";

    /** 死信记录都会带上的来源标识 header */
    public static final String ORIGIN_HEADER = "x-origin-consumer";

    /** 来源：账本消费者 */
    public static final String ORIGIN_LEDGER = "banking-group";

    /** 来源：审计消费者 */
    public static final String ORIGIN_ANALYTICS = "analytics-group";

    private KafkaTopics() {
    }
}
