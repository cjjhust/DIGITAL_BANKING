CREATE TABLE IF NOT EXISTS processed_transactions (
    transaction_id VARCHAR(255) PRIMARY KEY,
    client_request_id VARCHAR(255) NOT NULL UNIQUE,
    from_account_no VARCHAR(255) NOT NULL,
    to_account_no VARCHAR(255) NOT NULL,
    amount DECIMAL(38,2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    error_message VARCHAR(255)
);
