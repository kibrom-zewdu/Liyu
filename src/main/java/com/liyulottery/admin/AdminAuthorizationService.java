package com.liyulottery.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAuthorizationService {
    private final JdbcTemplate jdbcTemplate;

    public AdminAuthorizationService(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    @Transactional(readOnly = true)
    public long requireRole(long telegramUserId, AdminRole requiredRole) {
        return jdbcTemplate.query("""
                SELECT a.id FROM admins a JOIN admin_roles r ON r.admin_id=a.id
                WHERE a.telegram_user_id=? AND a.active=TRUE AND r.role_name IN ('SUPER_ADMIN',?)
                LIMIT 1
                """, rs -> {
            if (!rs.next()) throw new AccessDeniedException("Administrator permission required");
            return rs.getLong(1);
        }, telegramUserId, requiredRole.name());
    }
}
