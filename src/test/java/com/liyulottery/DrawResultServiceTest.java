package com.liyulottery;

import com.liyulottery.admin.AdminAuthorizationService;
import com.liyulottery.draw.DrawResultService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DrawResultServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final AdminAuthorizationService authorization=mock(AdminAuthorizationService.class);
    private final DrawResultService results=new DrawResultService(jdbc,authorization);

    @Test
    void onlyAuthorizedResultsManagersCanPrepareResults() {
        doThrow(new AccessDeniedException("denied")).when(authorization).requireRole(anyLong(),org.mockito.ArgumentMatchers.any());
        assertThrows(AccessDeniedException.class,()->results.prepare(14,3,Map.of("en","notice","am","notice","ti","notice"),"0000000001"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void allThreeReviewedNoticesAreRequired() {
        org.mockito.Mockito.when(authorization.requireRole(anyLong(),org.mockito.ArgumentMatchers.any())).thenReturn(7L);
        assertThrows(IllegalArgumentException.class,()->results.prepare(14,3,Map.of("en","notice"),"0000000001"));
        verifyNoInteractions(jdbc);
    }
}
