-- 添加 trace_parent 字段到 outbox_events 表
-- 用于兼容 W3C 标准的完整追踪上下文

ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS trace_parent VARCHAR(100);

-- 添加索引以提高查询性能
CREATE INDEX IF NOT EXISTS idx_outbox_trace_id ON outbox_events(trace_id);
CREATE INDEX IF NOT EXISTS idx_outbox_span_id ON outbox_events(span_id);