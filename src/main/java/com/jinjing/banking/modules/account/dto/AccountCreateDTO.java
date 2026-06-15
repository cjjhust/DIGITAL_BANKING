package com.jinjing.banking.modules.account.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 用于创建账户的 DTO
 * 生产环境标配：防止用户恶意通过 API 修改余额或 ID
 */
@Data
public class AccountCreateDTO {
    
    @NotBlank(message = "Owner name is required")
    private String ownerName;
    
    @NotBlank(message = "Account number is required")
    private String accountNumber;
}