package com.jinjing.banking.modules.transaction.repository;

import com.jinjing.banking.modules.transaction.entity.ProcessedTransaction;
import java.math.BigDecimal;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;


@Repository
public interface ProcessedTransactionRepository extends JpaRepository<ProcessedTransaction, String> {
    Optional<ProcessedTransaction> findByTransactionIdAndStatus(String transactionId, ProcessedTransaction.Status status);

    // 查询某个账户作为发起方或接收方的所有交易
    @Query("SELECT pt FROM ProcessedTransaction pt WHERE pt.fromAccountNo = :accountNumber OR pt.toAccountNo = :accountNumber ORDER BY pt.createdAt DESC")
    Page<ProcessedTransaction> findByAccountNo(@Param("accountNumber") String accountNumber, Pageable pageable);

    Optional<ProcessedTransaction> findByTransactionId(String transactionId);

    // 受理结果查询：用客户端 requestId 定位最终状态
    Optional<ProcessedTransaction> findByClientRequestId(String clientRequestId);

    // GDPR 导出：一次取回多个账户的流水
    @Query("SELECT pt FROM ProcessedTransaction pt WHERE pt.fromAccountNo IN :accountNumbers OR pt.toAccountNo IN :accountNumbers ORDER BY pt.createdAt DESC")
    List<ProcessedTransaction> findByAccountNumbers(@Param("accountNumbers") java.util.Collection<String> accountNumbers);

    // 硬核幂等检查：判断该客户端请求是否已经作为最终结果落库
    boolean existsByClientRequestId(String clientRequestId);

    @Modifying
    @Query(value = "INSERT INTO processed_transactions "
            + "(transaction_id, client_request_id, from_account_no, to_account_no, amount, status, created_at, updated_at) "
            + "VALUES (:transactionId, :clientRequestId, :fromAccountNo, :toAccountNo, :amount, 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) "
            + "ON CONFLICT DO NOTHING", nativeQuery = true)
    int insertPendingIfAbsent(@Param("transactionId") String transactionId,
                              @Param("clientRequestId") String clientRequestId,
                              @Param("fromAccountNo") String fromAccountNo,
                              @Param("toAccountNo") String toAccountNo,
                              @Param("amount") BigDecimal amount);

    List<ProcessedTransaction> findByStatusAndCreatedAtBefore(ProcessedTransaction.Status status, LocalDateTime dateTime);

    /**
     * 原子认领一条超时 PENDING：单条 UPDATE 同时完成「检查状态」和「占用」，
     * 多实例并发时只有一个实例能把 affected rows 变成 1（SELECT + UPDATE 两步做不到这点）。
     * 已有未过期租约的记录不会被重复认领。
     */
    @Modifying
    @Query(value = "UPDATE processed_transactions SET lease_owner = :owner, "
            + "lease_expires_at = CURRENT_TIMESTAMP + (:ttlSeconds * INTERVAL '1 second'), "
            + "updated_at = CURRENT_TIMESTAMP "
            + "WHERE transaction_id = :transactionId AND status = 'PENDING' "
            + "AND (lease_expires_at IS NULL OR lease_expires_at < CURRENT_TIMESTAMP)",
            nativeQuery = true)
    int claimPending(@Param("transactionId") String transactionId,
                     @Param("owner") String owner,
                     @Param("ttlSeconds") long ttlSeconds);
}