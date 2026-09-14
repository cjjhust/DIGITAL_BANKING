package com.jinjing.banking.modules.account.util;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JWT 注销（黑名单）与刷新（轮换）行为测试。
 */
@ExtendWith(MockitoExtension.class)
class JwtUtilsTest {

    /** 64 字节密钥：满足 HS512 要求 */
    private static final String SECRET =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private JwtUtils jwtUtils;

    private JwtUtils newJwtUtils(String secret, long expirationMs, long refreshExpirationMs) {
        JwtUtils utils = new JwtUtils(redisTemplate);
        ReflectionTestUtils.setField(utils, "jwtSecret", secret);
        ReflectionTestUtils.setField(utils, "jwtExpirationMs", expirationMs);
        ReflectionTestUtils.setField(utils, "jwtRefreshExpirationMs", refreshExpirationMs);
        return utils;
    }

    @BeforeEach
    void setUp() {
        jwtUtils = newJwtUtils(SECRET, 3_600_000L, 86_400_000L);
    }

    @Test
    void generatedTokenIsValidAndKeepsRoles() {
        String token = jwtUtils.generateTokenFromUsername("alice", List.of("ROLE_USER"));

        assertThat(jwtUtils.validateToken(token)).isTrue();
        assertThat(jwtUtils.getUsernameFromToken(token)).isEqualTo("alice");
        assertThat(jwtUtils.getClaims(token)).containsEntry("authorities", List.of("ROLE_USER"));
        assertThat(jwtUtils.getRemainingValidityMs(token)).isPositive();
    }

    @Test
    void missingSecretFailsFast() {
        JwtUtils broken = newJwtUtils("", 3_600_000L, 86_400_000L);

        assertThatThrownBy(broken::validateSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_JWT_SECRET");
    }

    @Test
    void blacklistUsesHashedKeyWithRemainingTtl() {
        String token = jwtUtils.generateTokenFromUsername("alice", List.of("ROLE_USER"));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        assertThat(jwtUtils.blacklistToken(token)).isTrue();

        // key 是 SHA-256 摘要，不能把明文 token 留在 Redis 里
        verify(valueOperations).set(startsWith("jwt:blacklist:"), eq("1"), any(Duration.class));
        verify(valueOperations, never()).set(eq(token), anyString(), any(Duration.class));
    }

    @Test
    void expiredTokenCannotBeBlacklisted() {
        JwtUtils expired = newJwtUtils(SECRET, -1_000L, 86_400_000L);
        String token = expired.generateTokenFromUsername("alice", List.of("ROLE_USER"));

        assertThat(expired.blacklistToken(token)).isFalse();
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void isBlacklistedReadsRedis() {
        when(redisTemplate.hasKey(startsWith("jwt:blacklist:"))).thenReturn(true);
        assertThat(jwtUtils.isBlacklisted("whatever")).isTrue();
    }

    @Test
    void refreshRotatesTokenAndPreservesRoles() {
        String oldToken = jwtUtils.generateTokenFromUsername("alice", List.of("ROLE_USER", "ROLE_ADMIN"));
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        String newToken = jwtUtils.refreshAccessToken(oldToken);

        assertThat(newToken).isNotEqualTo(oldToken);
        assertThat(jwtUtils.getClaims(newToken)).containsEntry("authorities", List.of("ROLE_USER", "ROLE_ADMIN"));
        // 刷新即轮换：旧 token 必须被注销
        verify(valueOperations).set(startsWith("jwt:blacklist:"), eq("1"), any(Duration.class));
    }

    @Test
    void refreshRejectsBlacklistedToken() {
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        JwtException ex = catchThrowableOfType(
                () -> jwtUtils.refreshAccessToken("revoked-token"),
                JwtException.class);

        assertThat(ex).hasMessageContaining("已被注销");
    }

    @Test
    void refreshRejectsTokenBeyondSessionWindow() {
        // 会话窗口 1ms：签发后立刻超出可刷新窗口
        JwtUtils shortWindow = newJwtUtils(SECRET, 3_600_000L, 1L);
        String token = shortWindow.generateTokenFromUsername("alice", List.of("ROLE_USER"));
        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        JwtException ex = catchThrowableOfType(
                () -> shortWindow.refreshAccessToken(token),
                JwtException.class);

        assertThat(ex).hasMessageContaining("可刷新窗口");
    }

    @Test
    void refreshRejectsExpiredToken() {
        JwtUtils expired = newJwtUtils(SECRET, -1_000L, 86_400_000L);
        String token = expired.generateTokenFromUsername("alice", List.of("ROLE_USER"));
        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        JwtException ex = catchThrowableOfType(
                () -> expired.refreshAccessToken(token),
                JwtException.class);

        assertThat(ex).hasMessageContaining("无效或已过期");
    }
}
