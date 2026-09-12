package com.jinjing.banking;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@SpringBootTest
public class BankingApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
        assertThat(kafkaTemplate).isNotNull();
    }

    @Test
    void testClickhouseConnection() {
        try {
            Map<String, Object> result = jdbcTemplate.queryForMap("SELECT version() as v");
            System.out.println("Connected to ClickHouse, version: " + result.get("v"));
            assertThat(result).containsKey("v");
        } catch (Exception e) {
            System.err.println("Failed to connect to ClickHouse or query: " + e.getMessage());
            e.printStackTrace();
            fail("ClickHouse connection or query failed");
        }
    }
}
