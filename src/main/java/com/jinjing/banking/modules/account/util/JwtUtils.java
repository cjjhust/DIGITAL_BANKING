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

    /**
     * 从 Authentication 对象生成 JWT Token
     */
    public String generateToken(Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        return generateTokenFromUsername(userDetails.getUsername());
    }

    /**
     * 从用户名生成 JWT Token
     */
    public String generateTokenFromUsername(String username) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpirationMs);

        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());

        return Jwts.builder()
            .subject(username)
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
            Claims claims = Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
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
     * 返回 true 表示 token 有效且未过期
     */
    public boolean validateToken(String token) {
        try {
            SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());
            Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token);
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
}
