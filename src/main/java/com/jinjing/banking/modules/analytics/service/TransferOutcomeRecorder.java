package com.jinjing.banking.modules.analytics.service;

import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 把转账的<b>最终结果</b>写入 ClickHouse 的 {@code banking_analytics.transaction_outcome}。
 *
 * <h3>为什么需要这张表</h3>
 * {@code transaction_audit} 由旁路分析消费者在「收到 Kafka 事件」时写入，与业务成败无关 ——
 * 被拒绝的转账（余额不足、自转账、超限）同样会在审计表里留一行。
 * 因此审计表回答不了「今天成功多少、失败多少、为什么失败」，那需要终态数据。
 *
 * <h3>为什么不在审计表上加 status 列</h3>
 * 分析消费者拿到消息时<b>结果尚未产生</b>：状态是账本消费者自己算出来后才有的。
 * 若强行在审计表上就地更新（ClickHouse 的 mutation 会异步重写整个 part，代价高、非即时），
 * 等于用一个昂贵且语义混乱的方案去解决一个本该由「追加一张表」解决的问题。
 *
 * <h3>为什么保留两张表</h3>
 * 两表行数之差（audit − outcome）= 「事件进了 Kafka 但账本没算出结果」的量，也就是 DLT 兜底的量，
 * 这个差值本身就是监控指标。合并成一张表会把它掩盖掉。
 *
 * <h3>失败隔离</h3>
 * 分析侧不可用<b>绝不能</b>影响主交易链路。所有异常在此吞掉并只记 ERROR 日志。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransferOutcomeRecorder {

    private static final String INSERT_OUTCOME_SQL = """
            INSERT INTO banking_analytics.transaction_outcome
            (transaction_id, client_request_id, from_account, to_account, amount,
             status, error_message, trace_id, span_id, settled_at)
            VALUES (:tid, :cid, :fromAccount, :toAccount, :amount,
                    :status, :errorMessage, :traceId, :spanId, :settledAt)
            """;

    private final NamedParameterJdbcTemplate clickHouseJdbcTemplate;
    private final Tracer tracer;

    /**
     * 记录一笔转账的终态。幂等由调用方保证（同一 transactionId 只在状态跃迁时调用一次）。
     *
     * @param status {@code COMPLETED} / {@code FAILED} / {@code DLQ}
     * @param errorMessage 失败原因，成功时传 {@code null}
     */
    public void record(String transactionId,
                       String clientRequestId,
                       String fromAccount,
                       String toAccount,
                       BigDecimal amount,
                       String status,
                       String errorMessage) {
        try {
            // 用 HashMap 而不是 Map.of：Map.of 不接受 null，而 ClickHouse 的 String 列
            // 也不接受 SQL NULL，统一归一化成空串。
            Map<String, Object> params = new HashMap<>();
            params.put("tid", nz(transactionId));
            params.put("cid", nz(clientRequestId));
            params.put("fromAccount", nz(fromAccount));
            params.put("toAccount", nz(toAccount));
            params.put("amount", amount == null ? BigDecimal.ZERO : amount);
            params.put("status", nz(status));
            params.put("errorMessage", nz(errorMessage));
            params.put("traceId", traceId());
            params.put("spanId", spanId());
            params.put("settledAt", LocalDateTime.now());

            int rows = clickHouseJdbcTemplate.update(INSERT_OUTCOME_SQL, params);
            log.info("ClickHouse outcome persisted for transaction {} (status={}, rows={})",
                    transactionId, status, rows);
        } catch (Exception e) {
            // 绝不让分析侧故障冒泡到交易主链路。
            // 注意：必须打完整堆栈 —— Spring 的 SQL 异常翻译器会把底层原因替换成
            // 泛化的 "PreparedStatementCallback; bad SQL grammar [...]"，只打 getMessage()
            // 会把真正的 cause（驱动/网络/服务端错误）永久丢失。
            log.error("ClickHouse outcome write failed for transaction {} (status={})",
                    transactionId, status, e);
        }
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private String traceId() {
        return Optional.ofNullable(tracer.currentSpan())
                .map(span -> span.context().traceId())
                .orElse("");
    }

    private String spanId() {
        return Optional.ofNullable(tracer.currentSpan())
                .map(span -> span.context().spanId())
                .orElse("");
    }
}
