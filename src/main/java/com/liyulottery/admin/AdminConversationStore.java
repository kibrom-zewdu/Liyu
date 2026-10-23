package com.liyulottery.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

@Repository
public class AdminConversationStore {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AdminConversationStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(long telegramUserId, int step, Map<String, String> data) {
        save(telegramUserId, "CAMPAIGN_NEW", step, data);
    }

    public void save(long telegramUserId, String type, int step, Map<String, String> data) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO admin_conversations(telegram_user_id,conversation_type,current_step,state_data)
                    VALUES(?,?,?,?)
                    ON DUPLICATE KEY UPDATE conversation_type=VALUES(conversation_type),current_step=VALUES(current_step),
                        state_data=VALUES(state_data),updated_at=CURRENT_TIMESTAMP(6)
                    """, telegramUserId, type, step, objectMapper.writeValueAsString(data));
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Could not save the admin form state", e);
        }
    }

    public Optional<AdminConversation> find(long telegramUserId) {
        return jdbcTemplate.query("SELECT conversation_type,current_step,state_data FROM admin_conversations WHERE telegram_user_id=?",
                rs -> rs.next() ? Optional.of(new AdminConversation(rs.getString(1), rs.getInt(2), decode(rs.getString(3)))) : Optional.empty(),
                telegramUserId);
    }

    public void clear(long telegramUserId) {
        jdbcTemplate.update("DELETE FROM admin_conversations WHERE telegram_user_id=?", telegramUserId);
    }

    private Map<String, String> decode(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() { });
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored admin form state is invalid", e);
        }
    }

    public record AdminConversation(String type, int currentStep, Map<String, String> data) { }
}
