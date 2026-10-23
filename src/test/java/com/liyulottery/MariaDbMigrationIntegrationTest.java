package com.liyulottery;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    }
}
