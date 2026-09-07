package com.jinjing.banking.modules.account.service;

import com.jinjing.banking.modules.account.dto.AuthResponse;
import com.jinjing.banking.modules.account.dto.LoginRequest;
import com.jinjing.banking.modules.account.dto.RegisterRequest;
import com.jinjing.banking.modules.account.entity.Role;
import com.jinjing.banking.modules.account.entity.User;
import com.jinjing.banking.modules.account.repository.UserRepository;
import com.jinjing.banking.modules.account.util.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
/**
 * AuthServiceTest (Mockito 单元测试)

 *loginSuccess_returnsJwtToken — 测试成功登录返回 JWT
 *registerNewUser_createsAccountAndReturnsToken — 测试新用户注册
 *loginWithWrongPassword_throwsBusinessException — 测试错误密码抛出业务异常
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtUtils jwtUtils;

    @InjectMocks
    private AuthService authService;

    @BeforeEach
    void setUp() {
        // 初始化 mock 行为
    }

    @Test
    void loginSuccess_returnsJwtToken() {
        // Given
        String username = "testuser";
        String password = "secret";
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);

        Authentication auth = mock(Authentication.class);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(auth);
        when(jwtUtils.generateToken(auth)).thenReturn("mock-jwt-token");

        User user = User.builder()
                .username(username)
                .email("test@test.com")
                .role(Role.USER)
                .enabled(true)
                .build();
        when(userRepository.findByUsername(username)).thenReturn(Optional.of(user));

        // When
        AuthResponse response = authService.login(username, password);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getToken()).isEqualTo("mock-jwt-token");
        assertThat(response.getTokenType()).isEqualTo("Bearer");
        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
    }

    @Test
    void registerNewUser_createsAccountAndReturnsToken() {
        // Given
        RegisterRequest req = new RegisterRequest();
        req.setUsername("newuser");
        req.setPassword("password");
        req.setEmail("new@test.com");

        when(userRepository.existsByUsername("newuser")).thenReturn(false);
        when(userRepository.existsByEmail("new@test.com")).thenReturn(false);
        when(passwordEncoder.encode("password")).thenReturn("encoded-password");

        User saved = User.builder()
                .username("newuser")
                .email("new@test.com")
                .password("encoded-password")
                .role(Role.USER)
                .enabled(true)
                .build();
        when(userRepository.save(any(User.class))).thenReturn(saved);

        Authentication auth = mock(Authentication.class);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(auth);
        when(jwtUtils.generateToken(auth)).thenReturn("new-token");

        // When
        AuthResponse response = authService.register(req);

        // Then
        assertThat(response.getToken()).isEqualTo("new-token");
        verify(userRepository).save(any(User.class));
    }

    @Test
    void loginWithWrongPassword_throwsBusinessException() {
        // Given
        when(authenticationManager.authenticate(any())).thenThrow(new org.springframework.security.core.AuthenticationException("bad") {});

        // Then
        assertThatThrownBy(() -> authService.login("bad", "bad"))
                .isInstanceOf(com.jinjing.banking.common.exception.BusinessException.class)
                .hasMessageContaining("用户名或密码错误");
    }
}
