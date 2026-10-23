package com.liyulottery;

import com.liyulottery.admin.AdminAuthorizationService;
import com.liyulottery.settings.PublicSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PublicSettingsServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final AdminAuthorizationService authorization=mock(AdminAuthorizationService.class);
    private final PublicSettingsService settings=new PublicSettingsService(jdbc,authorization);

    @Test
    void unauthorizedUsersCannotUpdateSettings() {
        doThrow(new AccessDeniedException("denied")).when(authorization).requireRole(anyLong(),org.mockito.ArgumentMatchers.any());
        assertThrows(AccessDeniedException.class,()->settings.set(42,"support_contact","@support"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void secretOrUnknownKeysAreRejected() {
        when(authorization.requireRole(anyLong(),org.mockito.ArgumentMatchers.any())).thenReturn(5L);
        assertThrows(IllegalArgumentException.class,()->settings.set(42,"payment_account","12345"));
        assertThrows(IllegalArgumentException.class,()->settings.set(42,"support_contact"," "));
        verifyNoInteractions(jdbc);
    }
}
