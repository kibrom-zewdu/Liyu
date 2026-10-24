package com.liyulottery;

import com.liyulottery.common.EthiopianCalendarService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EthiopianCalendarServiceTest {
    private final EthiopianCalendarService calendar = new EthiopianCalendarService("Africa/Addis_Ababa");

    @Test
    void convertsEthiopianNewYearToBusinessTimezoneInstantAndBack() {
        Instant instant = calendar.parseDateTime("2019-01-01 EC 00:00");

        assertEquals(Instant.parse("2026-09-10T21:00:00Z"), instant);
        assertEquals("2019-01-01 EC 00:00 (Africa/Addis_Ababa)", calendar.format(instant));
    }

    @Test
    void acceptsSixthPagumeDayInEthiopianLeapYear() {
        Instant instant = calendar.parseDateTime("2019-13-06 EC 23:59");

        assertEquals("2019-13-06 EC 23:59 (Africa/Addis_Ababa)", calendar.format(instant));
    }

    @Test
    void rejectsSixthPagumeDayOutsideEthiopianLeapYear() {
        assertThrows(IllegalArgumentException.class, () -> calendar.parseDateTime("2018-13-06 EC 12:00"));
    }

    @Test
    void requiresExplicitEcMarkerAndValidTime() {
        assertThrows(IllegalArgumentException.class, () -> calendar.parseDateTime("2019-02-15 14:30"));
        assertThrows(IllegalArgumentException.class, () -> calendar.parseDateTime("2019-02-15 EC 24:00"));
    }
}
