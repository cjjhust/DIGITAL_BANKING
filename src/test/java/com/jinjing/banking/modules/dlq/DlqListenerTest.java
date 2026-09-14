package com.jinjing.banking.modules.dlq;

import com.jinjing.banking.config.KafkaTopics;
import com.jinjing.banking.modules.analytics.service.TransferOutcomeRecorder;
import com.jinjing.banking.modules.transaction.entity.ProcessedTransaction;
import com.jinjing.banking.modules.transaction.service.ProcessedTransactionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账本死信兜底的两条不变量：
 *   1. 只有来源是账本消费者（banking-group）的死信才允许改状态；
 *   2. 只有 PENDING 才能兜底成 FAILED，终态一律不改写。
 */
@ExtendWith(MockitoExtension.class)
class DlqListenerTest {

    @Mock
    private ProcessedTransactionService processedTransactionService;

    /** DLQ 兜底同样要写 ClickHouse 终态表；单测里 mock 掉。 */
    @Mock
    private TransferOutcomeRecorder transferOutcomeRecorder;

    @InjectMocks
    private DlqListener dlqListener;

    private static ProcessedTransaction tx(ProcessedTransaction.Status status) {
        return ProcessedTransaction.builder()
                .transactionId("tx-1")
                .clientRequestId("req-1")
                .status(status)
                .build();
    }

    @Test
    void ledgerOriginMarksPendingAsFailed() {
        when(processedTransactionService.getTransactionById("tx-1"))
                .thenReturn(Optional.of(tx(ProcessedTransaction.Status.PENDING)));

        dlqListener.listenDlq("tx-1", KafkaTopics.ORIGIN_LEDGER, "{bad json}");

        verify(processedTransactionService).markAsFailed(anyString(), anyString());
    }

    @Test
    void ledgerOriginDoesNotOverwriteCompleted() {
        when(processedTransactionService.getTransactionById("tx-1"))
                .thenReturn(Optional.of(tx(ProcessedTransaction.Status.COMPLETED)));

        dlqListener.listenDlq("tx-1", KafkaTopics.ORIGIN_LEDGER, "{bad json}");

        verify(processedTransactionService, never()).markAsFailed(anyString(), anyString());
    }

    @Test
    void analyticsOriginMustNotTouchLedgerState() {
        // 审计失败不代表转账失败：即使账本是 PENDING，也不能由审计侧来判死
        dlqListener.listenDlq("tx-1", KafkaTopics.ORIGIN_ANALYTICS, "{bad json}");

        verify(processedTransactionService, never()).getTransactionById(anyString());
        verify(processedTransactionService, never()).markAsFailed(anyString(), anyString());
    }

    @Test
    void missingOriginIsTreatedAsUnknownAndIgnored() {
        // 老消息没有 x-origin-consumer 头 → 无法证明账本失败 → 不改状态（留给人工对账）
        dlqListener.listenDlq("tx-1", null, "{bad json}");

        verify(processedTransactionService, never()).markAsFailed(anyString(), anyString());
    }

    @Test
    void unknownTransactionDoesNotThrow() {
        when(processedTransactionService.getTransactionById("tx-1")).thenReturn(Optional.empty());

        dlqListener.listenDlq("tx-1", KafkaTopics.ORIGIN_LEDGER, "{bad json}");

        verify(processedTransactionService, never()).markAsFailed(anyString(), anyString());
    }
}
