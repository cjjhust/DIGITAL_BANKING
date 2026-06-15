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

    @Bean
    public NamedParameterJdbcTemplate clickHouseJdbcTemplate() throws SQLException {
        Properties props = new Properties();
        // 生产环境建议：显式设置超时，防止网络抖动导致应用线程阻塞
        props.setProperty("socket_timeout", "30000");
        props.setProperty("connection_timeout", "5000");
        
        DataSource ds = new ClickHouseDataSource(url, props);
        return new NamedParameterJdbcTemplate(ds);
    }
}