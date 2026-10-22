CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    telegram_user_id BIGINT NOT NULL,
    telegram_username VARCHAR(64) NULL,
    display_name VARCHAR(160) NULL,
    language_code CHAR(2) NULL,
    account_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_interaction_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_users_telegram_id (telegram_user_id),
    CONSTRAINT chk_users_language CHECK (language_code IS NULL OR language_code IN ('en','am','ti'))
) ENGINE=InnoDB;

CREATE TABLE campaigns (
    id BIGINT NOT NULL AUTO_INCREMENT,
    campaign_code VARCHAR(40) NOT NULL,
    ticket_price DECIMAL(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    sales_start_at TIMESTAMP(6) NULL,
    sales_close_at TIMESTAMP(6) NULL,
    draw_at TIMESTAMP(6) NULL,
    ticket_limit BIGINT NULL,
    per_user_limit INT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    promotional_image_file_id VARCHAR(255) NULL,
    created_by_admin_id BIGINT NULL,
    updated_by_admin_id BIGINT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id), UNIQUE KEY uk_campaigns_code (campaign_code), KEY ix_campaigns_status_dates (status,sales_start_at,sales_close_at),
    CONSTRAINT chk_campaign_price CHECK (ticket_price >= 0),
    CONSTRAINT chk_campaign_status CHECK (status IN ('DRAFT','SCHEDULED','OPEN','CLOSED','DRAW_PENDING','COMPLETED','CANCELLED'))
) ENGINE=InnoDB;

CREATE TABLE campaign_translations (
    campaign_id BIGINT NOT NULL, language_code CHAR(2) NOT NULL,
    title VARCHAR(200) NOT NULL, description TEXT NOT NULL, terms TEXT NULL,
    PRIMARY KEY (campaign_id,language_code),
    CONSTRAINT fk_campaign_translation_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns(id),
    CONSTRAINT chk_campaign_translation_language CHECK (language_code IN ('en','am','ti'))
) ENGINE=InnoDB;

CREATE TABLE prizes (
    id BIGINT NOT NULL AUTO_INCREMENT, campaign_id BIGINT NOT NULL,
    category VARCHAR(80) NULL, quantity INT NOT NULL DEFAULT 1, monetary_value DECIMAL(19,4) NULL,
    image_file_id VARCHAR(255) NULL, display_order INT NOT NULL DEFAULT 0, active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id), KEY ix_prizes_campaign_display (campaign_id,active,display_order),
    CONSTRAINT fk_prizes_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns(id),
    CONSTRAINT chk_prize_quantity CHECK (quantity > 0), CONSTRAINT chk_prize_value CHECK (monetary_value IS NULL OR monetary_value >= 0)
) ENGINE=InnoDB;

CREATE TABLE prize_translations (
    prize_id BIGINT NOT NULL, language_code CHAR(2) NOT NULL, name VARCHAR(200) NOT NULL, description TEXT NULL,
    PRIMARY KEY (prize_id,language_code), CONSTRAINT fk_prize_translation_prize FOREIGN KEY (prize_id) REFERENCES prizes(id),
    CONSTRAINT chk_prize_translation_language CHECK (language_code IN ('en','am','ti'))
) ENGINE=InnoDB;

CREATE TABLE orders (
    id BIGINT NOT NULL AUTO_INCREMENT, order_reference VARCHAR(40) NOT NULL,
    user_id BIGINT NOT NULL, campaign_id BIGINT NOT NULL, quantity INT NOT NULL,
    unit_price DECIMAL(19,4) NOT NULL, total_amount DECIMAL(19,4) NOT NULL, currency CHAR(3) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING_PAYMENT',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id), UNIQUE KEY uk_orders_reference (order_reference),
    KEY ix_orders_campaign_status (campaign_id,status), KEY ix_orders_user_created (user_id,created_at),
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users(id), CONSTRAINT fk_orders_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns(id),
    CONSTRAINT chk_order_quantity CHECK (quantity > 0), CONSTRAINT chk_order_amounts CHECK (unit_price >= 0 AND total_amount >= 0),
    CONSTRAINT chk_order_status CHECK (status IN ('PENDING_PAYMENT','RECEIPT_SUBMITTED','UNDER_REVIEW','PAYMENT_VERIFIED','APPROVED','REJECTED','CANCELLED','REFUND_PENDING','REFUNDED'))
) ENGINE=InnoDB;

CREATE TABLE payment_receipts (
    id BIGINT NOT NULL AUTO_INCREMENT, order_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
    telegram_file_id VARCHAR(255) NOT NULL, mime_type VARCHAR(120) NOT NULL, file_size BIGINT NOT NULL,
    submission_number INT NOT NULL, submitted_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    PRIMARY KEY (id), KEY ix_receipts_review (status,submitted_at), KEY ix_receipts_order (order_id,submission_number),
    CONSTRAINT fk_receipts_order FOREIGN KEY (order_id) REFERENCES orders(id), CONSTRAINT fk_receipts_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT chk_receipt_size CHECK (file_size > 0)
) ENGINE=InnoDB;

CREATE TABLE admins (
    id BIGINT NOT NULL AUTO_INCREMENT, telegram_user_id BIGINT NOT NULL, display_name VARCHAR(160) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id), UNIQUE KEY uk_admins_telegram_id (telegram_user_id)
) ENGINE=InnoDB;
ALTER TABLE campaigns ADD CONSTRAINT fk_campaign_created_admin FOREIGN KEY (created_by_admin_id) REFERENCES admins(id);
ALTER TABLE campaigns ADD CONSTRAINT fk_campaign_updated_admin FOREIGN KEY (updated_by_admin_id) REFERENCES admins(id);

CREATE TABLE admin_roles (
    admin_id BIGINT NOT NULL, role_name VARCHAR(32) NOT NULL,
    PRIMARY KEY (admin_id,role_name), CONSTRAINT fk_admin_roles_admin FOREIGN KEY (admin_id) REFERENCES admins(id),
    CONSTRAINT chk_admin_role CHECK (role_name IN ('SUPER_ADMIN','CAMPAIGN_MANAGER','PAYMENT_REVIEWER','RESULTS_MANAGER','SUPPORT_AGENT'))
) ENGINE=InnoDB;

CREATE TABLE payment_verifications (
    id BIGINT NOT NULL AUTO_INCREMENT, order_id BIGINT NOT NULL, receipt_id BIGINT NULL, reviewer_admin_id BIGINT NOT NULL,
    decision VARCHAR(24) NOT NULL, transaction_reference VARCHAR(120) NULL, internal_note VARCHAR(1000) NULL,
    decided_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id), UNIQUE KEY uk_payment_transaction_reference (transaction_reference), KEY ix_verification_order (order_id,decided_at),
    CONSTRAINT fk_verification_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_verification_receipt FOREIGN KEY (receipt_id) REFERENCES payment_receipts(id),
    CONSTRAINT fk_verification_reviewer FOREIGN KEY (reviewer_admin_id) REFERENCES admins(id),
    CONSTRAINT chk_verification_decision CHECK (decision IN ('APPROVED','REJECTED','REPLACEMENT_REQUESTED'))
) ENGINE=InnoDB;

CREATE TABLE tickets (
    id BIGINT NOT NULL AUTO_INCREMENT, campaign_id BIGINT NOT NULL, order_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
    ticket_number VARCHAR(40) NOT NULL, issued_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    PRIMARY KEY (id), UNIQUE KEY uk_ticket_campaign_number (campaign_id,ticket_number),
    KEY ix_tickets_user_issued (user_id,issued_at), KEY ix_tickets_order (order_id),
    CONSTRAINT fk_tickets_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns(id), CONSTRAINT fk_tickets_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_tickets_user FOREIGN KEY (user_id) REFERENCES users(id), CONSTRAINT chk_ticket_status CHECK (status IN ('ACTIVE','WINNER','VOID'))
) ENGINE=InnoDB;

CREATE TABLE draw_results (
    id BIGINT NOT NULL AUTO_INCREMENT, campaign_id BIGINT NOT NULL, version_number INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT', notice TEXT NULL, prepared_by_admin_id BIGINT NOT NULL, published_by_admin_id BIGINT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), published_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_draw_result_version (campaign_id,version_number), KEY ix_results_campaign_status (campaign_id,status),
    CONSTRAINT fk_results_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns(id),
    CONSTRAINT fk_results_preparer FOREIGN KEY (prepared_by_admin_id) REFERENCES admins(id),
    CONSTRAINT fk_results_publisher FOREIGN KEY (published_by_admin_id) REFERENCES admins(id),
    CONSTRAINT chk_result_status CHECK (status IN ('DRAFT','PUBLISHED','SUPERSEDED'))
) ENGINE=InnoDB;

CREATE TABLE draw_result_entries (
    id BIGINT NOT NULL AUTO_INCREMENT, draw_result_id BIGINT NOT NULL, ticket_id BIGINT NOT NULL, prize_id BIGINT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id), UNIQUE KEY uk_result_ticket (draw_result_id,ticket_id), KEY ix_result_entries_prize (prize_id),
    CONSTRAINT fk_result_entry_result FOREIGN KEY (draw_result_id) REFERENCES draw_results(id),
    CONSTRAINT fk_result_entry_ticket FOREIGN KEY (ticket_id) REFERENCES tickets(id), CONSTRAINT fk_result_entry_prize FOREIGN KEY (prize_id) REFERENCES prizes(id)
) ENGINE=InnoDB;

CREATE TABLE payment_instructions (
    id BIGINT NOT NULL AUTO_INCREMENT, method_code VARCHAR(40) NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id), UNIQUE KEY uk_payment_method_code (method_code)
) ENGINE=InnoDB;
CREATE TABLE payment_instruction_translations (
    payment_instruction_id BIGINT NOT NULL, language_code CHAR(2) NOT NULL, title VARCHAR(120) NOT NULL, instructions TEXT NOT NULL,
    PRIMARY KEY (payment_instruction_id,language_code),
    CONSTRAINT fk_payment_instruction_translation FOREIGN KEY (payment_instruction_id) REFERENCES payment_instructions(id),
    CONSTRAINT chk_payment_instruction_language CHECK (language_code IN ('en','am','ti'))
) ENGINE=InnoDB;

CREATE TABLE app_settings (
    setting_key VARCHAR(100) NOT NULL, setting_value TEXT NOT NULL, sensitive BOOLEAN NOT NULL DEFAULT FALSE,
    updated_by_admin_id BIGINT NULL, updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (setting_key), CONSTRAINT fk_settings_admin FOREIGN KEY (updated_by_admin_id) REFERENCES admins(id)
) ENGINE=InnoDB;
CREATE TABLE audit_logs (
    id BIGINT NOT NULL AUTO_INCREMENT, actor_admin_id BIGINT NULL, action VARCHAR(80) NOT NULL,
    target_type VARCHAR(80) NOT NULL, target_id VARCHAR(80) NULL, before_json JSON NULL, after_json JSON NULL,
    occurred_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id), KEY ix_audit_target (target_type,target_id,occurred_at), KEY ix_audit_actor (actor_admin_id,occurred_at),
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_admin_id) REFERENCES admins(id)
) ENGINE=InnoDB;
CREATE TABLE support_requests (
    id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    subject VARCHAR(200) NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id), KEY ix_support_status (status,created_at), CONSTRAINT fk_support_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;
CREATE TABLE support_messages (
    id BIGINT NOT NULL AUTO_INCREMENT, support_request_id BIGINT NOT NULL, sender_user_id BIGINT NULL,
    sender_admin_id BIGINT NULL, message_text TEXT NOT NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id), KEY ix_support_messages (support_request_id,created_at),
    CONSTRAINT fk_support_message_request FOREIGN KEY (support_request_id) REFERENCES support_requests(id),
    CONSTRAINT fk_support_message_user FOREIGN KEY (sender_user_id) REFERENCES users(id),
    CONSTRAINT fk_support_message_admin FOREIGN KEY (sender_admin_id) REFERENCES admins(id)
) ENGINE=InnoDB;
CREATE TABLE notification_records (
    id BIGINT NOT NULL AUTO_INCREMENT, event_key VARCHAR(160) NOT NULL, user_id BIGINT NOT NULL,
    message_key VARCHAR(160) NOT NULL, payload_json JSON NULL, status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0, available_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), sent_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id), UNIQUE KEY uk_notification_event_user (event_key,user_id), KEY ix_notifications_delivery (status,available_at),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users(id), CONSTRAINT chk_notification_status CHECK (status IN ('PENDING','SENT','FAILED'))
) ENGINE=InnoDB;
CREATE TABLE telegram_updates (
    update_id BIGINT NOT NULL, received_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), processed_at TIMESTAMP(6) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'RECEIVED',
    PRIMARY KEY (update_id), KEY ix_telegram_updates_status (status,received_at)
) ENGINE=InnoDB;
