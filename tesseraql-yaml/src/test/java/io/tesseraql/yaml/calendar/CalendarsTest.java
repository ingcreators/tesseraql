package io.tesseraql.yaml.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.SimpleYamlParser;
import io.tesseraql.yaml.model.CalendarsDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Business-day calendars (docs/batch-platform.md track B): the shared-definition load and the
 * daily-consider evaluation — the cron says when to consider, the calendar says whether the
 * firing counts.
 */
class CalendarsTest {

    private static final SimpleYamlParser PARSER = new SimpleYamlParser();

    private Calendars load(Path dir, String yaml) throws Exception {
        Files.createDirectories(dir.resolve("calendars"));
        Files.writeString(dir.resolve("calendars/main.yml"), yaml);
        return Calendars.load(dir, PARSER);
    }

    @Test
    void loadsCalendarsAndDefaultsTheWeekend(@TempDir Path dir) throws Exception {
        Calendars calendars = load(dir, """
                version: tesseraql/v1
                calendars:
                  jp-banking:
                    holidays:
                      dates: [2026-01-01, 2026-01-02]
                """);

        CalendarsDocument.Calendar calendar = calendars.calendars().get("jp-banking");
        // 2026-01-05 is a Monday; the 3rd/4th are the default weekend, the 1st/2nd holidays.
        Set<LocalDate> holidays = Calendars.staticHolidays(calendar);
        assertThat(Calendars.counts(calendar, null, LocalDate.parse("2026-01-05"), holidays))
                .isTrue();
        assertThat(Calendars.counts(calendar, null, LocalDate.parse("2026-01-03"), holidays))
                .isFalse(); // Saturday
        assertThat(Calendars.counts(calendar, null, LocalDate.parse("2026-01-01"), holidays))
                .isFalse(); // holiday
    }

    @Test
    void monthBoundaryQualifiersSkipWeekendsAndHolidays(@TempDir Path dir) throws Exception {
        Calendars calendars = load(dir, """
                version: tesseraql/v1
                calendars:
                  close:
                    holidays:
                      dates: [2026-06-01, 2026-06-30]
                """);
        CalendarsDocument.Calendar calendar = calendars.calendars().get("close");
        Set<LocalDate> holidays = Calendars.staticHolidays(calendar);

        // June 2026: the 1st (Monday) is a holiday, so the first business day is Tuesday the 2nd.
        assertThat(Calendars.counts(calendar, "firstBusinessDayOfMonth",
                LocalDate.parse("2026-06-02"), holidays)).isTrue();
        assertThat(Calendars.counts(calendar, "firstBusinessDayOfMonth",
                LocalDate.parse("2026-06-01"), holidays)).isFalse(); // holiday itself
        assertThat(Calendars.counts(calendar, "firstBusinessDayOfMonth",
                LocalDate.parse("2026-06-03"), holidays)).isFalse(); // the 2nd already counted

        // The 30th (Tuesday) is a holiday, so the last business day is Monday the 29th.
        assertThat(Calendars.counts(calendar, "lastBusinessDayOfMonth",
                LocalDate.parse("2026-06-29"), holidays)).isTrue();
        assertThat(Calendars.counts(calendar, "lastBusinessDayOfMonth",
                LocalDate.parse("2026-06-30"), holidays)).isFalse();
        assertThat(Calendars.counts(calendar, "lastBusinessDayOfMonth",
                LocalDate.parse("2026-06-26"), holidays)).isFalse(); // the 29th still to come
    }

    @Test
    void anExplicitEmptyWeekendMeansEveryDayIsABusinessDay(@TempDir Path dir) throws Exception {
        Calendars calendars = load(dir, """
                version: tesseraql/v1
                calendars:
                  always:
                    weekend: []
                """);
        CalendarsDocument.Calendar calendar = calendars.calendars().get("always");

        assertThat(Calendars.counts(calendar, null, LocalDate.parse("2026-01-03"), Set.of()))
                .isTrue(); // a Saturday counts under an explicit empty weekend
    }

    @Test
    void duplicateNamesBadDatesAndBadDayNamesFailTheLoad(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("calendars"));
        Files.writeString(dir.resolve("calendars/a.yml"), """
                version: tesseraql/v1
                calendars:
                  jp: { weekend: [saturday] }
                """);
        Files.writeString(dir.resolve("calendars/b.yml"), """
                version: tesseraql/v1
                calendars:
                  jp: { weekend: [sunday] }
                """);
        assertThatThrownBy(() -> Calendars.load(dir, PARSER))
                .isInstanceOf(TqlException.class).hasMessageContaining("declared twice");

        Files.delete(dir.resolve("calendars/b.yml"));
        Files.writeString(dir.resolve("calendars/a.yml"), """
                version: tesseraql/v1
                calendars:
                  jp:
                    holidays:
                      dates: [not-a-date]
                """);
        assertThatThrownBy(() -> Calendars.load(dir, PARSER))
                .isInstanceOf(TqlException.class).hasMessageContaining("ISO date");

        Files.writeString(dir.resolve("calendars/a.yml"), """
                version: tesseraql/v1
                calendars:
                  jp: { weekend: [caturday] }
                """);
        assertThatThrownBy(() -> Calendars.load(dir, PARSER))
                .isInstanceOf(TqlException.class).hasMessageContaining("caturday");

        Files.writeString(dir.resolve("calendars/a.yml"), """
                version: tesseraql/v1
                calendars:
                  jp:
                    holidays:
                      source: { table: "holidays; drop table users", date: holiday_date }
                """);
        assertThatThrownBy(() -> Calendars.load(dir, PARSER))
                .isInstanceOf(TqlException.class).hasMessageContaining("plain identifier");
    }

    @Test
    void aMissingCalendarsDirectoryIsSimplyEmpty(@TempDir Path dir) {
        assertThat(Calendars.load(dir, PARSER).isEmpty()).isTrue();
    }
}
