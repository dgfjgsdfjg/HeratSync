CREATE TABLE IF NOT EXISTS message (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id  TEXT    NOT NULL,
    role     TEXT    NOT NULL,   -- user / ai
    content  TEXT    NOT NULL,
    ts       INTEGER NOT NULL    -- epoch millis
);
CREATE INDEX IF NOT EXISTS idx_message_user ON message(user_id, id);

-- 事件（有时间点的事——明天爬山、第一次约会、吵架纪念日）
CREATE TABLE IF NOT EXISTS event (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id    TEXT    NOT NULL,
    title      TEXT    NOT NULL,              -- 事件标题
    event_date TEXT    NOT NULL,              -- YYYY-MM-DD
    content    TEXT,                          -- 补充描述
    created_ts INTEGER NOT NULL               -- epoch millis
);
CREATE INDEX IF NOT EXISTS idx_event_user_date ON event(user_id, event_date);
