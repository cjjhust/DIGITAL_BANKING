package com.jinjing.banking.modules.account.service;

import com.jinjing.banking.common.exception.BusinessException;
import com.jinjing.banking.modules.account.dto.AuthResponse;
import com.jinjing.banking.modules.account.dto.RegisterRequest;
import com.jinjing.banking.modules.account.entity.Role;
import com.jinjing.banking.modules.account.entity.User;
import com.jinjing.banking.modules.account.repository.UserRepository;
import com.jinjing.banking.modules.account.util.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证服务
 * 处理用户登录、注册、Token 生成等逻辑
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtUtils jwtUtils;

    /**
     * 用户登录
     * @param username 用户名
     * @param password 密码
     * @return 认证响应（包含 JWT Token）
     */
    public AuthResponse login(String username, String password) {
        try {
            // 使用 Spring Security 的认证管理器进行认证
            Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(username, password)
            );

            // 生成 JWT Token
            String token = jwtUtils.generateToken(authentication);

            // 获取用户信息
            User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException("用户不存在"));

            log.info("用户登录成功: {}", username);

            // 构建响应
            return AuthResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresIn(jwtUtils.getExpirationTime() / 1000) // 转换为秒
                .user(AuthResponse.UserInfo.builder()
                    .id(user.getId())
                    .username(user.getUsername())
                    .email(user.getEmail())
                    .role(user.getRole().getCode())
                    .build())
                .build();
        } catch (org.springframework.security.core.AuthenticationException e) {
            log.warn("登录失败: 用户名或密码错误");
            throw new BusinessException("用户名或密码错误");
        }
    }

    /**
     * 用户注册
     * @param registerRequest 注册请求
     * @return 认证响应
     */
    @Transactional
    public AuthResponse register(RegisterRequest registerRequest) {
        // 检查用户名是否已存在
        if (userRepository.existsByUsername(registerRequest.getUsername())) {
            throw new BusinessException("用户名已存在");
        }

        // 检查邮箱是否已存在
        if (userRepository.existsByEmail(registerRequest.getEmail())) {
            throw new BusinessException("邮箱已被注册");
        }

        // 创建新用户
        User newUser = User.builder()
            .username(registerRequest.getUsername())
            .password(passwordEncoder.encode(registerRequest.getPassword()))
            .email(registerRequest.getEmail())
            .role(Role.USER)
            .enabled(true)
            .build();

        User savedUser = userRepository.save(newUser);

        log.info("新用户注册成功: {}", registerRequest.getUsername());

        // 自动生成 Token（注册后直接登录）
        Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(
                registerRequest.getUsername(),
                registerRequest.getPassword()
            )
        );

        String token = jwtUtils.generateToken(authentication);

        return AuthResponse.builder()
            .token(token)
            .tokenType("Bearer")
            .expiresIn(jwtUtils.getExpirationTime() / 1000)
            .user(AuthResponse.UserInfo.builder()
                .id(savedUser.getId())
                .username(savedUser.getUsername())
                .email(savedUser.getEmail())
                .role(savedUser.getRole().getCode())
                .build())
            .build();
    }

    /**
     * 获取当前用户信息
     */
    public AuthResponse.UserInfo getCurrentUserInfo(String username) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new BusinessException("用户不存在"));

        return AuthResponse.UserInfo.builder()
            .id(user.getId())
            .username(user.getUsername())
            .email(user.getEmail())
            .role(user.getRole().getCode())
            .build();
    }
}
