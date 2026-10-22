package com.liyulottery.telegram;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class TelegramUpdateQueue {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TelegramUpdateQueue(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void saveReceived(long updateId, Map<String, Object> payload) {
        try {
            jdbcTemplate.update("INSERT IGNORE INTO telegram_updates(update_id,payload_json,status) VALUES(?,?,'RECEIVED')",
                    updateId, objectMapper.writeValueAsString(payload));
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Could not encode Telegram update", e);
        }
    }

    public List<PendingUpdate> pending(int limit) {
        return jdbcTemplate.query("SELECT update_id,payload_json FROM telegram_updates WHERE status IN ('RECEIVED','FAILED') ORDER BY update_id LIMIT ?",
                (rs, row) -> new PendingUpdate(rs.getLong("update_id"), parse(rs.getString("payload_json"))), limit);
    }

    public void markProcessed(long updateId) {
        jdbcTemplate.update("UPDATE telegram_updates SET status='PROCESSED',processed_at=CURRENT_TIMESTAMP(6) WHERE update_id=?", updateId);
    }

    public void markFailed(long updateId) {
        jdbcTemplate.update("UPDATE telegram_updates SET status='FAILED' WHERE update_id=?", updateId);
    }

    private Map<String, Object> parse(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored Telegram update is invalid", e);
        }
    }

    public record PendingUpdate(long updateId, Map<String, Object> payload) { }
}
