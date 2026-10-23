package com.liyulottery;

import com.liyulottery.admin.AdminAuthorizationService;
import com.liyulottery.order.OrderService;
import com.liyulottery.ticket.TicketIssuanceService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class OrderServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final AdminAuthorizationService authorization=mock(AdminAuthorizationService.class);
    private final TicketIssuanceService ticketIssuance=mock(TicketIssuanceService.class);

    @Test
    void paidOrderQuotesAreDisabledByDefault() {
        OrderService service=new OrderService(jdbc,authorization,false,10_485_760,ticketIssuance);
        assertThrows(IllegalStateException.class,()->service.quote(42,1,1));
        verifyNoInteractions(jdbc);
    }

    @Test
    void quantityIsValidatedBeforeDatabaseAccess() {
        OrderService service=new OrderService(jdbc,authorization,true,10_485_760,ticketIssuance);
        assertThrows(IllegalArgumentException.class,()->service.quote(42,1,0));
        assertThrows(IllegalArgumentException.class,()->service.quote(42,1,21));
        verifyNoInteractions(jdbc);
    }
}
