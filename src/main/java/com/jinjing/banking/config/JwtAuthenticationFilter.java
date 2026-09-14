package com.jinjing.banking.config;

import com.jinjing.banking.modules.account.service.CustomUserDetailsService;
import com.jinjing.banking.modules.account.util.JwtUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.stream.Collectors;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * JWT 认证过滤器
 * 在每次请求中检查并验证 JWT Token，并拦截已注销（Redis 黑名单）的 Token。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** login/register/refresh/logout 这些端点自己解析 Token，过滤器只负责填充上下文，不直接 401 */
    private static final String AUTH_PATH_PREFIX = "/api/auth/";

    private final JwtUtils jwtUtils;
    private final CustomUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {

        String token = extractTokenFromRequest(request);

        if (token != null && !authenticate(request, response, token)) {
            return; // 已经写入 401，不再继续过滤链
        }

        // 继续处理请求链
        filterChain.doFilter(request, response);
    }

    /**
     * 校验并填充 SecurityContext。
     *
     * @return true 表示可以继续过滤器链；false 表示已直接返回 401
     */
    private boolean authenticate(HttpServletRequest request, HttpServletResponse response, String token)
        throws IOException {

        // 注销检查必须放在最前面：黑名单里的 Token 即使签名有效也不能再用
        if (jwtUtils.isBlacklisted(token)) {
            log.warn("Token 已被注销，拒绝访问 {} {}", request.getMethod(), request.getRequestURI());
            return rejectOrSkip(request, response, "Token 已被注销，请重新登录");
        }

        if (!jwtUtils.validateToken(token)) {
            log.debug("Token 无效或已过期: {} {}", request.getMethod(), request.getRequestURI());
            return rejectOrSkip(request, response, "Token 无效或已过期");
        }

        try {
            String username = jwtUtils.getUsernameFromToken(token);
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);

            // 解析 JWT 中的 authorities claim（角色）
            Collection<SimpleGrantedAuthority> authorities = java.util.Collections.emptyList();
            try {
                java.util.Map<String, Object> claims = jwtUtils.getClaims(token);
                Object authClaim = claims.get("authorities");
                if (authClaim instanceof java.util.Collection) {
                    authorities = ((java.util.Collection<?>) authClaim).stream()
                            .map(Object::toString)
                            .map(SimpleGrantedAuthority::new)
                            .collect(Collectors.toList());
                }
            } catch (Exception ex) {
                log.warn("无法从 Token 解析 authorities，使用 UserDetails 默认权限");
                authorities = userDetails.getAuthorities().stream()
                        .map(a -> new SimpleGrantedAuthority(a.getAuthority()))
                        .collect(Collectors.toList());
            }

            // 创建认证 token
            UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                    userDetails, null, authorities);

            // 设置请求详情（用于日志和审计）
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            // 将认证放入 SecurityContext
            SecurityContextHolder.getContext().setAuthentication(authentication);

            log.debug("JWT Token 验证成功，用户: {}", username);
            return true;
        } catch (Exception e) {
            // 例如用户已被删除/匿名化，Token 里的用户名已不存在
            log.error("JWT Token 处理过程中出错: {}", e.getMessage());
            SecurityContextHolder.clearContext();
            return rejectOrSkip(request, response, "Token 认证失败，请重新登录");
        }
    }

    /**
     * 受保护接口直接返回 401 JSON；{@code /api/auth/**} 交给控制器自行判断
     * （login/register 可能被客户端带上了过期 Token，不能因此拒绝登录）。
     */
    private boolean rejectOrSkip(HttpServletRequest request, HttpServletResponse response, String message)
        throws IOException {

        SecurityContextHolder.clearContext();

        if (request.getRequestURI().startsWith(AUTH_PATH_PREFIX)) {
            return true;
        }

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"status\":401,\"message\":\"" + message + "\"}");
        return false;
    }

    /**
     * 从 HTTP 请求的 Authorization header 中提取 Bearer Token
     * 格式: Authorization: Bearer <token>
     */
    private String extractTokenFromRequest(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");

        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7); // 移除 "Bearer " 前缀
        }

        return null;
    }
}
