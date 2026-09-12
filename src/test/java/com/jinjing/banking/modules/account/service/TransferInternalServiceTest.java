package com.jinjing.banking.modules.account.service;

import com.jinjing.banking.common.exception.BusinessException;
import com.jinjing.banking.modules.account.entity.Account;
import com.jinjing.banking.modules.account.repository.AccountRepository;
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

    private TransferInternalService transferInternalService;

    @BeforeEach
    void setUp() {
        transferInternalService = new TransferInternalService(accountRepository, new SimpleMeterRegistry());
    }

    @Test
    void executeTransfer_shouldUpdateBalances() {
        Account from = Account.builder().accountNumber("A001").balance(new BigDecimal("1000")).build();
        Account to = Account.builder().accountNumber("A002").balance(new BigDecimal("500")).build();

        when(accountRepository.findByAccountNumber("A001")).thenReturn(Optional.of(from));
        when(accountRepository.findByAccountNumber("A002")).thenReturn(Optional.of(to));

        transferInternalService.executeTransfer("A001", "A002", new BigDecimal("100"));

        assertEquals(new BigDecimal("900"), from.getBalance());
        assertEquals(new BigDecimal("600"), to.getBalance());
        verify(accountRepository, times(2)).save(any(Account.class));
    }

    @Test
    void executeTransfer_shouldThrowWhenInsufficientBalance() {
        Account from = Account.builder().accountNumber("A001").balance(new BigDecimal("50")).build();
        when(accountRepository.findByAccountNumber("A001")).thenReturn(Optional.of(from));

        assertThrows(BusinessException.class, () ->
                transferInternalService.executeTransfer("A001", "A002", new BigDecimal("100")));
    }
}
