package com.campuspi.notification;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NotificationPreferencePolicyTest {
    @Test void daytimeWindowUsesShanghaiAndExcludesEnd(){
        LocalTime start=LocalTime.of(9,0),end=LocalTime.of(18,0);
        assertEquals(Instant.parse("2026-10-21T10:00:00Z"),NotificationPreferencePolicy.nextAllowed(Instant.parse("2026-10-21T01:00:00Z"),start,end));
        var boundary=Instant.parse("2026-10-21T10:00:00Z");assertEquals(boundary,NotificationPreferencePolicy.nextAllowed(boundary,start,end));
    }
    @Test void overnightWindowCrossesLocalMidnight(){
        LocalTime start=LocalTime.of(23,0),end=LocalTime.of(7,0);
        var endUtc=Instant.parse("2026-10-21T23:00:00Z");
        assertEquals(endUtc,NotificationPreferencePolicy.nextAllowed(Instant.parse("2026-10-21T15:00:00Z"),start,end));
        assertEquals(endUtc,NotificationPreferencePolicy.nextAllowed(Instant.parse("2026-10-21T22:59:59Z"),start,end));
        assertEquals(endUtc,NotificationPreferencePolicy.nextAllowed(endUtc,start,end));
    }
    @Test void midnightAndYearRolloverAreNotTreatedAsSameUtcDay(){
        assertEquals(Instant.parse("2026-12-31T17:00:00Z"),NotificationPreferencePolicy.nextAllowed(Instant.parse("2026-12-31T16:00:00Z"),LocalTime.of(22,0),LocalTime.of(1,0)));
        var now=Instant.parse("2026-12-31T10:00:00Z");assertEquals(now,NotificationPreferencePolicy.nextAllowed(now,null,null));
    }
    @Test void malformedLegacyWindowsFailClosed(){
        var now=Instant.parse("2026-10-21T00:00:00Z");
        assertThrows(RuntimeException.class,()->NotificationPreferencePolicy.nextAllowed(now,LocalTime.NOON,null));
        assertThrows(RuntimeException.class,()->NotificationPreferencePolicy.nextAllowed(now,LocalTime.NOON,LocalTime.NOON));
    }
}
