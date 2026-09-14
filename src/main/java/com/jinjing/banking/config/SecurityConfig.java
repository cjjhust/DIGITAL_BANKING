package com.jinjing.banking.config;

import com.jinjing.banking.modules.account.service.CustomUserDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置
 * 定义认证、授权、会话管理等安全策略
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(
    securedEnabled = true,
    jsr250Enabled = true,
    prePostEnabled = true  // 启用 @PreAuthorize, @PostAuthorize 等注解
)
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * 是否放行 Swagger / OpenAPI 端点。
     * 默认关闭：接口文档会暴露全部 API 结构，生产环境不应对外开放；
     * 本地开发在 application.yml 里打开，生产在 application-prod.yml 里保持 false。
     */
    @Value("${app.security.expose-api-docs:false}")
    private boolean exposeApiDocs;

    /**
     * 定义密码编码器
     * 使用 BCryptPasswordEncoder 进行密码加密和验证
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 定义认证提供者
     * 连接 UserDetailsService 和 PasswordEncoder
     */
    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        // Spring Security 6+: 必须通过构造器注入 UserDetailsService
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    /**
     * 定义认证管理器
     * 负责处理用户名/密码的认证
     */
    @Bean
    public AuthenticationManager authenticationManager(HttpSecurity http) throws Exception {
        AuthenticationManagerBuilder builder = http.getSharedObject(AuthenticationManagerBuilder.class);
        builder.authenticationProvider(authenticationProvider());
        return builder.build();
    }

    /**
     * 定义安全过滤链
     * 配置哪些 URL 允许匿名访问，哪些需要认证
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // 禁用 CSRF 保护（REST API 通常不需要，且你已经有 JWT）
            .csrf(csrf -> csrf.disable())

            // 设置会话管理为无状态（使用 JWT，不需要 Session）
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // 配置请求授权规则
            .authorizeHttpRequests(authz -> authz
                // 允许匿名访问的端点
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/public/**").permitAll()
                .requestMatchers("/", "/index.html", "/favicon.ico").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/actuator/prometheus").permitAll()

                // Swagger：仅当 app.security.expose-api-docs=true 时放行（默认关闭）
                .requestMatchers(exposeApiDocs
                        ? new String[]{"/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**"}
                        : new String[0]).permitAll()

                // 任何其他请求都需要认证
                .anyRequest().authenticated()
            )

            // 添加 JWT 认证过滤器
            // 在 UsernamePasswordAuthenticationFilter 之前执行，确保 JWT token 在标准认证前被处理
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
