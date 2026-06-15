package com.jinjing.banking.modules.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class TransferRequest {
    private String requestId; // 对应前端生成的 UUID
    
    @NotBlank(message = "Source account is required")
    private String fromAccountNo;

    @NotBlank(message = "Target account is required")
    private String toAccountNo;

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be positive")
    private BigDecimal amount;
}