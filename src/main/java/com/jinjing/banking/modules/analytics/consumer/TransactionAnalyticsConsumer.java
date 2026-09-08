package  com.jinjing.banking.modules.analytics.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
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
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final ObservationRegistry observationRegistry;

    /**
     * 监听交易主题，将数据沉淀到 ClickHouse
     * 借助 Java 21 虚拟线程，这个消费者即使遇到 ClickHouse 写入波峰也能轻松应对
     */
    @KafkaListener(topics = "banking-transfers", groupId = "analytics-group")
    public void consumeForAudit(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_KEY) String transactionId,
            @Header(KafkaHeaders.RECEIVED_TIMESTAMP) long timestamp) {

        Observation.createNotStarted("analytics.audit", observationRegistry)
            .observe(() -> {
                try {
                    TransferRequest request = objectMapper.readValue(message, TransferRequest.class);
                    LocalDateTime createdAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());

                    clickHouseJdbcTemplate.update(INSERT_AUDIT_SQL, Map.of(
                        "tid", transactionId,
                        "cid", request.getRequestId(),
                        "from", request.getFromAccountNo(),
                        "to", request.getToAccountNo(),
                        "amount", request.getAmount(),
                        "trace", getTraceId(),
                        "span", getSpanId(),
                        "ts", createdAt
                    ));
                } catch (JsonProcessingException e) {
                    log.error("Invalid message format for transaction: {}", transactionId, e);
                } catch (Exception e) {
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
