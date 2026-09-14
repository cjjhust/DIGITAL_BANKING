CREATE TABLE IF NOT EXISTS accounts (
    id BIGSERIAL PRIMARY KEY,
    owner_name VARCHAR(100) NOT NULL,
    account_number VARCHAR(50) UNIQUE NOT NULL,
    balance DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_accounts_account_number ON accounts(account_number);
CREATE INDEX IF NOT EXISTS idx_accounts_owner_name ON accounts(owner_name);