package com.jinjing.banking.modules.account.service;

import com.jinjing.banking.common.exception.BusinessException;
import com.jinjing.banking.config.KafkaTopics;
import com.jinjing.banking.modules.account.dto.AccountCreateDTO;
import com.jinjing.banking.modules.account.entity.Account;
import com.jinjing.banking.modules.account.entity.User;
import com.jinjing.banking.modules.account.repository.AccountRepository;
import com.jinjing.banking.modules.account.repository.UserRepository;
import com.jinjing.banking.modules.transaction.entity.ProcessedTransaction;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.kafka.annotation.KafkaListener; // 保持不变
import org.springframework.kafka.support.KafkaHeaders;
import com.jinjing.banking.modules.account.dto.TransferRequest; // Import the new DTO
import com.jinjing.banking.modules.analytics.service.TransferOutcomeRecorder;
import com.jinjing.banking.modules.transaction.service.ProcessedTransactionService;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Optional;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;

@Slf4j // 增加日志支持，这是德国银行项目监控的标配
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final TransferInternalService transferInternalService;
    private final ProcessedTransactionService processedTransactionService;
    /**
     * 终态结果写入 ClickHouse（成功率 / 失败原因分析用）。
     * 与 {@code TransactionAnalyticsConsumer} 的「接收流水」分工不同，详见 7.13 与 §8.1。
     */
    private final TransferOutcomeRecorder transferOutcomeRecorder;
    /**
     * 成功/失败计数器。
     *
     * <p>[为什么放在这里而不是 {@link TransferInternalService}]：业务校验（自转账、超限、金额精度）
     * 发生在 {@code validateRisk()}，位于 {@code executeTransfer()} 之外。原先计数器加在
     * executeTransfer 内部，导致这类拒绝<b>完全不计入失败率</b> —— 实测 Prometheus 报
     * {@code failure=0}，而同一时间窗口 ClickHouse 里确实有 3 笔失败。
     * 现在规则是：<b>终态在哪里确定，计数器就加在哪里</b>，与 transaction_outcome 写入点完全一致，
     * 保证 Prometheus 与 ClickHouse 使用同一个「失败」定义。
     */
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;

    /** 单笔转账限额，默认 10000。改限额只需改配置，不再需要重新编译。 */
    @Value("${banking.transfer.max-amount:10000}")
    private BigDecimal maxTransferAmount;

    public Account createAccount(Account account) {
        log.info("Creating new account for owner: {}", account.getOwnerName());
        return accountRepository.save(account);
    }

    /**
     * 开户（带归属）：普通用户只能给自己开户；ADMIN 可以指定 ownerName 为他人开户。
     * 账户的 user_id 是后续所有账户级接口的授权依据，因此必须在这里落库。
     */
    @Transactional
    public Account createAccountFor(AccountCreateDTO request, Authentication authentication) {
        String username = authentication.getName();
        boolean admin = AccountAccessService.isAdmin(authentication);

        String ownerName = admin && StringUtils.hasText(request.getOwnerName())
                ? request.getOwnerName()
                : username;

        Long userId = userRepository.findByUsername(ownerName)
                .map(User::getId)
                .orElseGet(() -> userRepository.findByUsername(username)
                        .map(User::getId)
                        .orElseThrow(() -> new BusinessException(
                                "Authenticated user not found: " + username, HttpStatus.UNAUTHORIZED)));

        Account account = Account.builder()
                .accountNumber(request.getAccountNumber())
                .ownerName(ownerName)
                .userId(userId)
                .build();

        log.info("Creating new account {} for owner={} (userId={})", request.getAccountNumber(), ownerName, userId);
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
     *
     * <p>[事务边界] 这里刻意不加 @Transactional：
     * 状态机（markAsProcessing / markAsCompleted / markAsFailed）各自是 REQUIRES_NEW 独立事务，
     * 转账本身由 {@link TransferInternalService#executeTransfer} 自己开事务。
     * 早期版本在方法上加了 @Transactional，导致 executeTransfer 抛出的 BusinessException
     * 把整个事务标记为 rollback-only，占坑记录与 FAILED 标记被一起回滚 ——
     * 结果是业务失败的转账在库里毫无痕迹，客户端也永远查不到失败原因。
     */
    // containerFactory 显式写出：它就是 Spring 的默认 bean 名，写出来行为完全不变，
    // 但错误处理器（死信主题 + x-origin-consumer 来源标识）是挂在容器工厂上的，
    // 漏写会静默回退到默认工厂。ArchitectureTest 要求每个 @KafkaListener 都必须显式声明。
    @KafkaListener(topics = KafkaTopics.TRANSFER, groupId = "banking-group",
            containerFactory = "kafkaListenerContainerFactory")
    public void handleTransferEvent(@Header(KafkaHeaders.RECEIVED_KEY) String transactionId, TransferRequest request) {
        // 面试点：手动将业务 ID 放入 MDC，确保该线程后续所有日志都带上这个业务单号
        org.slf4j.MDC.put("bizId", transactionId);
        
        // 分布式追踪：Kafka 消息头注入 traceId，确保异步任务传播链路完整
        log.info("Trace propagation active for transaction {} (Kafka consumer)", transactionId);
        
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
            // 面试点：银行核心系统严禁在业务逻辑中直接使用浮点数，validateRisk 内部会严格校验精度
            validateRisk(request.getFromAccountNo(), request.getToAccountNo(), request.getAmount());

            transferInternalService.executeTransfer(
                transactionId,
                request.getFromAccountNo(), 
                request.getToAccountNo(), 
                request.getAmount());
            
            processedTransactionService.markAsCompleted(transactionId);
            transferOutcomeRecorder.record(transactionId, request.getRequestId(),
                    request.getFromAccountNo(), request.getToAccountNo(), request.getAmount(),
                    "COMPLETED", null);
            meterRegistry.counter("transfer.success.total").increment();
        } catch (BusinessException e) {
            // 面试点：业务异常（如余额不足）不应触发 Kafka 重试
            log.error("Business failure for transaction {}: {}", transactionId, e.getMessage());
            processedTransactionService.markAsFailed(transactionId, e.getMessage());
            transferOutcomeRecorder.record(transactionId, request.getRequestId(),
                    request.getFromAccountNo(), request.getToAccountNo(), request.getAmount(),
                    "FAILED", e.getMessage());
            // 业务失败 = 终态失败，必须计数（包括 validateRisk 抛出的自转账/超限/精度错误）
            meterRegistry.counter("transfer.failure.total").increment();
        } catch (Exception e) {
            // 系统异常（如数据库断开）：直接抛出，触发 KafkaConsumerConfig 里定义的 3 次重试。
            // 此时不要 markAsFailed，因为重试可能成功。
            log.error("System error for transaction {}, scheduling retry...", transactionId);
            throw new RuntimeException("System error: " + transactionId, e);
        } finally {
            org.slf4j.MDC.remove("bizId");
        }
    }

    /**
     * 业务规则校验（在消费者侧执行，失败落成 FAILED + 原因，可通过 requestId 查到）。
     *
     * <p>限额、精度、自转账这三条都应当由配置/常量驱动，而不是散落的字面量。
     */
    private void validateRisk(String fromAccountNo, String toAccountNo, BigDecimal amount) {
        // 1. 禁止自转账：同一账户一进一出，借贷两条会撞唯一约束，且没有任何业务意义
        if (fromAccountNo.equals(toAccountNo)) {
            throw new BusinessException("Self transfer is not allowed", HttpStatus.BAD_REQUEST);
        }
        // 2. 金额精度：库里是 DECIMAL(38,2)，超过两位小数会被静默四舍五入
        if (amount.scale() > 2) {
            throw new BusinessException("Amount must have at most 2 decimal places", HttpStatus.BAD_REQUEST);
        }
        // 3. 单笔限额：从配置读，改限额不需要重新编译
        if (amount.compareTo(maxTransferAmount) > 0) {
            log.warn("Risk Alert: Transfer amount {} exceeds single transaction limit {}!", amount, maxTransferAmount);
            throw new BusinessException("Single transfer limit exceeded (Max " + maxTransferAmount + ")",
                    HttpStatus.FORBIDDEN);
        }
    }
}