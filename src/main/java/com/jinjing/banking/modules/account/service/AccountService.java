package com.jinjing.banking.modules.account.service;

import com.jinjing.banking.common.exception.BusinessException;
import com.jinjing.banking.modules.account.entity.Account;
import com.jinjing.banking.modules.account.repository.AccountRepository;
import com.jinjing.banking.modules.transaction.entity.ProcessedTransaction;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.kafka.annotation.KafkaListener; // 保持不变
import org.springframework.kafka.support.KafkaHeaders;
import com.jinjing.banking.modules.account.dto.TransferRequest; // Import the new DTO
import com.jinjing.banking.modules.transaction.service.ProcessedTransactionService;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.Optional;

@Slf4j // 增加日志支持，这是德国银行项目监控的标配
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final TransferInternalService transferInternalService;
    private final ProcessedTransactionService processedTransactionService;

    public Account createAccount(Account account) {
        log.info("Creating new account for owner: {}", account.getOwnerName());
        return accountRepository.save(account);
    }

    public Optional<Account> getAccount(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber);
    }

    public Page<ProcessedTransaction> getTransactionHistory(String accountNumber, Pageable pageable) {
        return processedTransactionService.getTransactionsByAccount(accountNumber, pageable);
    }

    /**
     * [Kafka 消费者演进]
     * 以前是 Controller 直接调 transfer()，现在是由 Kafka 驱动。
     * 面试谈资：如果 Kafka 挂了怎么办？消息会堆积，但不会丢失。等 Service 重启后会自动继续处理。
     */
    @KafkaListener(topics = "banking-transfers", groupId = "banking-group")
    public void handleTransferEvent(@Header(KafkaHeaders.RECEIVED_KEY) String transactionId, TransferRequest request) {
        // 面试点：手动将业务 ID 放入 MDC，确保该线程后续所有日志都带上这个业务单号
        org.slf4j.MDC.put("bizId", transactionId);
        
        log.info("Kafka Consumer received transfer task with ID: {} from {} to {}",
                 transactionId, request.getFromAccountNo(), request.getToAccountNo());
         // --- 核心：幂等性检查 ---
        // 即使有了 Snowflake ID 保证消息唯一，Kafka 依然可能因为网络抖动、消费者重启等原因
        // 导致消息被“至少一次 (at-least-once)”投递，即同一条消息被消费多次。
        // 为了确保转账操作只执行一次，这里需要进行幂等性检查。

        // 1. 检查是否处理过
        if (processedTransactionService.isTransactionProcessed(transactionId)) {
            log.warn("Transaction {} already completed. Skipping.", transactionId);
            return;
        }

        // 2. 占坑（利用数据库唯一约束实现分布式锁）
        if (!processedTransactionService.markAsProcessing(
                transactionId, 
                request.getRequestId(), 
                request.getFromAccountNo(), 
                request.getToAccountNo(), 
                request.getAmount())) {
            return;
        }

        try {
            // 面试点：银行核心系统严禁在业务逻辑中直接使用浮点数，validateRisk 内部应严格校验精度
            validateRisk(request.getAmount());

            transferInternalService.executeTransfer(
                request.getFromAccountNo(), 
                request.getToAccountNo(), 
                request.getAmount());
            
            processedTransactionService.markAsCompleted(transactionId);
        } catch (BusinessException e) {
            // 面试点：业务异常（如余额不足）不应触发 Kafka 重试
            log.error("Business failure for transaction {}: {}", transactionId, e.getMessage());
            processedTransactionService.markAsFailed(transactionId, e.getMessage());
        } catch (Exception e) {
            // 系统异常（如数据库断开）：直接抛出，触发 KafkaConsumerConfig 里定义的 3 次重试。
            // 此时不要 markAsFailed，因为重试可能成功。
            log.error("System error for transaction {}, scheduling retry...", transactionId);
            throw new RuntimeException("System error: " + transactionId, e);
        } finally {
            org.slf4j.MDC.remove("bizId");
        }
    }

    private void validateRisk(BigDecimal amount) {
        if (amount.compareTo(new BigDecimal("10000")) > 0) {
            log.warn("Risk Alert: Transfer amount {} exceeds single transaction limit!", amount);
            throw new BusinessException("Single transfer limit exceeded (Max 10,000)", HttpStatus.FORBIDDEN);
        }
    }
}