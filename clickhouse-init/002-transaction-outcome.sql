-- 终态结算表（2026-09-14 新增）
--
-- 与 transaction_audit 语义不同，因此分两张表而不是给审计表加一列：
--
--   transaction_audit   = 「分析消费者**收到**了多少事件」
--                         写入者：TransactionAnalyticsConsumer（旁路消费者，收到即写）
--                         用途  ：测管道缺口（审计行数 vs 账本行数 = 进 DLQ / 未被消费的量）
--
--   transaction_outcome = 「账本**算出**的最终结果」
--                         写入者：账本消费者在终态时写（TransferOutcomeRecorder）
--                         用途  ：成功率、失败原因分布
--
-- 为什么不在 MergeTree 上做 UPDATE / ALTER ... UPDATE：
--   ClickHouse 的 mutation 会异步重写整个 part，代价高且不保证即时可见。
--   用「追加一张终态表」替代「就地更新」是列式库的标准做法。
--
-- 为什么不合并成一张表：
--   两表行数之差（audit 数 - outcome 数）本身就是指标 —— 它等于「事件进了 Kafka
--   但账本没算出结果」的数量，也就是 DLT 兜底的量。合并后会掩盖这个缺口。
CREATE TABLE IF NOT EXISTS banking_analytics.transaction_outcome (
    transaction_id    String,
    client_request_id String,
    from_account      String,
    to_account        String,
    amount            Decimal(18, 4),
    status            String,                    -- COMPLETED / FAILED / DLQ
    error_message     String,
    trace_id          String,
    span_id           String,
    settled_at        DateTime64(3, 'UTC')
) ENGINE = MergeTree()
ORDER BY (settled_at, transaction_id);
