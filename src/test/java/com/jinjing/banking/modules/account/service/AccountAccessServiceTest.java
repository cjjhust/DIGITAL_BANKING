package com.jinjing.banking.modules.account.service;

import com.jinjing.banking.common.exception.BusinessException;
import com.jinjing.banking.modules.account.entity.Account;
import com.jinjing.banking.modules.account.entity.Role;
import com.jinjing.banking.modules.account.entity.User;
import com.jinjing.banking.modules.account.repository.AccountRepository;
import com.jinjing.banking.modules.account.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 账户级授权规则测试：本人可访问、他人 403、ADMIN 放行、历史数据兼容、账户不存在 404。
 */
@ExtendWith(MockitoExtension.class)
class AccountAccessServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AccountAccessService accountAccessService;

    private static Authentication auth(String username, String authority) {
        return new UsernamePasswordAuthenticationToken(
                username, "n/a", List.of(new SimpleGrantedAuthority(authority)));
    }

    private static Account account(String number, Long userId, String ownerName) {
        return Account.builder()
                .accountNumber(number)
                .ownerName(ownerName)
                .userId(userId)
                .build();
    }

    private static User user(long id, String username) {
        return User.builder().id(id).username(username).role(Role.USER).enabled(true).build();
    }

    @Test
    void ownerCanAccessOwnAccount() {
        Account acc = account("A1", 1L, "alice");
        when(accountRepository.findByAccountNumber("A1")).thenReturn(Optional.of(acc));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(1L, "alice")));

        assertThat(accountAccessService.requireAccess("A1", auth("alice", "ROLE_USER"))).isSameAs(acc);
    }

    @Test
    void anotherUserIsForbidden() {
        when(accountRepository.findByAccountNumber("A1")).thenReturn(Optional.of(account("A1", 2L, "bob")));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(1L, "alice")));

        BusinessException ex = catchThrowableOfType(
                () -> accountAccessService.requireAccess("A1", auth("alice", "ROLE_USER")),
                BusinessException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminBypassesOwnershipCheck() {
        Account acc = account("A1", 2L, "bob");
        when(accountRepository.findByAccountNumber("A1")).thenReturn(Optional.of(acc));

        assertThat(accountAccessService.requireAccess("A1", auth("root", "ROLE_ADMIN"))).isSameAs(acc);
        verifyNoInteractions(userRepository);
    }

    @Test
    void legacyAccountWithoutUserIdFallsBackToOwnerName() {
        // V7 之前的账户没有 user_id，回填失败时用 owner_name 兜底
        when(accountRepository.findByAccountNumber("LEGACY")).thenReturn(Optional.of(account("LEGACY", null, "alice")));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(1L, "alice")));

        assertThat(accountAccessService.requireAccess("LEGACY", auth("alice", "ROLE_USER"))).isNotNull();
    }

    @Test
    void legacyAccountWithDifferentOwnerNameIsForbidden() {
        when(accountRepository.findByAccountNumber("LEGACY")).thenReturn(Optional.of(account("LEGACY", null, "bob")));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(1L, "alice")));

        BusinessException ex = catchThrowableOfType(
                () -> accountAccessService.requireAccess("LEGACY", auth("alice", "ROLE_USER")),
                BusinessException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unknownAccountIsNotFound() {
        when(accountRepository.findByAccountNumber("NOPE")).thenReturn(Optional.empty());

        BusinessException ex = catchThrowableOfType(
                () -> accountAccessService.requireAccess("NOPE", auth("alice", "ROLE_USER")),
                BusinessException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unauthenticatedRequestIsRejected() {
        BusinessException ex = catchThrowableOfType(
                () -> accountAccessService.requireAccess("A1", null),
                BusinessException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
