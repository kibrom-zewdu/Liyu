CREATE TABLE admin_conversations (
    telegram_user_id BIGINT NOT NULL,
    conversation_type VARCHAR(32) NOT NULL,
    current_step INT NOT NULL DEFAULT 0,
    state_data JSON NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (telegram_user_id),
    KEY ix_admin_conversations_expiry (updated_at),
    CONSTRAINT chk_admin_conversation_step CHECK (current_step >= 0)
) ENGINE=InnoDB;
