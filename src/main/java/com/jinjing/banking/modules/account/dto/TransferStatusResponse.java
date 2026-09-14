package com.jinjing.banking.modules.account.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 转账受理结果查询响应。
 *
 * <p>status 取值：
 * <ul>
 *     <li>QUEUED —— 已受理并写入 Outbox，尚未被消费者占坑；</li>
 *     <li>PENDING —— 消费者已占坑，正在扣款；</li>
 *     <li>COMPLETED —— 账本已更新；</li>
 *     <li>FAILED —— 业务失败（如余额不足、超限）或重试耗尽，原因见 errorMessage。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferStatusResponse {

    private String requestId;

    private String transactionId;

    private String status;

    private String fromAccountNo;

    private String toAccountNo;

    private BigDecimal amount;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
