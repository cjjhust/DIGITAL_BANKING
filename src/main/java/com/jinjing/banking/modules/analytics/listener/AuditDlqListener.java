package com.jinjing.banking.modules.analytics.listener;

import com.jinjing.banking.config.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * 审计链路的死信监听器。
 *
 * <p>存在的意义：审计（写 ClickHouse）失败与转账是否成功<b>没有关系</b>，所以它走独立的死信主题
 * {@link KafkaTopics#AUDIT_DLT}，由这里单独处理——可以重放或直接丢弃，处理优先级也远低于账本死信。
 *
 * <p><b>绝不触碰 {@code processed_transactions}</b>：审计侧没有资格改写账本状态。
 */
@Component
@Slf4j
public class AuditDlqListener {

    @KafkaListener(topics = KafkaTopics.AUDIT_DLT, groupId = "banking-audit-dlt-group",
            containerFactory = "dltListenerContainerFactory")
    public void listenAuditDlq(@Header(KafkaHeaders.RECEIVED_KEY) String transactionId,
                               @Header(name = KafkaTopics.ORIGIN_HEADER, required = false) String origin,
                               @Payload String failedMessage) {
        log.error("AUDIT DLT: 审计数据写入失败（不影响账本）。tx={}, origin={}, payload={}",
                transactionId, origin, failedMessage);
        // 审计死信的处理策略（按业务选择）：
        //   1) 修复 ClickHouse 后人工重放；
        //   2) 或者直接丢弃——审计数据不参与资金对账，缺失可由主库回补。
        // 注意：这里不调用 ProcessedTransactionService，账本状态一律不动。
    }
}
