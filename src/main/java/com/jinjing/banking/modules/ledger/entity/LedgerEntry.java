package com.jinjing.banking.modules.ledger.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 复式记账分录：一笔转账拆成借贷两条，金额相等、方向相反。
 *
 * <p>为什么需要它：单张 {@code accounts.balance} 是"余额快照"，不是账本。
 * 余额被改错时无法发现，也无法解释"这笔余额由哪些业务事件构成"。
 * 有了分录之后，"钱扣了但没入账"会立刻表现为借贷不平。
 */
@Entity
@Table(name = "ledger_entries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LedgerEntry {

    /** 借贷方向。DEBIT=转出（余额减少），CREDIT=转入（余额增加）。 */
    public enum Direction {
        DEBIT, CREDIT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false)
    private String transactionId;

    @Column(name = "account_no", nullable = false)
    private String accountNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Direction direction;

    /** 永远存正数，方向由 direction 表达，便于 SUM 与对账。 */
    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
