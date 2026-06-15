package com.jinjing.banking.modules.account.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.jinjing.banking.modules.account.dto.AccountCreateDTO; // 引入新 DTO
import com.jinjing.banking.modules.account.dto.TransferRequest; // 确保导入正确的 DTO 包
import com.jinjing.banking.modules.account.entity.Account;
import com.jinjing.banking.modules.account.service.AccountService;
import com.jinjing.banking.modules.transaction.entity.OutboxEvent;
import com.jinjing.banking.modules.transaction.repository.OutboxEventRepository;
import com.jinjing.banking.modules.transaction.repository.ProcessedTransactionRepository;
import com.jinjing.banking.modules.transaction.entity.ProcessedTransaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Transactional;
import java.util.concurrent.atomic.AtomicLong;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.Span;
import java.time.Duration;

@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;
    private final OutboxEventRepository outboxEventRepository;
    private final ProcessedTransactionRepository processedTransactionRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Tracer tracer; // 注入 Micrometer Tracer
    private final MeterRegistry meterRegistry; // 注入监控注册表

    // 建议从配置读取，防止分布式部署时 workerId 重复导致 ID 冲突
    @Value("${banking.snowflake.worker-id:1}") 
    private long workerId;

    private static final AtomicLong sequence = new AtomicLong(0);
    private static final long CUSTOM_EPOCH = 1704067200000L; // 2024-01-01

    @PostMapping
    public ResponseEntity<Account> createAccount(@Valid @RequestBody AccountCreateDTO request) {
        // 修复：Account 构造函数是 PROTECTED。必须使用 @Builder 实例化。
        // 面试点：Builder 模式在处理多字段对象时比 new 更安全，且能保证对象创建的原子性。
        Account account = Account.builder()
                .ownerName(request.getOwnerName())
                .accountNumber(request.getAccountNumber())
                .build();
        // 初始余额通常在业务层控制或数据库设置默认值
        
        return ResponseEntity.ok(accountService.createAccount(account));
    }

    @GetMapping("/{accountNumber}")
    public ResponseEntity<Account> getAccount(@PathVariable String accountNumber) {
        return accountService.getAccount(accountNumber)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{accountNumber}/transactions")
    public ResponseEntity<Page<ProcessedTransaction>> getAccountTransactions(@PathVariable String accountNumber, Pageable pageable) {
        Page<ProcessedTransaction> transactions = accountService.getTransactionHistory(accountNumber, pageable);
        return ResponseEntity.ok(transactions);
    }

    @PostMapping("/transfer")
    @Transactional // 硬核点：开启事务，保证业务 ID 生成和 Outbox 记录在同一个本地事务中
    @SneakyThrows
    public ResponseEntity<String> transfer(@Valid @RequestBody TransferRequest request) {
        // [硬核方案]：使用客户端生成的 requestId 实现端到端幂等，防止网络重试导致双花
        String clientRequestId = request.getRequestId();

        // 0. [Redis 性能优化层]：先过 Redis，挡住 99% 的高并发重复请求，保护数据库
        // 这里的锁只是为了防止“同一秒内的重复点击”，不应该设置 30 分钟这么长
        String redisKey = "idempotency:transfer:" + clientRequestId;
        Boolean isNew = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, "LOCKED", Duration.ofMinutes(1)); // 缩短为 1 分钟
        
        if (Boolean.FALSE.equals(isNew)) {
            // 只要进了这个判断，就说明触发了幂等拦截
            // 我们加上 "action" 标签，方便以后在仪表盘区分是转账还是开户被拦截了
            meterRegistry.counter("banking.requests.intercepted.total", 
                                "action", "transfer", 
                                "worker_id", String.valueOf(workerId)).increment();
            return ResponseEntity.accepted().body("Request processed or being processed (Cached)");
        }

        try {
            // 1. 检查 Outbox 表：利用客户端 RequestId 查重
            if (outboxEventRepository.existsByClientRequestId(clientRequestId)) {
                return ResponseEntity.accepted().body("Request already being processed. ClientID: " + clientRequestId);
            }

            // 2. 检查已处理事务表：防止已经彻底完成的单子被重复提交
            if (processedTransactionRepository.existsByClientRequestId(clientRequestId)) {
                return ResponseEntity.accepted().body("Transfer already completed successfully. ID: " + clientRequestId);
            }

            // 改进：使用毫秒级精度 (41位) + workerId (10位) + 序列号 (12位)
            // 这样单机每毫秒支持 4096 个交易，秒级支持 400万+，真正达到银行级吞吐
            long timestamp = System.currentTimeMillis() - CUSTOM_EPOCH;
            long snowflakeId = (timestamp << 22) 
                             | (workerId << 12) 
                             | (sequence.getAndIncrement() % 4096);
            
            String transactionId = String.valueOf(snowflakeId);

            // [硬核技巧]：Baggage 绑定
            try (BaggageInScope baggage = tracer.createBaggageInScope("transactionId", transactionId)) {
                Span currentSpan = tracer.currentSpan();
                String traceId = null;
                String spanId = null;
                if (currentSpan != null && currentSpan.context() != null) {
                    traceId = currentSpan.context().traceId();
                    spanId = currentSpan.context().spanId();
                }
            
                // [最终一致性]：存储到 Outbox
                OutboxEvent event = OutboxEvent.builder()
                        .aggregateId(transactionId)
                        .clientRequestId(clientRequestId)
                        .traceId(traceId) // 修复：必须存入 Trace 上下文，否则异步链路会断
                        .spanId(spanId)
                        .topic("banking-transfers")
                        .payload(objectMapper.writeValueAsString(request))
                        .build();
            
                outboxEventRepository.save(event);
            }

            return ResponseEntity.accepted()
                    .body("Transfer request accepted. ID: " + transactionId);
        } catch (Exception e) {
            // [核心修复点]：如果业务逻辑报错或者数据库写失败，说明“没开始成功”
            // 此时必须删除 Redis 锁，允许用户立即重试
            redisTemplate.delete(redisKey);
            throw e;
        }
    }

    @GetMapping("/health-check")
    public String health() {
        return "Banking Account Service is running";
    }
}
