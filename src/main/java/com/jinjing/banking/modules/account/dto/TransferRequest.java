package com.jinjing.banking.modules.account.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class TransferRequest {
    @NotBlank(message = "requestId is required")
    private String requestId; // 对应前端生成的 UUID
    
    @NotBlank(message = "Source account is required")
    private String fromAccountNo;

    @NotBlank(message = "Target account is required")
    private String toAccountNo;

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be positive")
    // 与库里的 DECIMAL(38,2) 对齐。不校验小数位的话，0.001 会被 Postgres 静默四舍五入成 0.00，
    // 出现“扣款额 ≠ 请求额”这类对不上账的差额。
    @Digits(integer = 36, fraction = 2, message = "Amount must have at most 2 decimal places")
    private BigDecimal amount;
}