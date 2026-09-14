package com.jinjing.banking.modules.account.service;

import com.jinjing.banking.common.exception.BusinessException;
import com.jinjing.banking.modules.account.entity.Account;
import com.jinjing.banking.modules.account.repository.AccountRepository;
import com.jinjing.banking.modules.ledger.service.LedgerService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferInternalServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private LedgerService ledgerService;

    private TransferInternalService transferInternalService;

    @BeforeEach
    void setUp() {
        transferInternalService = new TransferInternalService(accountRepository, ledgerService, new SimpleMeterRegistry());
    }

    @Test
    void executeTransfer_shouldUpdateBalances() {
        Account from = Account.builder().accountNumber("A001").balance(new BigDecimal("1000")).build();
        Account to = Account.builder().accountNumber("A002").balance(new BigDecimal("500")).build();

        when(accountRepository.findByAccountNumber("A001")).thenReturn(Optional.of(from));
        when(accountRepository.findByAccountNumber("A002")).thenReturn(Optional.of(to));

        transferInternalService.executeTransfer("tx-1", "A001", "A002", new BigDecimal("100"));

        assertEquals(new BigDecimal("900"), from.getBalance());
        assertEquals(new BigDecimal("600"), to.getBalance());
        verify(accountRepository, times(2)).save(any(Account.class));
    }

    /**
     * 余额变更必须伴随两条分录，且金额与币种一致 —— 这是复式记账的核心契约。
     */
    @Test
    void executeTransfer_shouldPostDoubleEntryLedger() {
        Account from = Account.builder().accountNumber("A001").balance(new BigDecimal("1000")).build();
        Account to = Account.builder().accountNumber("A002").balance(new BigDecimal("0")).build();
        when(accountRepository.findByAccountNumber("A001")).thenReturn(Optional.of(from));
        when(accountRepository.findByAccountNumber("A002")).thenReturn(Optional.of(to));

        transferInternalService.executeTransfer("tx-42", "A001", "A002", new BigDecimal("250.50"));

        verify(ledgerService).recordTransfer("tx-42", "A001", "A002", new BigDecimal("250.50"), "EUR");
    }

    /**
     * 跨币种转账需要汇率与单独的业务规则，不能当作普通转账处理。
     */
    @Test
    void executeTransfer_shouldRejectCurrencyMismatch() {
        Account from = Account.builder().accountNumber("A001").balance(new BigDecimal("1000")).currency("EUR").build();
        Account to = Account.builder().accountNumber("A002").balance(new BigDecimal("0")).currency("USD").build();
        when(accountRepository.findByAccountNumber("A001")).thenReturn(Optional.of(from));
        when(accountRepository.findByAccountNumber("A002")).thenReturn(Optional.of(to));

        assertThrows(BusinessException.class, () ->
                transferInternalService.executeTransfer("tx-3", "A001", "A002", new BigDecimal("10")));
        verify(ledgerService, never()).recordTransfer(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void executeTransfer_shouldThrowWhenInsufficientBalance() {
        Account from = Account.builder().accountNumber("A001").balance(new BigDecimal("50")).build();
        when(accountRepository.findByAccountNumber("A001")).thenReturn(Optional.of(from));

        assertThrows(BusinessException.class, () ->
                transferInternalService.executeTransfer("tx-2", "A001", "A002", new BigDecimal("100")));
        verify(ledgerService, never()).recordTransfer(anyString(), anyString(), anyString(), any(), anyString());
    }
}
