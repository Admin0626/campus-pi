package com.campuspi.admin;
import static org.assertj.core.api.Assertions.*;
import com.campuspi.account.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdminQueryTest {
    @Test void datesUseShanghaiMidnightAndExclusiveEnd(){
        var args=new ArrayList<Object>();assertThat(AdminQuery.dates("l.operated_at","2026-10-20","2026-10-20",args)).isEqualTo(" AND l.operated_at>=? AND l.operated_at<?");
        assertThat(args).containsExactly(Timestamp.from(Instant.parse("2026-10-19T16:00:00Z")),Timestamp.from(Instant.parse("2026-10-20T16:00:00Z")));
    }
    @Test void absentDatesDoNotFilter(){var args=new ArrayList<Object>();assertThat(AdminQuery.dates("sent_at","","",args)).isEmpty();assertThat(args).isEmpty();}
    @ParameterizedTest @ValueSource(strings={"2026-02-30|2026-03-01","2026-10-23|2026-10-01","bad|2026-10-23","1899-01-01|1900-01-01","2026-1-1|2026-10-23","2026-10-23|9999-12-31"})
    void invalidRangesFailBeforeAddingArguments(String input){var pair=input.split("\\|",-1);var args=new ArrayList<Object>();assertThatThrownBy(()->AdminQuery.dates("sent_at",pair[0],pair[1],args)).isInstanceOf(ApiException.class);assertThat(args).isEmpty();}
    @Test void longKeywordsAndUntrustedColumnsAreRejected(){assertThat(AdminQuery.keyword(" 测试 ")).isEqualTo("测试");assertThatThrownBy(()->AdminQuery.keyword("a".repeat(101))).isInstanceOf(ApiException.class);assertThatThrownBy(()->AdminQuery.dates("x;DROP","","",new ArrayList<>())).isInstanceOf(IllegalArgumentException.class);}
}
