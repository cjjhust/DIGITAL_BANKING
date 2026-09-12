package com.jinjing.banking.modules.transaction.service;

import com.jinjing.banking.modules.transaction.entity.ProcessedTransaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class PendingRecoveryService {

    private final ProcessedTransactionService processedTransactionService;

    @Scheduled(fixedRate = 300000) // 每 5 分钟执行一次
    public void recoverPendingTransactions() {
        log.info("Starting PENDING recovery scan...");
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(5); // 查找 5 分钟前仍处于 PENDING 的交易
        List<ProcessedTransaction> pendingTransactions = processedTransactionService.getPendingTransactionsOlderThan(threshold);

        if (pendingTransactions.isEmpty()) {
            log.info("No old PENDING transactions found.");
            return;
        }

        log.error("Found {} stale PENDING transactions. Manual reconciliation is required.", pendingTransactions.size());

        for (ProcessedTransaction pt : pendingTransactions) {
            log.error("Stale PENDING transaction requires reconciliation: {}, createdAt={}",
                    pt.getTransactionId(), pt.getCreatedAt());
        }
        log.info("PENDING recovery scan completed.");
    }
}
