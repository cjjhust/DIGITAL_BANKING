CREATE TABLE IF NOT EXISTS processed_transactions (
    transaction_id VARCHAR(100) PRIMARY KEY,
    client_request_id VARCHAR(100) NOT NULL UNIQUE,
    from_account_no VARCHAR(50) NOT NULL,
    to_account_no VARCHAR(50) NOT NULL,
    amount DECIMAL(19, 4) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    error_message TEXT
);

CREATE INDEX IF NOT EXISTS idx_processed_transactions_status
    ON processed_transactions(status);
CREATE INDEX IF NOT EXISTS idx_processed_transactions_created_at
    ON processed_transactions(created_at);