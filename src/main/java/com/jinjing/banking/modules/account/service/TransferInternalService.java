package com.jinjing.banking.modules.account.service;

import com.jinjing.banking.common.exception.BusinessException;
import com.jinjing.banking.modules.account.entity.Account;
import com.jinjing.banking.modules.account.repository.AccountRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 内部转账服务：专门处理受事务保护的数据库操作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferInternalService {

    private final AccountRepository accountRepository;

    /**
     * 核心转账逻辑：执行具体的扣款和入账。
     * 该方法必须在事务中运行。
     */
    @Transactional
    public void executeTransfer(String fromAccountNo, String toAccountNo, BigDecimal amount) {
        log.info("Executing atomic transfer: {} -> {} | Amount: {}", fromAccountNo, toAccountNo, amount);

        // 1. 获取并校验账户
        Account fromAccount = accountRepository.findByAccountNumber(fromAccountNo)
                .orElseThrow(() -> new BusinessException("Source account not found", HttpStatus.NOT_FOUND));
        
        Account toAccount = accountRepository.findByAccountNumber(toAccountNo)
                .orElseThrow(() -> new BusinessException("Target account not found", HttpStatus.NOT_FOUND));

        // 2. 余额检查
        if (fromAccount.getBalance().compareTo(amount) < 0) {
            throw new BusinessException("Insufficient balance", HttpStatus.BAD_REQUEST);
        }

        // 3. 执行变更（利用 JPA @Version 乐观锁防并发）
        fromAccount.setBalance(fromAccount.getBalance().subtract(amount));
        toAccount.setBalance(toAccount.getBalance().add(amount));

        // 4. 持久化
        accountRepository.save(fromAccount);
        accountRepository.save(toAccount);
    }
}