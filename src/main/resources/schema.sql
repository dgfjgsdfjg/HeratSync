CREATE TABLE IF NOT EXISTS message (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id  TEXT    NOT NULL,
    role     TEXT    NOT NULL,   -- user / ai
    content  TEXT    NOT NULL,
    ts       INTEGER NOT NULL    -- epoch millis
);
CREATE INDEX IF NOT EXISTS idx_message_user ON message(user_id, id);
