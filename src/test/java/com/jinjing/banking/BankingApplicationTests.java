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

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail; // Added for test failure

@SpringBootTest // 加载整个 Spring Boot 应用上下文
@Testcontainers
public class BankingApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:latest"));

    @Container
    @ServiceConnection
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    @ServiceConnection
    static ClickHouseContainer clickhouse = new ClickHouseContainer("clickhouse/clickhouse-server:latest");


    @Autowired
    private ApplicationContext context;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate; // 注入真实的 KafkaTemplate

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
