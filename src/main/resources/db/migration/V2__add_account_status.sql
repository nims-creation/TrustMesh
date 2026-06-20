-- V2: Add account lifecycle status fields (real-bank soft-delete pattern)
-- ACTIVE  = open account, can send/receive
-- CLOSED  = permanently closed; data preserved for RBI audit trail

ALTER TABLE accounts ADD COLUMN IF NOT EXISTS status       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS closed_at    TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS close_reason VARCHAR(500);
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS created_at   TIMESTAMP(6) WITH TIME ZONE;

-- Backfill existing accounts as ACTIVE
UPDATE accounts SET status = 'ACTIVE' WHERE status IS NULL OR status = '';

-- Index for quick filtering of active-only accounts
CREATE INDEX IF NOT EXISTS idx_accounts_status ON accounts(status);
