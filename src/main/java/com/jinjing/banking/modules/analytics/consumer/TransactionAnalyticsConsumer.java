package  com.jinjing.banking.modules.analytics.consumer;

import com.jinjing.banking.config.KafkaTopics;
import com.jinjing.banking.modules.account.dto.TransferRequest;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Map;
/**
 * 交易分析消费者，负责监听交易主题，将数据沉淀到 ClickHouse 以供后续分析使用。
 * 通过 KafkaListener 注解实现消息消费，利用 Java 21 虚拟线程轻松应对高并发场景。
 *
 * <p><b>注意 payload 类型必须是 TransferRequest 而不是 String。</b>
 * 本项目的监听容器统一使用 {@code JacksonJsonMessageConverter}，
 * 把 JSON 对象反序列化成 {@code String} 会抛
 * {@code MismatchedInputException: Cannot deserialize value of type java.lang.String from Object value}，
 * 结果每条消息都失败并被投进死信主题（ClickHouse 一行都不会写入）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TransactionAnalyticsConsumer {

    private static final String INSERT_AUDIT_SQL = """
            INSERT INTO transaction_audit (transaction_id, client_request_id, from_account, to_account, amount, trace_id, span_id, created_at)
            VALUES (:tid, :cid, :from, :to, :amount, :trace, :span, :ts)
            """;

    private final NamedParameterJdbcTemplate clickHouseJdbcTemplate;
    private final Tracer tracer;
    private final ObservationRegistry observationRegistry;

    /**
     * 监听交易主题，将数据沉淀到 ClickHouse
     * 借助 Java 21 虚拟线程，这个消费者即使遇到 ClickHouse 写入波峰也能轻松应对
     *
     * <p>用 {@code analyticsListenerContainerFactory}：失败后进的是独立审计死信主题，
     * 不会和账本死信混在一起（账本死信需要人工介入，审计死信可以重放或丢弃）。
     */
    @KafkaListener(topics = KafkaTopics.TRANSFER, groupId = "analytics-group",
            containerFactory = "analyticsListenerContainerFactory")
    public void consumeForAudit(
            @Payload TransferRequest request,
            @Header(KafkaHeaders.RECEIVED_KEY) String transactionId,
            @Header(KafkaHeaders.RECEIVED_TIMESTAMP) long timestamp) {

        Observation.createNotStarted("analytics.audit", observationRegistry)
            .observe(() -> {
                try {
                    LocalDateTime createdAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());

                    int inserted = clickHouseJdbcTemplate.update(INSERT_AUDIT_SQL, Map.of(
                        "tid", transactionId,
                        "cid", request.getRequestId(),
                        "from", request.getFromAccountNo(),
                        "to", request.getToAccountNo(),
                        "amount", request.getAmount(),
                        "trace", getTraceId(),
                        "span", getSpanId(),
                        "ts", createdAt
                    ));
                    log.info("ClickHouse audit persisted for transaction {} (rows={})", transactionId, inserted);
                } catch (Exception e) {
                    // ClickHouse 侧失败不应把消息留在主链路里重试到死，但要留下告警
                    log.error("ClickHouse Audit Failed for transaction: {}", transactionId, e);
                    throw new RuntimeException(e);
                }
            });
    }

    private String getTraceId() {
        return Optional.ofNullable(tracer.currentSpan())
                .map(span -> span.context().traceId())
                .orElse("N/A");
    }

    private String getSpanId() {
        return Optional.ofNullable(tracer.currentSpan())
                .map(span -> span.context().spanId())
                .orElse("N/A");
    }
}
