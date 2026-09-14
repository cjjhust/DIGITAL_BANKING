package com.jinjing.banking.modules.account.service;

import com.jinjing.banking.common.exception.BusinessException;
import com.jinjing.banking.modules.account.entity.Account;
import com.jinjing.banking.modules.account.entity.Role;
import com.jinjing.banking.modules.account.entity.User;
import com.jinjing.banking.modules.account.repository.AccountRepository;
import com.jinjing.banking.modules.account.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 账户级授权：所有涉及具体账户的接口都必须先过这一层。
 *
 * <p>规则：
 * <ul>
 *     <li>ADMIN 可访问任意账户；</li>
 *     <li>普通用户只能访问 user_id 指向自己的账户；</li>
 *     <li>兼容历史数据：早期账户没有 user_id，若 owner_name 等于当前用户名则视为本人账户。</li>
 * </ul>
 *
 * <p>放在 service 层而不是 controller 层，一是为了让 ArchUnit 的
 * “Controller 不直接依赖 Repository” 规则继续成立，二是为了后续抽
 * TransferCommandService 时可以直接复用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountAccessService {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    /**
     * 校验当前认证主体是否有权访问该账户，无权限时抛 403，账户不存在时抛 404。
     */
    public Account requireAccess(String accountNumber, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException("Authentication required", HttpStatus.UNAUTHORIZED);
        }

        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new BusinessException("Account not found: " + accountNumber, HttpStatus.NOT_FOUND));

        if (isAdmin(authentication)) {
            return account;
        }

        String username = authentication.getName();
        boolean owner = Objects.equals(account.getUserId(), currentUserId(authentication))
                // 兼容 V7 之前创建、尚未回填 user_id 的账户
                || (account.getUserId() == null && username.equals(account.getOwnerName()));

        if (!owner) {
            log.warn("Access denied: user={} attempted to access account={} (owner={})",
                    username, accountNumber, account.getOwnerName());
            throw new BusinessException("You are not allowed to access this account", HttpStatus.FORBIDDEN);
        }
        return account;
    }

    /**
     * 当前登录用户的数据库主键。
     */
    public Long currentUserId(Authentication authentication) {
        String username = authentication.getName();
        return userRepository.findByUsername(username)
                .map(User::getId)
                .orElseThrow(() -> new BusinessException("Authenticated user not found: " + username,
                        HttpStatus.UNAUTHORIZED));
    }

    /**
     * 是否拥有管理员角色（角色 code 为 ROLE_ADMIN，与 @PreAuthorize("hasRole('ADMIN')") 保持一致）。
     */
    public static boolean isAdmin(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(Role.ADMIN.getCode()::equals);
    }
}
