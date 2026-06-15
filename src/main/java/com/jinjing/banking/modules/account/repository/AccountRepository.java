package com.jinjing.banking.modules.account.repository;

import com.jinjing.banking.modules.account.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {
    
    /**
     * 通过账号查询
     */
    Optional<Account> findByAccountNumber(String accountNumber);

    /**
     * 检查账号是否存在
     */
    boolean existsByAccountNumber(String accountNumber);
}