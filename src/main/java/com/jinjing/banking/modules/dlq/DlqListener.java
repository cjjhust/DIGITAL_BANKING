package com.jinjing.banking.modules.dlq;

//import com.jinjing.banking.modules.account.dto.TransferRequest;
import com.jinjing.banking.modules.transaction.service.ProcessedTransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class DlqListener {

    private final ProcessedTransactionService processedTransactionService;

    @KafkaListener(topics = "banking-transfers.DLT", groupId = "banking-dlt-group")
    public void listenDlq(@Header(KafkaHeaders.RECEIVED_KEY) String transactionId, Object failedMessage) {
        // 面试点：DLQ 监听器不能假设消息格式正确
        // 既然都进死信队列了，很有可能是因为 JSON 格式就不对，无法转成 TransferRequest 对象
        
        log.error("CRITICAL: Message moved to DLQ. TransactionID: {}, Raw Message Type: {}", 
                  transactionId, failedMessage.getClass().getSimpleName());
        log.error("Payload content: {}", failedMessage);

        // 可以根据业务需求进行处理：
        // 1. 最终兜底：将数据库状态标记为 FAILED，因为重试已经全部失败
        processedTransactionService.markAsFailed(transactionId, "Exhausted retries in Kafka, moved to DLQ");

        // 2. 发送告警通知（邮件、短信）
        // 3. 人工干预流程
        processedTransactionService.getTransactionById(transactionId)
                .ifPresentOrElse(pt -> log.info("DLQ: Transaction {} status: {}", transactionId, pt.getStatus()),
                        () -> log.warn("DLQ: Transaction {} not found in processed_transactions.", transactionId));
    }
}