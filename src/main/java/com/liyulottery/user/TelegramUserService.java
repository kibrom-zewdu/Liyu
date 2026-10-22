package com.liyulottery.user;

import com.liyulottery.common.CustomerLanguage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TelegramUserService {
    private final JdbcTemplate jdbcTemplate;

    public TelegramUserService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public String touch(long telegramId, String username, String displayName) {
        jdbcTemplate.update("""
                INSERT INTO users(telegram_user_id,telegram_username,display_name,last_interaction_at)
                VALUES(?,?,?,CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE telegram_username=VALUES(telegram_username),display_name=VALUES(display_name),
                last_interaction_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6)
                """, telegramId, username, displayName);
        return jdbcTemplate.queryForObject("SELECT language_code FROM users WHERE telegram_user_id=?", String.class, telegramId);
    }

    @Transactional
    public void setLanguage(long telegramId, CustomerLanguage language) {
        jdbcTemplate.update("UPDATE users SET language_code=?,updated_at=CURRENT_TIMESTAMP(6),last_interaction_at=CURRENT_TIMESTAMP(6) WHERE telegram_user_id=?",
                language.code(), telegramId);
    }
}
