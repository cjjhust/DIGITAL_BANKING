CREATE TABLE IF NOT EXISTS outbox_events (
    id BIGSERIAL PRIMARY KEY,
    aggregate_id VARCHAR(50) NOT NULL,
    client_request_id VARCHAR(100) NOT NULL,
    trace_id VARCHAR(50),
    span_id VARCHAR(50),
    topic VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_outbox_client_request_id ON outbox_events(client_request_id);
CREATE INDEX idx_outbox_topic ON outbox_events(topic);
CREATE INDEX idx_outbox_status ON outbox_events(status);