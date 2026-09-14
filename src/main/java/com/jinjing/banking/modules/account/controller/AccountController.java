package com.jinjing.banking.modules.account.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.jinjing.banking.common.exception.BusinessException;
import com.jinjing.banking.common.util.SnowflakeIdGenerator;
import com.jinjing.banking.modules.account.dto.AccountCreateDTO; // 引入新 DTO
import com.jinjing.banking.modules.account.dto.TransferRequest; // 确保导入正确的 DTO 包
import com.jinjing.banking.modules.account.dto.TransferStatusResponse;
import com.jinjing.banking.modules.account.entity.Account;
import com.jinjing.banking.modules.account.service.AccountAccessService;
import com.jinjing.banking.modules.account.service.AccountService;
import com.jinjing.banking.modules.transaction.entity.OutboxEvent;
import com.jinjing.banking.modules.transaction.entity.ProcessedTransaction;
import com.jinjing.banking.modules.transaction.service.OutboxService;
import com.jinjing.banking.modules.transaction.service.ProcessedTransactionService;
import tools.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.Span;
import java.time.Duration;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Account", description = "Account management APIs")
@Slf4j
@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;
    private final AccountAccessService accountAccessService;
    private final OutboxService outboxService;
    private final ProcessedTransactionService processedTransactionService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Tracer tracer; // 注入 Micrometer Tracer
    private final MeterRegistry meterRegistry; // 注入监控注册表
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    // 仅用于指标标签：多实例部署时区分是哪个 worker 拦截了幂等请求。
    // ID 生成已由 SnowflakeIdGenerator 负责（含 workerId 范围校验）。
    @Value("${banking.snowflake.worker-id:0}")
    private long workerId;

    /**
     * 幂等窗口：Redis 键的存活时间。
     *
     * <p>窗口必须远小于「客户端重试间隔」以外的所有业务超时，因为窗口内若数据库没有记录，
     * 接口会返回 409 让客户端稍后重试；窗口越长，这段不确定期就越长。
     * 默认 30 秒足够挡住同一次点击的重复提交，又不至于让中断的请求长时间无法重试。
     */
    @Value("${banking.transfer.idempotency-window-seconds:30}")
    private long idempotencyWindowSeconds;

    @Operation(summary = "Create account", description = "Register a new bank account owned by the authenticated user")
    @PostMapping
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public ResponseEntity<Account> createAccount(@Valid @RequestBody AccountCreateDTO request,
                                                 Authentication authentication) {
        // 归属在 service 层落库：普通用户只能给自己开户，ADMIN 可指定 ownerName。
        // 不能在这里直接依赖 AccountRepository，否则会破坏 ArchUnit 的分层约束。
        return ResponseEntity.ok(accountService.createAccountFor(request, authentication));
    }

    @Operation(summary = "Get account", description = "Only the owner or an ADMIN may read an account")
    @GetMapping("/{accountNumber}")
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public ResponseEntity<Account> getAccount(@PathVariable String accountNumber,
                                              Authentication authentication) {
        // 归属校验：越权访问返回 403，账户不存在返回 404
        Account account = accountAccessService.requireAccess(accountNumber, authentication);
        return ResponseEntity.ok(account);
    }

    @Operation(summary = "Get account transactions", description = "Only the owner or an ADMIN may read the history")
    @GetMapping("/{accountNumber}/transactions")
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public ResponseEntity<Page<ProcessedTransaction>> getAccountTransactions(@PathVariable String accountNumber,
                                                                            Pageable pageable,
                                                                            Authentication authentication) {
        accountAccessService.requireAccess(accountNumber, authentication);
        Page<ProcessedTransaction> transactions = accountService.getTransactionHistory(accountNumber, pageable);
        return ResponseEntity.ok(transactions);
    }

    @PostMapping("/transfer")
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    @Transactional // 硬核点：开启事务，保证业务 ID 生成和 Outbox 记录在同一个本地事务中
    @SneakyThrows
    public ResponseEntity<String> transfer(@Valid @RequestBody TransferRequest request,
                                           Authentication authentication) {
        // [归属校验]：付款账户必须属于当前用户（ADMIN 例外）。
        // 必须放在写 Redis 幂等键之前：越权请求不应占用 requestId，也不应留下任何副作用。
        accountAccessService.requireAccess(request.getFromAccountNo(), authentication);

        // [硬核方案]：使用客户端生成的 requestId 实现端到端幂等，防止网络重试导致双花
        String clientRequestId = request.getRequestId();

        // 0. [Redis 性能优化层]：先过 Redis，挡住 99% 的高并发重复请求，保护数据库
        // 这里的锁只是为了防止“同一秒内的重复点击”，不应该设置 30 分钟这么长。
        // 窗口可配（banking.transfer.idempotency-window-seconds），因为窗口越短，
        // 「Redis 残留但库里没记录」这个不确定区间就越短。
        String redisKey = "idempotency:transfer:" + clientRequestId;
        Boolean isNew = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, "LOCKED", Duration.ofSeconds(idempotencyWindowSeconds));
        
        if (Boolean.FALSE.equals(isNew)) {
            meterRegistry.counter("banking.redis.idempotency.hit.total").increment();
            // 只要进了这个判断，就说明触发了幂等拦截
            // 我们加上 "action" 标签，方便以后在仪表盘区分是转账还是开户被拦截了
            meterRegistry.counter("banking.requests.intercepted.total", 
                                "action", "transfer", 
                                "worker_id", String.valueOf(workerId)).increment();

            // [正确性修复] 不能无条件返回 202。
            //
            // 202 Accepted 的语义是「已持久化、会被处理」，而 Redis 命中只代表
            // 「idempotency-window 秒内见过这个 requestId」——两者不是一回事：
            // Redis 不在 transfer() 的数据库事务里，若进程在 SET NX 成功之后、
            // Outbox 落库之前崩溃，Redis 键会残留但库里**一行都没有**。
            // 此时若返回 202，客户端会认为已受理并停止重试，而这笔转账从未被记录——静默丢单。
            //
            // 因此 202 只由「数据库里确实有受理痕迹」产生（Outbox 行或终态记录），
            // 否则返回 409 + Retry-After，明确告诉客户端「在途或上次中断了，请用同一 requestId 重试」。
            if (outboxService.existsByClientRequestId(clientRequestId)
                    || processedTransactionService.existsByClientRequestId(clientRequestId)) {
                return ResponseEntity.accepted()
                        .body("Request already accepted (idempotent replay). ID: " + clientRequestId);
            }

            log.warn("Idempotency key present in Redis but no persisted record for requestId={} "
                    + "(concurrent accept in flight, or a previous attempt was interrupted)", clientRequestId);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .header(HttpHeaders.RETRY_AFTER, "5")
                    .body("Request is being processed concurrently, or a previous attempt was interrupted. "
                            + "Retry with the same requestId: " + clientRequestId);
        }

        meterRegistry.counter("banking.redis.idempotency.miss.total").increment();

        try {
            // 1. 检查 Outbox 表：利用客户端 RequestId 查重
            if (outboxService.existsByClientRequestId(clientRequestId)) {
                return ResponseEntity.accepted().body("Request already being processed. ClientID: " + clientRequestId);
            }

            // 2. 检查已处理事务表：防止已经彻底完成的单子被重复提交
            if (processedTransactionService.existsByClientRequestId(clientRequestId)) {
                return ResponseEntity.accepted().body("Transfer already completed successfully. ID: " + clientRequestId);
            }

            // ID 生成已抽到 SnowflakeIdGenerator。
            // 旧写法 sequence.getAndIncrement() % 4096 在同一毫秒内超过 4096 个请求时会回绕到 0，
            // 于是同一毫秒可能产生完全相同的 ID —— 两笔不同的转账会被当成同一笔。
            // 现在序列号用尽时等待下一毫秒，绝不回绕。
            String transactionId = snowflakeIdGenerator.nextIdAsString();

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

                outboxService.save(event);
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

    @Operation(summary = "Query transfer status",
            description = "Resolve the final state of an accepted transfer by client requestId (QUEUED / PENDING / COMPLETED / FAILED)")
    @GetMapping("/transfer/{requestId}")
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public ResponseEntity<TransferStatusResponse> getTransferStatus(@PathVariable String requestId,
                                                                    Authentication authentication) {
        // 1. 已经被消费者处理过：直接返回账本侧状态（含失败原因）
        Optional<ProcessedTransaction> processed = processedTransactionService.getByClientRequestId(requestId);
        if (processed.isPresent()) {
            ProcessedTransaction transaction = processed.get();
            accountAccessService.requireAccess(transaction.getFromAccountNo(), authentication);
            return ResponseEntity.ok(TransferStatusResponse.builder()
                    .requestId(requestId)
                    .transactionId(transaction.getTransactionId())
                    .status(transaction.getStatus().name())
                    .fromAccountNo(transaction.getFromAccountNo())
                    .toAccountNo(transaction.getToAccountNo())
                    .amount(transaction.getAmount())
                    .errorMessage(transaction.getErrorMessage())
                    .createdAt(transaction.getCreatedAt())
                    .updatedAt(transaction.getUpdatedAt())
                    .build());
        }

        // 2. 还在 Outbox 里：已受理、尚未被消费者占坑
        Optional<OutboxEvent> queued = outboxService.findByClientRequestId(requestId);
        if (queued.isPresent()) {
            OutboxEvent event = queued.get();
            TransferRequest queuedRequest = parseQueuedRequest(event);
            accountAccessService.requireAccess(queuedRequest.getFromAccountNo(), authentication);
            return ResponseEntity.ok(TransferStatusResponse.builder()
                    .requestId(requestId)
                    .transactionId(event.getAggregateId())
                    .status("QUEUED")
                    .fromAccountNo(queuedRequest.getFromAccountNo())
                    .toAccountNo(queuedRequest.getToAccountNo())
                    .amount(queuedRequest.getAmount())
                    .createdAt(event.getCreatedAt())
                    .build());
        }

        // 3. 受理空窗期：Outbox 行已被中继删除、消费者尚未写入 processed_transactions，
        //    或上次受理在落库前中断（此时只有 Redis 键残留）。
        //    两种情况都拿不到账户信息，因此不做归属校验（仅返回状态，不泄露账户数据）。
        //    注意语义：这是「无法确定」，不是「已受理」——所以不能返回 202。
        String idempotencyKey = "idempotency:transfer:" + requestId;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(idempotencyKey))) {
            return ResponseEntity.ok(TransferStatusResponse.builder()
                    .requestId(requestId)
                    .status("PROCESSING")
                    .build());
        }

        throw new BusinessException("Transfer request not found: " + requestId, HttpStatus.NOT_FOUND);
    }

    /**
     * Outbox 里存的是受理时的 TransferRequest JSON，解析出来才能做归属校验。
     */
    private TransferRequest parseQueuedRequest(OutboxEvent event) {
        try {
            return objectMapper.readValue(event.getPayload(), TransferRequest.class);
        } catch (Exception e) {
            log.error("Unable to parse outbox payload for aggregateId={}", event.getAggregateId(), e);
            throw new BusinessException("Unable to resolve transfer request payload", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/health-check")
    public String health() {
        return "Banking Account Service is running";
    }
}
