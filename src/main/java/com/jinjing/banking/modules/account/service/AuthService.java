package com.jinjing.banking.modules.account.service;

import com.jinjing.banking.common.exception.BusinessException;
import com.jinjing.banking.modules.account.dto.AuthResponse;
import com.jinjing.banking.modules.account.dto.RegisterRequest;
import com.jinjing.banking.modules.account.entity.Role;
import com.jinjing.banking.modules.account.entity.User;
import com.jinjing.banking.modules.account.repository.UserRepository;
import com.jinjing.banking.modules.account.util.JwtUtils;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

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

    /**
     * 注销：把当前 Token 写入 Redis 黑名单，TTL 取剩余有效期。
     * 请求本身可能已经过期（那就不需要注销），因此这里不抛异常，只回报结果。
     */
    public Map<String, Object> logout(String token) {
        boolean revoked = jwtUtils.blacklistToken(token);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("revoked", revoked);
        result.put("message", revoked ? "Token 已注销" : "Token 已过期，无需注销");
        return result;
    }

    /**
     * 刷新：用未过期的 Token 换取新 Token（旧 Token 立即失效），角色声明原样保留。
     * 超出可刷新窗口、已被注销、已过期都会返回 401，要求重新登录。
     */
    public AuthResponse refresh(String token) {
        String newToken;
        try {
            newToken = jwtUtils.refreshAccessToken(token);
        } catch (JwtException e) {
            throw new BusinessException(e.getMessage(), HttpStatus.UNAUTHORIZED);
        }

        String username = jwtUtils.getUsernameFromToken(newToken);
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new BusinessException("用户不存在或已注销", HttpStatus.UNAUTHORIZED));
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new BusinessException("账户已停用", HttpStatus.UNAUTHORIZED);
        }

        log.info("Token 刷新成功: {}", username);

        return AuthResponse.builder()
            .token(newToken)
            .tokenType("Bearer")
            .expiresIn(jwtUtils.getExpirationTime() / 1000)
            .user(AuthResponse.UserInfo.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole().getCode())
                .build())
            .build();
    }
}
