ALTER TABLE telegram_updates
    ADD COLUMN payload_json JSON NULL AFTER update_id,
    MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'RECEIVED';
