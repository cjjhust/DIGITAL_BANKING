-- Keep the database schema aligned with the JPA entities after the initial migrations.
DO $$
BEGIN
	IF EXISTS (
		SELECT 1 FROM information_schema.columns
		WHERE table_name = 'users' AND column_name = 'password_hash'
	) AND NOT EXISTS (
		SELECT 1 FROM information_schema.columns
		WHERE table_name = 'users' AND column_name = 'password'
	) THEN
		ALTER TABLE users RENAME COLUMN password_hash TO password;
	END IF;
END $$;

ALTER TABLE users ADD COLUMN IF NOT EXISTS password VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS version BIGINT;
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS trace_parent VARCHAR(255);
