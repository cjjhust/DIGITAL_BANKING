package com.jinjing.banking.modules.account.controller;

import com.jinjing.banking.common.exception.BusinessException;
import com.jinjing.banking.modules.account.dto.AuthResponse;
import com.jinjing.banking.modules.account.dto.LoginRequest;
import com.jinjing.banking.modules.account.dto.RegisterRequest;
import com.jinjing.banking.modules.account.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证控制器
 * 处理用户登录、注册、获取当前用户信息等请求
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 用户登录
     * POST /api/auth/login
     * 请求体: { "username": "...", "password": "..." }
     * 响应: { "token": "...", "tokenType": "Bearer", "expiresIn": 3600, "user": {...} }
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest loginRequest) {
        log.info("用户登录请求: {}", loginRequest.getUsername());
        AuthResponse response = authService.login(loginRequest.getUsername(), loginRequest.getPassword());
        return ResponseEntity.ok(response);
    }

    /**
     * 用户注册
     * POST /api/auth/register
     * 请求体: { "username": "...", "password": "...", "email": "..." }
     * 响应: 同登录，返回 Token
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest registerRequest) {
        log.info("用户注册请求: {}", registerRequest.getUsername());
        AuthResponse response = authService.register(registerRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 获取当前用户信息
     * GET /api/auth/me
     * 需要有效的 JWT Token（Authorization: Bearer <token>）
     */
    @GetMapping("/me")
    public ResponseEntity<AuthResponse.UserInfo> getCurrentUser(Authentication authentication) {
        log.info("获取当前用户信息: {}", authentication.getName());
        AuthResponse.UserInfo userInfo = authService.getCurrentUserInfo(authentication.getName());
        return ResponseEntity.ok(userInfo);
    }

    /**
     * 验证 Token 是否有效
     * GET /api/auth/validate
     * 需要有效的 JWT Token
     */
    @GetMapping("/validate")
    public ResponseEntity<?> validateToken() {
        return ResponseEntity.ok(new Object() {
            public final boolean valid = true;
            public final String message = "Token 有效";
        });
    }

    /**
     * 注销
     * POST /api/auth/logout
     * 请求头: Authorization: Bearer <token>
     * 效果：Token 进入 Redis 黑名单（TTL = 剩余有效期），之后该 Token 立即失效
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
        String token = extractBearerToken(request);
        if (token == null) {
            throw new BusinessException("缺少 Authorization: Bearer <token> 请求头", HttpStatus.UNAUTHORIZED);
        }
        return ResponseEntity.ok(authService.logout(token));
    }

    /**
     * 刷新 Token
     * POST /api/auth/refresh
     * 请求头: Authorization: Bearer <token>（未过期的旧 Token）
     * 响应：与登录相同，返回新的 Token；旧 Token 立即失效（轮换）
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(HttpServletRequest request) {
        String token = extractBearerToken(request);
        if (token == null) {
            throw new BusinessException("缺少 Authorization: Bearer <token> 请求头", HttpStatus.UNAUTHORIZED);
        }
        log.info("Token 刷新请求");
        return ResponseEntity.ok(authService.refresh(token));
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
