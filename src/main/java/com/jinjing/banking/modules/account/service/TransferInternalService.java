package com.jinjing.banking.modules.account.service;

import com.jinjing.banking.common.exception.BusinessException;
import com.jinjing.banking.modules.account.entity.Account;
import com.jinjing.banking.modules.account.repository.AccountRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@Slf4j
public class TransferInternalService {

    private final AccountRepository accountRepository;
    private final MeterRegistry meterRegistry;

    private final Counter transferSuccessCounter;
    private final Counter transferFailureCounter;
    private final Timer transferDurationTimer;

    public TransferInternalService(AccountRepository accountRepository, MeterRegistry meterRegistry) {
        this.accountRepository = accountRepository;
        this.meterRegistry = meterRegistry;
        this.transferSuccessCounter = Counter.builder("transfer.success.total")
                .description("Total successful transfers")
                .register(meterRegistry);
        this.transferFailureCounter = Counter.builder("transfer.failure.total")
                .description("Total failed transfers")
                .register(meterRegistry);
        this.transferDurationTimer = Timer.builder("transfer.duration")
                .description("Transfer execution duration")
                .register(meterRegistry);
    }

    /**
     * 核心转账逻辑：执行具体的扣款和入账。
     * 该方法必须在事务中运行。
     */
    @Transactional
    public void executeTransfer(String fromAccountNo, String toAccountNo, BigDecimal amount) {
        Timer.Sample sample = Timer.start(meterRegistry);
        log.info("Executing atomic transfer: {} -> {} | Amount: {}", fromAccountNo, toAccountNo, amount);

        try {
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

                        transferSuccessCounter.increment();
                } catch (RuntimeException exception) {
                        transferFailureCounter.increment();
                        throw exception;
                } finally {
                        sample.stop(transferDurationTimer);
                }
    }
}