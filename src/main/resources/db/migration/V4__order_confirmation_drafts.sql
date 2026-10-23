CREATE TABLE order_drafts (
    user_id BIGINT NOT NULL,
    campaign_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    quoted_unit_price DECIMAL(19,4) NOT NULL,
    quoted_total DECIMAL(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id),
    KEY ix_order_drafts_expiry (expires_at),
    CONSTRAINT fk_order_draft_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_order_draft_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns(id),
    CONSTRAINT chk_order_draft_quantity CHECK (quantity > 0)
) ENGINE=InnoDB;
