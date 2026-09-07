package com.jinjing.banking.config;

import com.jinjing.banking.common.exception.BusinessException;
import com.jinjing.banking.modules.account.entity.Role;
import com.jinjing.banking.modules.account.entity.User;
import com.jinjing.banking.modules.account.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 系统启动时初始化管理员账号
 * 仅在没有管理员用户时创建默认 admin
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminDataInitializer {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.default-admin.username:admin}")
    private String defaultAdminUsername;

    @Value("${APP_DEFAULT_ADMIN_PASSWORD:admin123}")
    private String defaultAdminPassword;

    @Value("${app.default-admin.email:admin@example.com}")
    private String defaultAdminEmail;

    @PostConstruct
    public void initAdminUser() {
        boolean adminExists = userRepository.findAll().stream()
            .anyMatch(user -> user.getRole() == Role.ADMIN);

        if (adminExists) {
            log.info("管理员账号已存在，跳过默认管理员初始化");
            return;
        }

        if (defaultAdminPassword == null || defaultAdminPassword.isBlank()) {
            throw new BusinessException("默认管理员密码未配置，请设置 APP_DEFAULT_ADMIN_PASSWORD 环境变量");
        }

        User admin = User.builder()
            .username(defaultAdminUsername)
            .password(passwordEncoder.encode(defaultAdminPassword))
            .email(defaultAdminEmail)
            .role(Role.ADMIN)
            .enabled(true)
            .build();

        userRepository.save(admin);
        log.info("已初始化默认管理员账号: {}", defaultAdminUsername);
    }
}
