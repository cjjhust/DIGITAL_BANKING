package com.jinjing.banking.modules.dlq;

import com.jinjing.banking.config.KafkaTopics;
import com.jinjing.banking.modules.analytics.service.TransferOutcomeRecorder;
import com.jinjing.banking.modules.transaction.entity.ProcessedTransaction;
import com.jinjing.banking.modules.transaction.service.ProcessedTransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * 账本链路死信监听器。
 *
 * <p>两条必须遵守的规则：
 * <ol>
 *     <li><b>只处理来自账本消费者（{@code banking-group}）的死信</b>：同一个 topic 可能被多个消费者组
 *         订阅（例如 ClickHouse 审计），任一组的失败都会产生死信，但只有账本失败才代表"转账可能没成功"。
 *         来源由 header {@code x-origin-consumer} 标识，缺失或不匹配时只告警、不改状态。</li>
 *     <li><b>不得改写终态</b>：只有 PENDING 才能兜底成 FAILED；已经是 COMPLETED/FAILED 的一律不动。</li>
 * </ol>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DlqListener {

    private final ProcessedTransactionService processedTransactionService;
    private final TransferOutcomeRecorder transferOutcomeRecorder;

    /**
     * 使用 {@code dltListenerContainerFactory}：该工厂不挂 JSON 转换器。
     * 进死信的消息往往连 JSON 都解析不了，再用 Jackson 转换会二次失败并困在重试循环里。
     */
    @KafkaListener(topics = KafkaTopics.TRANSFER_DLT, groupId = "banking-dlt-group",
            containerFactory = "dltListenerContainerFactory")
    public void listenDlq(@Header(KafkaHeaders.RECEIVED_KEY) String transactionId,
                          @Header(name = KafkaTopics.ORIGIN_HEADER, required = false) String origin,
                          @Payload String failedMessage) {

        log.error("CRITICAL: Message moved to DLQ. TransactionID: {}, origin: {}, Payload: {}",
                transactionId, origin, failedMessage);

        // 规则 1：来源不是账本消费者（或老消息没有来源标识）→ 无法证明转账失败，不改账本状态
        if (!KafkaTopics.ORIGIN_LEDGER.equals(origin)) {
            log.warn("DLQ: origin={} 不是账本消费者，无法据此判定转账失败，账本状态保持不变。tx={}",
                    origin, transactionId);
            return;
        }

        // 规则 2：只允许把 PENDING 兜底成 FAILED
        processedTransactionService.getTransactionById(transactionId).ifPresentOrElse(
                pt -> {
                    if (pt.getStatus() == ProcessedTransaction.Status.PENDING) {
                        log.error("DLQ: marking transaction {} as FAILED after exhausted retries.", transactionId);
                        processedTransactionService.markAsFailed(transactionId,
                                "Exhausted retries in Kafka, moved to DLQ");
                        // 终态同样是「结算结果」，必须进 outcome 表 —— 否则这条路造成的失败
                        // 只会出现在 transaction_audit 里，被当成成功笔数统计进去。
                        transferOutcomeRecorder.record(transactionId, pt.getClientRequestId(),
                                pt.getFromAccountNo(), pt.getToAccountNo(), pt.getAmount(),
                                "DLQ", "Exhausted retries in Kafka, moved to DLQ");
                    } else {
                        log.warn("DLQ: transaction {} 已是终态 {}，不改写（幂等）。",
                                transactionId, pt.getStatus());
                    }
                },
                () -> log.warn("DLQ: Transaction {} not found in processed_transactions.", transactionId));

        // 人工干预流程由运维按上述 ERROR 日志触发（可再接邮件/短信告警）
    }
}