package com.jinjing.banking.modules.transaction.service;

import com.jinjing.banking.modules.transaction.entity.ProcessedTransaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时 PENDING 交易扫描。
 *
 * <p><b>策略：只认领 + 告警，不自动补账。</b>
 * 自动重试扣款在“是否已经扣过”未被证明的情况下就是重复扣钱，风险远大于收益；
 * 所以这里只把记录认领下来并升级为人工对账，把决定权交给业务。
 *
 * <p><b>为什么需要租约：</b>多实例部署时，每个实例的定时任务都会扫到同一批超时记录。
 * 没有租约就会出现 N 个实例对同一条记录重复告警、重复处理；
 * 有了租约，{@code claimPending} 的单条 UPDATE 保证只有一个实例能拿到。
 */
@Component
@Slf4j
public class PendingRecoveryService {

    private final ProcessedTransactionService processedTransactionService;

    /** 租约持有者标识：默认用 JVM 名（pid@host），多实例天然不同 */
    private final String instanceId;

    @Value("${banking.recovery.pending-threshold-minutes:5}")
    private long thresholdMinutes;

    @Value("${banking.recovery.lease-ttl-seconds:300}")
    private long leaseTtlSeconds;

    public PendingRecoveryService(ProcessedTransactionService processedTransactionService,
                                  @Value("${banking.instance-id:}") String configuredInstanceId) {
        this.processedTransactionService = processedTransactionService;
        this.instanceId = (configuredInstanceId == null || configuredInstanceId.isBlank())
                ? ManagementFactory.getRuntimeMXBean().getName()
                : configuredInstanceId;
    }

    @Scheduled(fixedRate = 300000) // 每 5 分钟执行一次
    public void recoverPendingTransactions() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(thresholdMinutes);
        List<ProcessedTransaction> candidates = processedTransactionService.getPendingTransactionsOlderThan(threshold);

        if (candidates.isEmpty()) {
            log.info("PENDING recovery scan [{}]: no stale PENDING transactions.", instanceId);
            return;
        }

        int claimed = 0;
        for (ProcessedTransaction pt : candidates) {
            // 单条 UPDATE 完成「检查 + 占用」：拿不到就说明别的实例已经在处理
            if (processedTransactionService.claimStalePending(pt.getTransactionId(), instanceId, leaseTtlSeconds)) {
                claimed++;
                log.error("Stale PENDING claimed by [{}] — manual reconciliation required. tx={}, createdAt={}",
                        instanceId, pt.getTransactionId(), pt.getCreatedAt());
            }
        }

        if (claimed == 0) {
            log.info("PENDING recovery scan [{}]: {} stale candidate(s), all already claimed by other instances.",
                    instanceId, candidates.size());
        } else {
            log.error("PENDING recovery scan [{}]: {} of {} stale transaction(s) claimed; "
                            + "no automatic compensation is performed by design.",
                    instanceId, claimed, candidates.size());
        }
    }
}
