package com.jinjing.banking.modules.account.service;

import com.jinjing.banking.common.exception.BusinessException;
import com.jinjing.banking.modules.account.entity.Account;
import com.jinjing.banking.modules.account.repository.AccountRepository;
import com.jinjing.banking.modules.ledger.service.LedgerService;

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
    private final LedgerService ledgerService;
    private final MeterRegistry meterRegistry;

    // 计数器（transfer.success.total / transfer.failure.total）已移到 AccountService 的终态判定处。
    // 原因：业务校验（自转账、超限、精度）发生在 validateRisk()，在 executeTransfer() **之外**，
    // 原先在这里 increment 会漏计所有业务规则拒绝 —— 实测 Prometheus failure=0 而实际失败 3 笔。
    // 现在「终态在哪里确定，计数器就在哪里加」，与 transaction_outcome 写入点一致。
    private final Timer transferDurationTimer;

    public TransferInternalService(AccountRepository accountRepository, LedgerService ledgerService,
                                   MeterRegistry meterRegistry) {
        this.accountRepository = accountRepository;
        this.ledgerService = ledgerService;
        this.meterRegistry = meterRegistry;
        this.transferDurationTimer = Timer.builder("transfer.duration")
                .description("Transfer execution duration")
                .register(meterRegistry);
    }

    /**
     * 核心转账逻辑：执行具体的扣款和入账，并写入复式记账分录。
     * 该方法必须在事务中运行 —— 余额变更与两条分录必须同时生效或同时失效。n     */
    @Transactional
    public void executeTransfer(String transactionId, String fromAccountNo, String toAccountNo, BigDecimal amount) {
        Timer.Sample sample = Timer.start(meterRegistry);
        log.info("Executing atomic transfer: {} -> {} | Amount: {}", fromAccountNo, toAccountNo, amount);

        try {
            // 1. 获取并校验账户
                        Account fromAccount = accountRepository.findByAccountNumber(fromAccountNo)
                                        .orElseThrow(() -> new BusinessException("Source account not found", HttpStatus.NOT_FOUND));

                        Account toAccount = accountRepository.findByAccountNumber(toAccountNo)
                                        .orElseThrow(() -> new BusinessException("Target account not found", HttpStatus.NOT_FOUND));

                        // 2. 币种必须一致：跨币种转账是先换汇再转账，不是一笔转账
                        if (!fromAccount.getCurrency().equals(toAccount.getCurrency())) {
                                throw new BusinessException(
                                        "Currency mismatch: " + fromAccount.getCurrency() + " -> " + toAccount.getCurrency(),
                                        HttpStatus.BAD_REQUEST);
                        }

                        // 3. 余额检查
                        if (fromAccount.getBalance().compareTo(amount) < 0) {
                                throw new BusinessException("Insufficient balance", HttpStatus.BAD_REQUEST);
                        }

                        // 4. 执行变更（利用 JPA @Version 乐观锁防并发）
                        fromAccount.setBalance(fromAccount.getBalance().subtract(amount));
                        toAccount.setBalance(toAccount.getBalance().add(amount));

                        // 5. 持久化
                        accountRepository.save(fromAccount);
                        accountRepository.save(toAccount);

                        // 6. 复式记账：借方一条、贷方一条，与余额变更在同一事务内
                        ledgerService.recordTransfer(transactionId, fromAccountNo, toAccountNo, amount,
                                fromAccount.getCurrency());
                } finally {
                        // 计时器留在这里：它度量的是「真正尝试执行转账」的耗时。
                        sample.stop(transferDurationTimer);
                }
    }
}