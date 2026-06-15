CREATE TABLE IF NOT EXISTS banking_analytics.transaction_audit (
    transaction_id String,
    client_request_id String,
    from_account String,
    to_account String,
    amount Decimal(18, 4),
    trace_id String,
    span_id String,
    created_at DateTime64(3, 'UTC')
) ENGINE = MergeTree()
ORDER BY (created_at, transaction_id);
