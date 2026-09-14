package com.jinjing.banking.modules.account.service;

import com.jinjing.banking.common.exception.BusinessException;
import com.jinjing.banking.modules.account.entity.Account;
import com.jinjing.banking.modules.account.entity.User;
import com.jinjing.banking.modules.account.repository.AccountRepository;
import com.jinjing.banking.modules.account.repository.UserRepository;
import com.jinjing.banking.modules.transaction.entity.ProcessedTransaction;
import com.jinjing.banking.modules.transaction.service.ProcessedTransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * GDPR / DSGVO 数据处理：导出与删除（匿名化）。
 *
 * <p>删除采用匿名化而非物理删除：银行受法定留存义务约束（GDPR Art. 17(3)(b) +
 * § 257 HGB / § 147 AO，均为 10 年），交易流水必须保留。
 *
 * <p><b>匿名化必须做到两件事，缺一不可：</b>
 * <ol>
 *   <li>抹掉直接标识符（用户名、邮箱、密码）——见 {@link #anonymizeUser}；</li>
 *   <li><b>断开关联链</b>：把 {@code accounts.user_id} 置空。只做第一步的话只能算
 *       <i>假名化</i>，因为 {@code user_id → account_no → processed_transactions}
 *       仍然可以把数据重新关联到自然人，该数据在 GDPR Art. 4(1) 下依旧是个人数据。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GdprService {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final ProcessedTransactionService processedTransactionService;
    private final PasswordEncoder passwordEncoder;

    /**
     * 读取用户数据并做权限校验：本人或 ADMIN。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> exportUserData(Long userId, Authentication authentication) {
        User user = requireAccess(userId, authentication);
        List<Account> accounts = accountRepository.findByUserId(user.getId());
        List<ProcessedTransaction> transactions = processedTransactionService
                .getTransactionsByAccounts(accounts.stream().map(Account::getAccountNumber).toList());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user", Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "email", user.getEmail() == null ? "" : user.getEmail(),
                "role", user.getRole().name(),
                "enabled", user.getEnabled(),
                "createdAt", user.getCreatedAt() == null ? "" : user.getCreatedAt().toString()
        ));
        payload.put("accounts", accounts);
        payload.put("transactions", transactions);
        log.info("GDPR export generated for userId={} (accounts={}, transactions={})",
                userId, accounts.size(), transactions.size());
        return payload;
    }

    /**
     * 匿名化用户 PII。同样只允许本人或 ADMIN。
     */
    @Transactional
    public Map<String, Object> anonymizeUser(Long userId, Authentication authentication) {
        User user = requireAccess(userId, authentication);

        String anonymizedUsername = "deleted_user_" + user.getId();
        user.setUsername(anonymizedUsername);
        // email 在 V1 中是 NOT NULL UNIQUE，用不可投递的占位地址代替 null
        user.setEmail("deleted_" + user.getId() + "@anonymized.invalid");
        user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setEnabled(false);
        userRepository.save(user);

        List<Account> accounts = accountRepository.findByUserId(user.getId());
        for (Account account : accounts) {
            account.setOwnerName(anonymizedUsername);
            // [关联链必须断开] 只抹用户名/邮箱/密码叫「假名化(pseudonymisation)」，不叫匿名化：
            // 只要 user_id → account_no → 流水 这条链还在，数据在 GDPR Art. 4(1) 下仍然是个人数据，
            // Art. 17 的豁免不适用。这里把 user_id 置空，切掉「自然人 ← 账户 ← 流水」的最后一环。
            // 账号本身作为业务键保留（流水与分录都引用它），所以法定留存义务
            // （§ 257 HGB / § 147 AO，均为 10 年）不受影响。
            account.setUserId(null);
        }
        accountRepository.saveAll(accounts);

        // 交易流水保留：法定留存义务，且流水本身不含直接 PII（仅有账号）
        int retainedTransactions = processedTransactionService
                .getTransactionsByAccounts(accounts.stream().map(Account::getAccountNumber).toList())
                .size();

        log.warn("GDPR anonymization executed for userId={} (accounts={}, retainedTransactions={})",
                userId, accounts.size(), retainedTransactions);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("anonymizedUsername", anonymizedUsername);
        result.put("accountsAnonymized", accounts.size());
        result.put("linkSevered", true); // accounts.user_id 已置空，关联链不再可重建
        result.put("retainedTransactions", retainedTransactions);
        result.put("status", "ANONYMIZED");
        result.put("legalBasis", "GDPR Art. 17(3)(b) - retention for statutory obligations "
                + "(§ 257 HGB / § 147 AO, 10 years)");
        return result;
    }

    private User requireAccess(Long userId, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException("Authentication required", HttpStatus.UNAUTHORIZED);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found: " + userId, HttpStatus.NOT_FOUND));

        if (AccountAccessService.isAdmin(authentication)) {
            return user;
        }

        Long currentUserId = userRepository.findByUsername(authentication.getName())
                .map(User::getId)
                .orElseThrow(() -> new BusinessException("Authenticated user not found", HttpStatus.UNAUTHORIZED));

        if (!Objects.equals(currentUserId, userId)) {
            log.warn("Access denied: user={} attempted GDPR operation on userId={}",
                    authentication.getName(), userId);
            throw new BusinessException("You are not allowed to access another user's data", HttpStatus.FORBIDDEN);
        }
        return user;
    }
}
