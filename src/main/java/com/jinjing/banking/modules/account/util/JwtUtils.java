package com.jinjing.banking.modules.account.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * JWT Token 工具类
 * 负责生成、验证和解析 JWT Token
 */
@Slf4j
@Component
public class JwtUtils {

    @Value("${app.jwtSecret:your-super-secret-key-change-this-in-production-at-least-256-bits}")
    private String jwtSecret;

    @Value("${app.jwtExpirationMs:3600000}")
    private long jwtExpirationMs;

    private final StringRedisTemplate redisTemplate;

    public JwtUtils(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
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
     */
    public String generateTokenFromUsername(String username, java.util.Collection<String> roles) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpirationMs);

        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());

        return Jwts.builder()
            .subject(username)
            .claim("authorities", roles)
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(key, SignatureAlgorithm.HS512)
            .compact();
    }

    /**
     * 从 JWT Token 中提取用户名
     */
    public String getUsernameFromToken(String token) {
        try {
            SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());
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
            SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());
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
     * 将 JWT Token 添加到黑名单
     */
    public void addTokenToBlacklist(String token) {
        redisTemplate.opsForValue().set(token, "blacklist", jwtExpirationMs);
    }

    /**
     * 检查 JWT Token 是否在黑名单中
     */
    public boolean isTokenInBlacklist(String token) {
        return redisTemplate.hasKey(token);
    }

    public String refreshToken(String oldToken) {
        if (isTokenInBlacklist(oldToken)) {
            throw new JwtException("Token 已被注销");
        }
        String username = getUsernameFromToken(oldToken);
        Map<String, Object> claims = getClaims(oldToken);
        @SuppressWarnings("unchecked")
        java.util.Collection<String> roles = (java.util.Collection<String>) claims.get("authorities");
        if (roles == null) {
            roles = java.util.Collections.emptyList();
        }
        return generateTokenFromUsername(username, roles);
    }

    public Map<String, Object> getClaims(String token) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
