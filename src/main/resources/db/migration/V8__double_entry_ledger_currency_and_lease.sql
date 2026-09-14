-- V8：复式记账 + 币种 + PENDING 租约
--
-- 背景：在此之前的"账本"只有 accounts.balance 一列可变余额，没有任何分录。
-- 这在会计意义上不成立：无法回答"这笔余额是由哪些分录加总出来的"，也无法发现
-- "钱扣了但没入账"这类单边记账错误。这里补上真正的复式记账。

-- ---------------------------------------------------------------------------
-- 1) 账户币种（历史数据默认 EUR）
-- ---------------------------------------------------------------------------
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS currency VARCHAR(3) NOT NULL DEFAULT 'EUR';

-- ---------------------------------------------------------------------------
-- 2) 复式记账分录表
--    任何一笔转账都写两条：付款方 DEBIT（减少）、收款方 CREDIT（增加）。
--    两条金额必须相等 —— 这是"复式"的全部含义，也是可对账的基础。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ledger_entries (
    id             BIGSERIAL    PRIMARY KEY,
    transaction_id VARCHAR(255) NOT NULL,
    account_no     VARCHAR(255) NOT NULL,
    direction      VARCHAR(6)   NOT NULL,
    amount         NUMERIC(38,2) NOT NULL,
    currency       VARCHAR(3)   NOT NULL DEFAULT 'EUR',
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_ledger_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_ledger_amount_positive CHECK (amount > 0)
);

-- 借贷两笔必须成对：同一交易 + 同一账户 + 同一方向只能有一条
CREATE UNIQUE INDEX IF NOT EXISTS uq_ledger_tx_account_direction
    ON ledger_entries (transaction_id, account_no, direction);
CREATE INDEX IF NOT EXISTS idx_ledger_account ON ledger_entries (account_no);
CREATE INDEX IF NOT EXISTS idx_ledger_currency ON ledger_entries (currency);

-- ---------------------------------------------------------------------------
-- 3) PENDING 租约：多实例部署时，只有一个实例能"认领"某条超时 PENDING 交易
--    没有租约时，N 个实例的定时任务会同时扫到同一批记录，重复告警、重复补偿。
-- ---------------------------------------------------------------------------
ALTER TABLE processed_transactions ADD COLUMN IF NOT EXISTS lease_owner VARCHAR(120);
ALTER TABLE processed_transactions ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_processed_lease
    ON processed_transactions (status, lease_expires_at);
