package com.liyulottery;

import com.liyulottery.admin.AdminAuthorizationService;
import com.liyulottery.ticket.TicketIssuanceService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TicketIssuanceServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final AdminAuthorizationService authorization=mock(AdminAuthorizationService.class);
    private final TicketIssuanceService service=new TicketIssuanceService(jdbc,authorization);

    @Test
    void invalidCustomerPageIsRejectedBeforeQuery() {
        assertThrows(IllegalArgumentException.class,()->service.customerTickets(8,10,-1));
        assertThrows(IllegalArgumentException.class,()->service.customerTickets(8,51,0));
        verifyNoInteractions(jdbc);
    }

    @Test
    void unauthorizedReviewerCannotIssueTickets() {
        doThrow(new AccessDeniedException("denied")).when(authorization).requireRole(anyLong(),any());
        assertThrows(AccessDeniedException.class,()->service.issueVerifiedOrder(8,"LL-EXAMPLE"));
        verifyNoInteractions(jdbc);
    }
}
