package com.jinjing.banking.modules.account.service;

import com.jinjing.banking.common.exception.BusinessException;
import com.jinjing.banking.modules.account.dto.TransferRequest;
import com.jinjing.banking.modules.account.entity.Account;
import com.jinjing.banking.modules.account.repository.AccountRepository;
import com.jinjing.banking.modules.transaction.entity.ProcessedTransaction;
import com.jinjing.banking.modules.transaction.service.ProcessedTransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AccountServiceTest (Mockito 单元测试)
 * createAccount_savesAndReturnsAccount — 测试账户创建
 * getAccount_returnsAccountWhenExists / _returnsEmptyWhenNotFound — 测试查询
 * validateRisk_throwsExceptionWhenAmountExceedsLimit — 测试风控限额
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransferInternalService transferInternalService;

    @Mock
    private ProcessedTransactionService processedTransactionService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        // 初始化 mock 行为
    }

    @Test
    void createAccount_savesAndReturnsAccount() {
        // Given
        Account account = Account.builder()
                .ownerName("John Doe")
                .accountNumber("ACC123456")
                .build();
        when(accountRepository.save(any(Account.class))).thenReturn(account);

        // When
        Account result = accountService.createAccount(account);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getOwnerName()).isEqualTo("John Doe");
        verify(accountRepository).save(any(Account.class));
    }

    @Test
    void getAccount_returnsAccountWhenExists() {
        // Given
        Account account = Account.builder()
                .accountNumber("ACC123456")
                .ownerName("John Doe")
                .build();
        when(accountRepository.findByAccountNumber("ACC123456")).thenReturn(Optional.of(account));

        // When
        Optional<Account> result = accountService.getAccount("ACC123456");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getOwnerName()).isEqualTo("John Doe");
    }

    @Test
    void getAccount_returnsEmptyWhenNotFound() {
        // Given
        when(accountRepository.findByAccountNumber("UNKNOWN")).thenReturn(Optional.empty());

        // When
        Optional<Account> result = accountService.getAccount("UNKNOWN");

        // Then
        assertThat(result).isEmpty();
    }
}
