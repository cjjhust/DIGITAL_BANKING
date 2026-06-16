package com.jinjing.banking.config;

import com.jinjing.banking.modules.account.service.CustomUserDetailsService;
import com.jinjing.banking.modules.account.util.JwtUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器
 * 在每次请求中检查并验证 JWT Token
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final CustomUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {

        try {
            String token = extractTokenFromRequest(request);

            // 如果成功提取到 token，则进行验证和身份认证
            if (token != null && jwtUtils.validateToken(token)) {
                String username = jwtUtils.getUsernameFromToken(token);
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);

                // 创建认证 token
                UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());

                // 设置请求详情（用于日志和审计）
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                // 将认证放入 SecurityContext
                SecurityContextHolder.getContext().setAuthentication(authentication);

                log.debug("JWT Token 验证成功，用户: {}", username);
            }
        } catch (Exception e) {
            // 改进点：对于金融应用，明确的错误原因能提升用户体验和调试效率
            // 虽然目前只是记录日志，但在严苛模式下，这里可以直接写回 JSON 响应
            log.error("JWT Token 处理过程中出错: {}", e.getMessage());
            // 如果需要拦截非法 Token 并直接返回：
            // response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or Expired Token");
            // return;
        }

        // 继续处理请求链
        filterChain.doFilter(request, response);
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
