package com.jinjing.banking.modules.account.service;

import com.jinjing.banking.common.exception.BusinessException;
import com.jinjing.banking.modules.account.entity.Account;
import com.jinjing.banking.modules.account.entity.Role;
import com.jinjing.banking.modules.account.entity.User;
import com.jinjing.banking.modules.account.repository.AccountRepository;
import com.jinjing.banking.modules.account.repository.UserRepository;
import com.jinjing.banking.modules.transaction.entity.ProcessedTransaction;
import com.jinjing.banking.modules.transaction.service.ProcessedTransactionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GDPR 导出与匿名化：本人可用、他人 403、ADMIN 放行，且匿名化必须真正落库。
 */
@ExtendWith(MockitoExtension.class)
class GdprServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private ProcessedTransactionService processedTransactionService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private GdprService gdprService;

    private static Authentication auth(String username, String authority) {
        return new UsernamePasswordAuthenticationToken(
                username, "n/a", List.of(new SimpleGrantedAuthority(authority)));
    }

    private static User user(long id, String username) {
        return User.builder().id(id).username(username).email(username + "@test.com")
                .role(Role.USER).enabled(true).build();
    }

    private static Account account(String number, long userId) {
        return Account.builder().accountNumber(number).ownerName("alice").userId(userId).build();
    }

    @Test
    void exportSelfReturnsProfileAccountsAndTransactions() {
        User alice = user(1L, "alice");
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(accountRepository.findByUserId(1L)).thenReturn(List.of(account("A1", 1L)));
        when(processedTransactionService.getTransactionsByAccounts(any())).thenReturn(List.of(
                ProcessedTransaction.builder().transactionId("t1").clientRequestId("r1").build()));

        Map<String, Object> payload = gdprService.exportUserData(1L, auth("alice", "ROLE_USER"));

        assertThat(payload).containsKeys("user", "accounts", "transactions");
        assertThat(payload.get("accounts")).asList().hasSize(1);
        assertThat(payload.get("transactions")).asList().hasSize(1);
    }

    @Test
    void exportOfAnotherUserIsForbidden() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "bob")));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(1L, "alice")));

        BusinessException ex = catchThrowableOfType(
                () -> gdprService.exportUserData(2L, auth("alice", "ROLE_USER")),
                BusinessException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(accountRepository, never()).findByUserId(any());
    }

    @Test
    void adminCanExportAnotherUser() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "bob")));
        when(accountRepository.findByUserId(2L)).thenReturn(List.of());
        when(processedTransactionService.getTransactionsByAccounts(any())).thenReturn(List.of());

        Map<String, Object> payload = gdprService.exportUserData(2L, auth("root", "ROLE_ADMIN"));

        assertThat(payload.get("user")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("username", "bob");
    }

    @Test
    void anonymizeRewritesPiiAndKeepsAccounts() {
        User alice = user(1L, "alice");
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(accountRepository.findByUserId(1L)).thenReturn(List.of(account("A1", 1L)));
        when(processedTransactionService.getTransactionsByAccounts(any())).thenReturn(List.of());
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-random");

        Map<String, Object> result = gdprService.anonymizeUser(1L, auth("alice", "ROLE_USER"));

        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedUser.capture());
        assertThat(savedUser.getValue().getUsername()).isEqualTo("deleted_user_1");
        assertThat(savedUser.getValue().getEmail()).isEqualTo("deleted_1@anonymized.invalid");
        assertThat(savedUser.getValue().getEnabled()).isFalse();
        assertThat(savedUser.getValue().getPassword()).isEqualTo("encoded-random");

        ArgumentCaptor<List<Account>> savedAccounts = ArgumentCaptor.forClass(List.class);
        verify(accountRepository).saveAll(savedAccounts.capture());
        assertThat(savedAccounts.getValue()).allSatisfy(a ->
                assertThat(a.getOwnerName()).isEqualTo("deleted_user_1"));

        assertThat(result).containsEntry("status", "ANONYMIZED")
                .containsEntry("anonymizedUsername", "deleted_user_1")
                .containsEntry("accountsAnonymized", 1);
    }

    @Test
    void anonymizeOfAnotherUserIsForbidden() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "bob")));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(1L, "alice")));

        BusinessException ex = catchThrowableOfType(
                () -> gdprService.anonymizeUser(2L, auth("alice", "ROLE_USER")),
                BusinessException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(userRepository, never()).save(any());
    }
}
