package com.jinjing.banking.common.util;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 雪花 ID 生成器。
 *
 * <p>此前这段逻辑写在 {@code AccountController} 里，有一个真实缺陷：
 * 序列号用 {@code sequence.getAndIncrement() % 4096}，同一毫秒内超过 4096 个请求时
 * 序列号会<b>回绕</b>到 0，于是同一毫秒内可能生成完全相同的 ID。
 * 银行场景下 ID 重复意味着两笔不同的转账被认为是同一笔。
 *
 * <p>这里的做法是标准写法：序列号用尽时**等到下一毫秒**，绝不回绕。
 * 代价是那一毫秒的请求会有微小等待，收益是 ID 绝不可能重复。
 *
 * <p>结构：41 位毫秒时间戳 + 10 位 workerId + 12 位序列号。
 * 多实例部署时每个实例必须配置不同的 {@code banking.snowflake.worker-id}（0~1023），
 * 否则不同实例在同一毫秒会生成相同 ID。
 */
@Component
@Slf4j
public class SnowflakeIdGenerator {

    /** 自定义纪元 2024-01-01，41 位时间戳可用约 69 年 */
    private static final long CUSTOM_EPOCH = 1704067200000L;

    private static final int WORKER_ID_BITS = 10;
    private static final int SEQUENCE_BITS = 12;
    private static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;   // 1023
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;     // 4095

    private final long workerId;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(@Value("${banking.snowflake.worker-id:0}") long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                    "banking.snowflake.worker-id 必须在 0~" + MAX_WORKER_ID + " 之间，当前为 " + workerId);
        }
        this.workerId = workerId;
    }

    @PostConstruct
    void logWorkerId() {
        log.info("SnowflakeIdGenerator 已就绪，workerId={}（多实例部署必须各不相同）", workerId);
    }

    public synchronized long nextId() {
        long timestamp = currentMillis();

        if (timestamp < lastTimestamp) {
            // 时钟回拨：拒绝生成，避免产生可能重复的 ID（用短等待而不是回绕）
            long offset = lastTimestamp - timestamp;
            if (offset > 5) {
                throw new IllegalStateException("检测到时钟回拨 " + offset + "ms，拒绝生成 ID");
            }
            timestamp = waitUntil(lastTimestamp);
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // 本毫秒的 4096 个序列号用尽：等到下一毫秒，绝不复用序列号
                timestamp = waitUntil(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        return ((timestamp - CUSTOM_EPOCH) << (WORKER_ID_BITS + SEQUENCE_BITS))
                | (workerId << SEQUENCE_BITS)
                | sequence;
    }

    public String nextIdAsString() {
        return String.valueOf(nextId());
    }

    private long waitUntil(long lastTimestamp) {
        long timestamp = currentMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = currentMillis();
        }
        return timestamp;
    }

    private long currentMillis() {
        return System.currentTimeMillis();
    }
}
