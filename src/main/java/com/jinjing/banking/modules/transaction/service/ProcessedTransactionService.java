package com.jinjing.banking.modules.transaction.service;

import java.math.BigDecimal;
import com.jinjing.banking.modules.transaction.entity.ProcessedTransaction;
import com.jinjing.banking.modules.transaction.repository.ProcessedTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
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

    @Transactional
    public boolean markAsProcessing(String transactionId, String clientRequestId, String fromAccountNo, String toAccountNo, BigDecimal amount) {
        try {
            ProcessedTransaction pt = ProcessedTransaction.builder()
                    .transactionId(transactionId)
                    .clientRequestId(clientRequestId)
                    .status(ProcessedTransaction.Status.PENDING)
                    .fromAccountNo(fromAccountNo)
                    .toAccountNo(toAccountNo)
                    .amount(amount) // 新增：保存金额
                    .build();
            repository.saveAndFlush(pt);
            return true;
        } catch (DataIntegrityViolationException e) {
            log.warn("Transaction ID {} is already being processed.", transactionId);
            return false;
        }
    }

    @Transactional
    public void markAsCompleted(String transactionId) {
        repository.findById(transactionId).ifPresent(pt -> {
            pt.setStatus(ProcessedTransaction.Status.COMPLETED);
            repository.save(pt);
        });
    }

    @Transactional
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
}