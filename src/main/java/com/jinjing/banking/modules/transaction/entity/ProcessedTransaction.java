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

    /**
     * PENDING 租约：哪个实例认领了这条超时交易。
     * 多实例部署时，没有租约的话每个实例的定时任务都会扫到同一批记录，重复告警/重复补偿。
     */
    @Column(name = "lease_owner", length = 120)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

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