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
}