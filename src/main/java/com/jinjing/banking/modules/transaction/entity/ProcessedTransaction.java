package com.jinjing.banking.modules.transaction.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "processed_transactions")
@Getter @Setter @NoArgsConstructor
@AllArgsConstructor @Builder
public class ProcessedTransaction {
    @Id
    private String transactionId; // 存储 Snowflake ID

    @Column(nullable = false, unique = true)
    private String clientRequestId; // 存储客户端传来的 UUID，用于跨重试的幂等校验

    @Column(nullable = false)
    private String fromAccountNo;

    @Column(nullable = false)
    private String toAccountNo;

    @Column(nullable = false)
    private BigDecimal amount; // 新增：记录交易金额

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private String errorMessage;

    public enum Status {
        PENDING, COMPLETED, FAILED
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}