package com.jinjing.banking.modules.account.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
//@Data
@Table(name = "accounts")
@Getter @Setter @NoArgsConstructor(access = AccessLevel.PROTECTED) // 满足 JPA 规范且防误用
@AllArgsConstructor @Builder
public class Account {
    @Id @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "acc_seq")
    /* 
     * 修改点：改 IDENTITY 为 SEQUENCE
     * generator 指定生成器名称
     * sequenceName 指定数据库中序列的名字
     * 使用 SEQUENCE 可以在 persist() 调用后立即获得 ID，而不需要触发 flush 或等待事务提交，这让领域模型的逻辑更连贯
     * allocationSize 建议设为 1 (面试加分：若设为 50 则是为了极致性能，但需要额外配置)
     * 配合 allocationSize（分段申请 ID），减少了高并发下与数据库的往返次数（Round-trips）
     */
    @SequenceGenerator(name = "acc_seq", sequenceName = "accounts_id_seq", allocationSize = 1)
    private Long id;

    @NotBlank(message = "Account number is required") // 新增：安检 1
    @Column(unique = true, nullable = false)
    private String accountNumber;

    @NotBlank(message = "Owner name is required") // 新增：安检 2
    @Column(nullable = false)
    private String ownerName;

    @Min(value = 0, message = "Balance cannot be negative")
    @Builder.Default
    @Column(nullable = false)
    private BigDecimal balance = BigDecimal.ZERO;

    @Version
    private Long version; // 硬核乐观锁，防止并发吞钱,每次修改数据库自动 +1，版本不一致则报错

    @Column(name = "created_at", updatable = false,nullable = false)
    //@Builder.Default
    //private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime createdAt; // 删掉 @Builder.Default

    @PrePersist // JPA 生命周期回调：在数据第一次写入数据库前自动调用，在存入数据库之前的最后一秒，自动执行这个方法。
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}