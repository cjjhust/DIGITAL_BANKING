-- 一次性数据修复：把被旧版 DlqListener 误改写成 FAILED 的转账恢复为 COMPLETED。
--
-- 背景（2026-09-12）：
--   旧版 DlqListener 收到死信后无条件调用 markAsFailed。而 `banking-transfers` 这个 topic
--   被两个消费者组订阅：
--     - banking-group   → 账本（成功）
--     - analytics-group → ClickHouse 审计（当时因驱动不兼容 + 缺凭证而失败）
--   审计失败的消息也会进死信，于是「已经成功入账」的转账被改写成了 FAILED。
--
-- 判定依据（可用 kafka-console-consumer 复核）：
--   下面这 6 个 transactionId 对应的死信，`kafka_dlt-exception-message` 全部指向
--   `com.jinjing.banking.modules.analytics.consumer.TransactionAnalyticsConsumer`，
--   即失败发生在审计侧，账本侧成功 → 状态应为 COMPLETED。
--   新代码已加守卫（只有 PENDING 才允许改 FAILED），不会再产生此类脏数据。
--
-- 注：这 6 笔都是同一账户的自转账（from = to），余额净变化为 0，因此无需调整余额。

\echo '--- 修复前 ---'
SELECT transaction_id, client_request_id, status, error_message
FROM processed_transactions
WHERE transaction_id IN (
    '357132787171463168', '357136308377751552', '357136594139877376',
    '357136857009491968', '357137197255626752', '357137692074446849'
)
ORDER BY created_at;

UPDATE processed_transactions
SET status = 'COMPLETED',
    error_message = NULL
WHERE transaction_id IN (
    '357132787171463168', '357136308377751552', '357136594139877376',
    '357136857009491968', '357137197255626752', '357137692074446849'
)
  AND error_message = 'Exhausted retries in Kafka, moved to DLQ'  -- 只改被死信兜底误写的行
  AND status = 'FAILED';                                          -- 幂等：重复执行不会二次改动

\echo '--- 修复后 ---'
SELECT transaction_id, client_request_id, status, error_message
FROM processed_transactions
WHERE transaction_id IN (
    '357132787171463168', '357136308377751552', '357136594139877376',
    '357136857009491968', '357137197255626752', '357137692074446849'
)
ORDER BY created_at;
