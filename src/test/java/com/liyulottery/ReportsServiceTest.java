package com.liyulottery;

import com.liyulottery.admin.AdminAuthorizationService;
import com.liyulottery.report.ReportsService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ReportsServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final AdminAuthorizationService authorization=mock(AdminAuthorizationService.class);
    private final ReportsService reports=new ReportsService(jdbc,authorization);

    @Test
    void unauthorizedAdminCannotReadReports() {
        doThrow(new AccessDeniedException("denied")).when(authorization).requireRole(anyLong(),org.mockito.ArgumentMatchers.any());
        assertThrows(AccessDeniedException.class,()->reports.summary(42));
        verifyNoInteractions(jdbc);
    }
}
