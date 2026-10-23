package com.liyulottery;

import com.liyulottery.admin.AdminAuthorizationService;
import com.liyulottery.support.SupportService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SupportServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final AdminAuthorizationService authorization=mock(AdminAuthorizationService.class);
    private final SupportService support=new SupportService(jdbc,authorization);

    @Test
    void customerMessageLengthIsValidatedBeforeDatabaseAccess() {
        assertThrows(IllegalArgumentException.class,()->support.open(20," "));
        assertThrows(IllegalArgumentException.class,()->support.open(20,"x".repeat(4001)));
        verifyNoInteractions(jdbc);
    }

    @Test
    void nonSupportAgentsCannotListRequests() {
        doThrow(new AccessDeniedException("denied")).when(authorization).requireRole(anyLong(),org.mockito.ArgumentMatchers.any());
        assertThrows(AccessDeniedException.class,()->support.openRequests(20));
        verifyNoInteractions(jdbc);
    }
}
