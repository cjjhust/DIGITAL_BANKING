package com.jinjing.banking.modules.account.entity;

/**
 * 系统角色枚举
 * USER: 普通用户（可以查看自己的账户、做转账等受限操作）
 * ADMIN: 管理员（可以看所有账户、冻结账户等管理操作）
 */
public enum Role {
    USER("ROLE_USER", "普通用户"),
    ADMIN("ROLE_ADMIN", "管理员");

    private final String code;
    private final String description;

    Role(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}
