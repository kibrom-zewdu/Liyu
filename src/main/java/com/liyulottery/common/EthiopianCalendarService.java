package com.liyulottery.common;

import net.time4j.PlainDate;
import net.time4j.calendar.EthiopianCalendar;
import net.time4j.calendar.EthiopianEra;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneRules;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts explicit Ethiopian Calendar (EC) local date-times to and from UTC instants. */
@Service
public class EthiopianCalendarService {
    private static final Pattern INPUT = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})\\s+EC\\s+(\\d{2}):(\\d{2})$");
    private final ZoneId businessZone;

    public EthiopianCalendarService(@Value("${app.timezone:Africa/Addis_Ababa}") String businessTimezone) {
        this.businessZone = ZoneId.of(businessTimezone);
    }

    public Instant parseDateTime(String value) {
        if (value == null) throw new IllegalArgumentException("Enter date and time as YYYY-MM-DD EC HH:mm (Ethiopian Calendar)");
        Matcher matcher = INPUT.matcher(value.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Use YYYY-MM-DD EC HH:mm, for example 2019-02-15 EC 14:30");
        }
        int year = Integer.parseInt(matcher.group(1));
        int month = Integer.parseInt(matcher.group(2));
        int day = Integer.parseInt(matcher.group(3));
        int hour = Integer.parseInt(matcher.group(4));
        int minute = Integer.parseInt(matcher.group(5));
        if (!EthiopianCalendar.isValid(EthiopianEra.AMETE_MIHRET, year, month, day)) {
            throw new IllegalArgumentException("That is not a valid Ethiopian Calendar date; check the month and Pagume days");
        }
        LocalTime time;
        try {
            time = LocalTime.of(hour, minute);
        } catch (RuntimeException invalidTime) {
            throw new IllegalArgumentException("Enter a valid local time using 24-hour HH:mm", invalidTime);
        }
        EthiopianCalendar ecDate = EthiopianCalendar.of(EthiopianEra.AMETE_MIHRET, year, month, day);
        LocalDate isoDate = ecDate.transform(PlainDate.class).toTemporalAccessor();
        LocalDateTime localDateTime = LocalDateTime.of(isoDate, time);
        ZoneRules rules = businessZone.getRules();
        List<ZoneOffset> offsets = rules.getValidOffsets(localDateTime);
        if (offsets.size() != 1) {
            throw new IllegalArgumentException("That local time is ambiguous or does not exist in the configured business timezone");
        }
        return ZonedDateTime.ofStrict(localDateTime, offsets.getFirst(), businessZone).toInstant();
    }

    public String format(Instant instant) {
        if (instant == null) return "Not scheduled (EC)";
        ZonedDateTime local = instant.atZone(businessZone);
        EthiopianCalendar ecDate = PlainDate.from(local.toLocalDate()).transform(EthiopianCalendar.class);
        return "%04d-%02d-%02d EC %02d:%02d (%s)".formatted(ecDate.getYear(), ecDate.getMonth().getValue(),
                ecDate.getDayOfMonth(), local.getHour(), local.getMinute(), businessZone.getId());
    }

    public ZoneId businessZone() {
        return businessZone;
    }
}
