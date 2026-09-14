package com.jinjing.banking.modules.transaction.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import com.jinjing.banking.modules.transaction.entity.ProcessedTransaction;
import com.jinjing.banking.modules.transaction.repository.ProcessedTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProcessedTransactionService {
    private final ProcessedTransactionRepository repository;

    public boolean isTransactionProcessed(String transactionId) {
        return repository.findByTransactionIdAndStatus(transactionId, ProcessedTransaction.Status.COMPLETED).isPresent();
    }

    public boolean existsByClientRequestId(String clientRequestId) {
        return repository.existsByClientRequestId(clientRequestId);
    }

    /**
     * 占坑必须是独立事务（REQUIRES_NEW）。
     * 否则一旦下游 executeTransfer 抛异常把外层事务标记为 rollback-only，
     * 占坑记录和失败标记都会被一起回滚，等于什么状态都没留下。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markAsProcessing(String transactionId, String clientRequestId, String fromAccountNo, String toAccountNo, BigDecimal amount) {
        boolean inserted = repository.insertPendingIfAbsent(
                transactionId, clientRequestId, fromAccountNo, toAccountNo, amount) == 1;
        if (!inserted) {
            log.warn("Transaction {} is already being processed or has a final state.", transactionId);
        }
        return inserted;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAsCompleted(String transactionId) {
        repository.findById(transactionId).ifPresent(pt -> {
            pt.setStatus(ProcessedTransaction.Status.COMPLETED);
            repository.save(pt);
        });
    }

    /**
     * 独立事务：业务失败（余额不足、超限）与 DLT 兜底都必须能把 FAILED + 原因写进库，
     * 否则调用方无法通过 requestId 查询失败原因。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAsFailed(String transactionId, String error) {
        repository.findById(transactionId).ifPresent(pt -> {
            pt.setStatus(ProcessedTransaction.Status.FAILED);
            pt.setErrorMessage(error);
            repository.save(pt);
        });
    }

    public Page<ProcessedTransaction> getTransactionsByAccount(String accountNumber, Pageable pageable) {
        return repository.findByAccountNo(accountNumber, pageable);
    }

    public Optional<ProcessedTransaction> getTransactionById(String transactionId) {
        return repository.findByTransactionId(transactionId);
    }

    /**
     * 受理结果查询：按客户端 requestId 查最终状态（PENDING / COMPLETED / FAILED）。
     */
    public Optional<ProcessedTransaction> getByClientRequestId(String clientRequestId) {
        return repository.findByClientRequestId(clientRequestId);
    }

    /**
     * GDPR 导出：批量取回多个账户的流水；账户为空时直接返回空列表，避免生成空 IN 条件。
     */
    public List<ProcessedTransaction> getTransactionsByAccounts(java.util.Collection<String> accountNumbers) {
        if (accountNumbers == null || accountNumbers.isEmpty()) {
            return List.of();
        }
        return repository.findByAccountNumbers(accountNumbers);
    }

    public List<ProcessedTransaction> getPendingTransactionsOlderThan(LocalDateTime dateTime) {
        return repository.findByStatusAndCreatedAtBefore(ProcessedTransaction.Status.PENDING, dateTime);
    }

    /**
     * 认领一条超时 PENDING（独立事务，保证租约立即生效）。
     * 返回 true 表示本实例拿到了这条记录的处理权；false 表示已被其它实例认领或已不是 PENDING。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimStalePending(String transactionId, String owner, long ttlSeconds) {
        return repository.claimPending(transactionId, owner, ttlSeconds) == 1;
    }
}