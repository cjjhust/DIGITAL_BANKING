package com.jinjing.banking.config;

import com.clickhouse.jdbc.ClickHouseDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.sql.SQLException;

import javax.sql.DataSource;
import java.util.Properties;

@Configuration
public class ClickHouseConfig {

    @Value("${spring.clickhouse.jdbc-url}")
    private String url;

    @Value("${spring.clickhouse.username:}")
    private String username;

    @Value("${spring.clickhouse.password:}")
    private String password;

    @Bean
    public NamedParameterJdbcTemplate clickHouseJdbcTemplate() throws SQLException {
        Properties props = new Properties();
        // 生产环境建议：显式设置超时，防止网络抖动导致应用线程阻塞
        props.setProperty("socket_timeout", "30000");
        props.setProperty("connection_timeout", "5000");

        // 必须把账号密码传给驱动：否则会以 default 用户连接，
        // 在设置了 CLICKHOUSE_USER 的环境中直接报
        // "Code: 194 ... Authentication failed: password is incorrect, or there is no user with such name"，
        // 表现为审计数据一行都写不进去，但错误只出现在消费者线程日志里。
        if (username != null && !username.isBlank()) {
            props.setProperty("user", username);
        }
        if (password != null && !password.isBlank()) {
            props.setProperty("password", password);
        }

        // 关闭响应压缩：clickhouse-jdbc 0.6.3 与较新的 clickhouse-server 在压缩协商上不兼容，
        // 读取响应时会抛 "Magic is not correct - expect [-126] but got [-43]"（Lz4InputStream）。
        // 审计写入量不大，牺牲压缩换取可用性是划算的；若升级驱动后可去掉这两行。
        props.setProperty("compress", "false");
        props.setProperty("decompress", "false");

        DataSource ds = new ClickHouseDataSource(url, props);
        return new NamedParameterJdbcTemplate(ds);
    }
}