package com.liyulottery;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.liyulottery.admin.AdminAuthorizationService;
import com.liyulottery.order.OrderService;
import com.liyulottery.ticket.TicketIssuanceService;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;

@Testcontainers(disabledWithoutDocker = true)
class MariaDbMigrationIntegrationTest {
    @Container
    private static final MariaDBContainer<?> DATABASE = new MariaDBContainer<>("mariadb:11.8")
            .withDatabaseName("liyu_lottery_test")
            .withUsername("liyu_test")
            .withPassword("integration-test-only");

    @Test
    void allMigrationsApplyAndCriticalConstraintsExist() throws Exception {
        Flyway flyway=Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword())
                .locations("classpath:db/migration")
                .load();
        var migrationResult=flyway.migrate();
        assertEquals(6,migrationResult.migrationsExecuted);

        try(var connection=DriverManager.getConnection(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
            var statement=connection.createStatement()) {
            try(var results=statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('users','campaigns','orders','payment_receipts','tickets','draw_results','draw_result_translations','order_drafts')")) {
                assertTrue(results.next());
                assertEquals(8,results.getInt(1));
            }
            try(var results=statement.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='app_settings' AND column_name='is_sensitive'")) {
                assertTrue(results.next());
                assertEquals(1,results.getInt(1));
            }
        }

        DriverManagerDataSource dataSource=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        JdbcTemplate jdbc=new JdbcTemplate(dataSource);
        TransactionTemplate transactions=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.update("INSERT INTO users(telegram_user_id,display_name,language_code) VALUES(910001,'Integration customer','en')");
        jdbc.update("INSERT INTO users(telegram_user_id,display_name,language_code) VALUES(910002,'Other customer','en')");
        jdbc.update("INSERT INTO admins(telegram_user_id,active) VALUES(910003,TRUE)");
        long adminId=jdbc.queryForObject("SELECT id FROM admins WHERE telegram_user_id=910003",Long.class);
        jdbc.update("INSERT INTO admin_roles(admin_id,role_name) VALUES(?,'PAYMENT_REVIEWER')",adminId);
        jdbc.update("INSERT INTO campaigns(campaign_code,ticket_price,currency,sales_start_at,sales_close_at,draw_at,ticket_limit,per_user_limit,status) VALUES('INTEG01',10.0000,'ETB',UTC_TIMESTAMP(6)-INTERVAL 1 DAY,UTC_TIMESTAMP(6)+INTERVAL 1 DAY,UTC_TIMESTAMP(6)+INTERVAL 2 DAY,20,3,'OPEN')");
        long campaignId=jdbc.queryForObject("SELECT id FROM campaigns WHERE campaign_code='INTEG01'",Long.class);
        jdbc.update("INSERT INTO campaign_translations(campaign_id,language_code,title,description,terms) VALUES(?,'en','Integration campaign','Test description','Test rules')",campaignId);
        jdbc.update("INSERT INTO payment_instructions(method_code,active) VALUES('TEST_METHOD',TRUE)");
        long instructionId=jdbc.queryForObject("SELECT id FROM payment_instructions WHERE method_code='TEST_METHOD'",Long.class);
        jdbc.update("INSERT INTO payment_instruction_translations(payment_instruction_id,language_code,title,instructions) VALUES(?,'en','Test method','Test-only payment instructions')",instructionId);

        AdminAuthorizationService authorization=new AdminAuthorizationService(jdbc);
        TicketIssuanceService ticketIssuance=new TicketIssuanceService(jdbc,authorization);
        OrderService orders=new OrderService(jdbc,authorization,true,5_000_000,ticketIssuance);
        OrderService.OrderQuote quote=transactions.execute(status->orders.quote(910001,campaignId,2));
        assertEquals(new java.math.BigDecimal("20.0000"),quote.total());
        OrderService.OrderSummary order=transactions.execute(status->orders.confirm(910001));
        assertEquals("PENDING_PAYMENT",order.status());
        transactions.execute(status->orders.submitReceipt(910001,order.reference(),"private-test-file-id","image/jpeg",1024));
        assertEquals(1,orders.pendingPayments(910003).size());
        List<String> issued=transactions.execute(status->orders.decide(910003,order.reference(),true,"TEST-TXN-910001","verified in integration test"));
        assertEquals(2,issued.size());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE order_id=(SELECT id FROM orders WHERE order_reference=?)",Integer.class,order.reference()));
        assertEquals("APPROVED",jdbc.queryForObject("SELECT status FROM orders WHERE order_reference=?",String.class,order.reference()));
        assertEquals(2,ticketIssuance.customerTickets(910001,10,0).size());
        assertTrue(ticketIssuance.customerTickets(910002,10,0).isEmpty());
        assertThrows(IllegalStateException.class,()->transactions.execute(status->orders.decide(910003,order.reference(),true,"TEST-TXN-DUP","duplicate approval")));
    }
}
