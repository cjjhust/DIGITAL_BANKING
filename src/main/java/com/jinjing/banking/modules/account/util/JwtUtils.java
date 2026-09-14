package com.jinjing.banking.modules.account.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * JWT Token 工具类
 * 负责生成、验证、解析、注销（黑名单）与刷新 JWT Token。
 */
@Slf4j
@Component
public class JwtUtils {

    /** HS512 需要 ≥ 64 字节密钥，HS256 需要 ≥ 32 字节；这里按密钥长度自动选择，避免 WeakKeyException */
    private static final int HS512_MIN_BYTES = 64;
    private static final int HS256_MIN_BYTES = 32;

    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";

    @Value("${app.jwtSecret:}")
    private String jwtSecret;

    @Value("${app.jwtExpirationMs:3600000}")
    private long jwtExpirationMs;

    @Value("${app.jwtRefreshExpirationMs:86400000}")
    private long jwtRefreshExpirationMs;

    private final StringRedisTemplate redisTemplate;

    public JwtUtils(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * fail-fast：application.yml 里 `app.jwtSecret: ${APP_JWT_SECRET:}` 是空串兜底，
     * 会覆盖 @Value 的默认值。不校验的话应用能启动、但首次登录才 500。
     */
    @PostConstruct
    void validateSecret() {
        byte[] key = jwtSecret == null ? new byte[0] : jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (key.length < HS256_MIN_BYTES) {
            throw new IllegalStateException(
                    "app.jwtSecret 未配置或过短（当前 " + key.length + " 字节，至少需要 " + HS256_MIN_BYTES
                            + " 字节）。请设置环境变量 APP_JWT_SECRET 后再启动。");
        }
        if (key.length < HS512_MIN_BYTES) {
            log.warn("app.jwtSecret 不足 {} 字节，将使用 HS256 签名（生产环境建议 ≥ {} 字节以启用 HS512）",
                    HS512_MIN_BYTES, HS512_MIN_BYTES);
        }
    }

    /**
     * 从 Authentication 对象生成 JWT Token
     */
    public String generateToken(Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        return generateTokenFromUsername(userDetails.getUsername(),
                userDetails.getAuthorities().stream()
                        .map(a -> a.getAuthority())
                        .toList());
    }

    /**
     * 从用户名生成 JWT Token
     *
     * <p>必须带唯一 {@code jti}：否则同一秒内（iat/exp 精度为秒）用相同声明生成的 Token 会完全一致，
     * 刷新轮换时就会出现「旧 Token 被拉黑、新 Token 因字符串相同一起失效」的问题。
     */
    public String generateTokenFromUsername(String username, java.util.Collection<String> roles) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpirationMs);

        SecretKey key = signingKey();

        return Jwts.builder()
            .id(java.util.UUID.randomUUID().toString())
            .subject(username)
            .claim("authorities", roles)
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(key, signatureAlgorithm(key))
            .compact();
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    private SignatureAlgorithm signatureAlgorithm(SecretKey key) {
        return key.getEncoded().length >= HS512_MIN_BYTES ? SignatureAlgorithm.HS512 : SignatureAlgorithm.HS256;
    }

    /**
     * 从 JWT Token 中提取用户名
     */
    public String getUsernameFromToken(String token) {
        try {
            SecretKey key = signingKey();
            Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
            return claims.getSubject();
        } catch (ExpiredJwtException e) {
            log.warn("JWT token 已过期");
            throw new JwtException("Token 已过期");
        } catch (UnsupportedJwtException e) {
            log.warn("不支持的 JWT token");
            throw new JwtException("不支持的 Token 格式");
        } catch (MalformedJwtException e) {
            log.warn("无效的 JWT token");
            throw new JwtException("无效的 Token");
        } catch (SignatureException e) {
            log.warn("JWT signature 验证失败");
            throw new JwtException("Token 签名验证失败");
        } catch (IllegalArgumentException e) {
            log.warn("JWT claims 为空");
            throw new JwtException("Token 内容为空");
        }
    }

    /**
     * 验证 JWT Token 是否有效
     */
    public boolean validateToken(String token) {
        try {
            SecretKey key = signingKey();
            Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("JWT token 已过期: {}", e.getMessage());
            return false;
        } catch (UnsupportedJwtException e) {
            log.warn("不支持的 JWT token: {}", e.getMessage());
            return false;
        } catch (MalformedJwtException e) {
            log.warn("无效的 JWT token: {}", e.getMessage());
            return false;
        } catch (SignatureException e) {
            log.warn("JWT signature 验证失败: {}", e.getMessage());
            return false;
        } catch (IllegalArgumentException e) {
            log.warn("JWT claims 为空: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 获取 JWT Token 的过期时间
     */
    public long getExpirationTime() {
        return jwtExpirationMs;
    }

    /**
     * 将 JWT Token 加入黑名单（注销）。
     *
     * <p>把 token 做 SHA-256 后再当 Redis key，避免把明文凭证留在 Redis 里；
     * TTL 取「剩余有效期」而不是固定值，否则被注销的 token 可能比自身有效期活得更久。
     *
     * @return true 表示已成功注销；false 表示 token 已过期/无法解析，无需注销
     */
    public boolean blacklistToken(String token) {
        try {
            long remainingMs = getRemainingValidityMs(token);
            if (remainingMs <= 0) {
                return false;
            }
            redisTemplate.opsForValue().set(blacklistKey(token), "1", Duration.ofMillis(remainingMs));
            return true;
        } catch (Exception e) {
            log.warn("无法注销 Token：{}", e.getMessage());
            return false;
        }
    }

    /**
     * Token 是否已被注销（在 Redis 黑名单中）。
     */
    public boolean isBlacklisted(String token) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(blacklistKey(token)));
        } catch (Exception e) {
            // Redis 不可用时不能把请求直接判死，但要留下明确告警
            log.error("检查 JWT 黑名单失败（Redis 不可用？）：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 用未过期的 Token 换取新 Token（刷新）。
     *
     * <p>策略：
     * <ul>
     *     <li>已被注销的 Token 不能刷新；</li>
     *     <li>必须仍可验签且未过期；</li>
     *     <li>签发时间距现在不得超过 {@code app.jwtRefreshExpirationMs}（会话窗口，默认 24h），
     *         超过则必须重新登录；</li>
     *     <li>刷新即轮换：旧 Token 立刻进黑名单，角色声明原样保留。</li>
     * </ul>
     * 后续如需更强的方案，可在此基础上引入独立的 refresh token（独立 TTL + 一机一密）。
     */
    public String refreshAccessToken(String oldToken) {
        if (isBlacklisted(oldToken)) {
            throw new JwtException("Token 已被注销，无法刷新");
        }
        if (!validateToken(oldToken)) {
            throw new JwtException("Token 无效或已过期，请重新登录");
        }

        Claims claims = parseClaims(oldToken);
        Date issuedAt = claims.getIssuedAt();
        if (issuedAt != null && System.currentTimeMillis() - issuedAt.getTime() > jwtRefreshExpirationMs) {
            throw new JwtException("Token 已超出可刷新窗口，请重新登录");
        }

        @SuppressWarnings("unchecked")
        java.util.Collection<String> roles = (java.util.Collection<String>) claims.get("authorities");
        if (roles == null) {
            roles = java.util.Collections.emptyList();
        }

        String newToken = generateTokenFromUsername(claims.getSubject(), roles);
        blacklistToken(oldToken); // 轮换：旧 token 立即失效
        return newToken;
    }

    /**
     * Token 剩余有效期（毫秒），已过期返回负数。
     */
    public long getRemainingValidityMs(String token) {
        return parseClaims(token).getExpiration().getTime() - System.currentTimeMillis();
    }

    private String blacklistKey(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return BLACKLIST_PREFIX + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("无法计算 Token 摘要", e);
        }
    }

    public Map<String, Object> getClaims(String token) {
        return parseClaims(token);
    }

    private Claims parseClaims(String token) {
        SecretKey key = signingKey();
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
