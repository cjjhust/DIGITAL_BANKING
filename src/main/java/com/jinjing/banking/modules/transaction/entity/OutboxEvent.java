package com.jinjing.banking.modules.transaction.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "outbox_events")
@Getter @Setter @NoArgsConstructor
@AllArgsConstructor @Builder
public class OutboxEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String aggregateId; // 存储 Snowflake ID，作为业务流水号，用于 Kafka Key

    @Column(nullable = false, unique = true) // 确保 clientRequestId 在 Outbox 表中唯一
    private String clientRequestId; // 存储客户端传来的 UUID，用于 Controller 层的幂等查重

    @Column(nullable = false)
    private String topic;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payload; // 存储 TransferRequest 的 JSON 字符串

    // 新增：保存 Trace 上下文，确保异步链路不断裂
    private String traceId;
    private String spanId;
    private String traceParent; // 兼容 W3C 标准的完整上下文

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}