CREATE TABLE campaign_ticket_counters (
    campaign_id BIGINT NOT NULL,
    last_number BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (campaign_id),
    CONSTRAINT fk_ticket_counter_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns(id),
    CONSTRAINT chk_ticket_counter_value CHECK (last_number >= 0)
) ENGINE=InnoDB;
