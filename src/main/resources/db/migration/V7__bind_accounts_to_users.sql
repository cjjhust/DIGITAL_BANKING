-- 账户归属：把 accounts 绑定到 users，作为所有账户级接口的授权依据。
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS user_id BIGINT;

-- 外键约束（PostgreSQL 不支持 ADD CONSTRAINT IF NOT EXISTS，用 DO 块保证可重复执行）
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_name = 'accounts' AND constraint_name = 'fk_accounts_user'
    ) THEN
        ALTER TABLE accounts
            ADD CONSTRAINT fk_accounts_user FOREIGN KEY (user_id) REFERENCES users (id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_accounts_user_id ON accounts (user_id);

-- 历史数据回填：早期账户用 owner_name 存用户名（见 scripts/batch_transfer_demo.py 的建户逻辑）
UPDATE accounts a
SET user_id = u.id
FROM users u
WHERE a.user_id IS NULL
  AND u.username = a.owner_name;
