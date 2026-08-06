package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Studio calendars surface (docs/jobs.md "Business-day calendars"): the month grid that
 * makes the daily-consider model visible, and the weekend/dates form that lands drafts.
 */
class StudioServiceCalendarsTest {

    private StudioService studio(@TempDir Path dir, boolean readOnly) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("calendars"));
        Files.writeString(dir.resolve("calendars/main.yml"), """
                version: tesseraql/v1
                calendars:
                  jp-banking:
                    holidays:
                      dates: [2026-06-05]
                  market:
                    weekend: []
                    holidays:
                      source: { table: holidays, date: holiday_date }
                """);
        return new StudioService(new ManifestLoader().load(dir), readOnly);
    }

    @Test
    void listsCalendarsWithTheEffectiveWeekendAndHolidayHome(@TempDir Path dir)
            throws Exception {
        List<StudioService.CalendarSummary> calendars = studio(dir, true).calendars();

        assertThat(calendars).hasSize(2);
        StudioService.CalendarSummary banking = calendars.get(0);
        assertThat(banking.name()).isEqualTo("jp-banking");
        assertThat(banking.weekend()).containsExactly("saturday", "sunday"); // default explicit
        assertThat(banking.dates()).containsExactly("2026-06-05");
        assertThat(banking.tableBacked()).isFalse();
        assertThat(calendars.get(1).tableBacked()).isTrue();
        assertThat(calendars.get(1).sourceTable()).isEqualTo("holidays");
    }

    @Test
    void theMonthGridClassifiesDaysAndPreviewsTheShiftedNominal(@TempDir Path dir)
            throws Exception {
        // June 2026: the 5th is a Friday holiday, so a dayOfMonth-5 rule lands on Monday the 8th.
        StudioService.CalendarMonthGrid grid = studio(dir, true)
                .calendarMonth("jp-banking", "2026-06", 5, null, null);

        assertThat(grid.error()).isNull();
        assertThat(grid.monthLabel()).isEqualTo("JUNE 2026");
        StudioService.CalendarDayCell fifth = cell(grid, 5);
        assertThat(fifth.holiday()).isTrue();
        assertThat(fifth.nominal()).isTrue();
        assertThat(fifth.lands()).isFalse();
        StudioService.CalendarDayCell sixth = cell(grid, 6);
        assertThat(sixth.weekend()).isTrue(); // Saturday under the default weekend
        StudioService.CalendarDayCell eighth = cell(grid, 8);
        assertThat(eighth.business()).isTrue();
        assertThat(eighth.lands()).isTrue(); // the one date the firing counts

        assertThat(studio(dir, true).calendarMonth("no-such", null, null, null, null).error())
                .contains("no-such");
    }

    @Test
    void savingLandsAValidatedDraftAndRefusesTableBackedCalendars(@TempDir Path dir)
            throws Exception {
        StudioService studio = studio(dir, false);

        Path draft = studio.saveCalendar("jp-banking", List.of("saturday", "sunday"),
                List.of("2026-06-05", "2026-12-31"), "operator");
        String yaml = Files.readString(draft);
        assertThat(yaml).contains("2026-12-31").contains("saturday");
        assertThat(yaml).contains("market"); // the sibling in the same file survives the edit

        assertThatThrownBy(() -> studio.saveCalendar("jp-banking", List.of("caturday"),
                List.of(), "operator"))
                .isInstanceOf(TqlException.class).hasMessageContaining("caturday");
        assertThatThrownBy(() -> studio.saveCalendar("jp-banking", List.of(),
                List.of("not-a-date"), "operator"))
                .isInstanceOf(TqlException.class).hasMessageContaining("not-a-date");
        assertThatThrownBy(() -> studio.saveCalendar("market", List.of(), List.of(),
                "operator"))
                .isInstanceOf(TqlException.class).hasMessageContaining("table-backed");

        // A name not yet declared becomes a new calendars/ draft.
        Path created = studio.saveCalendar("uk-banking", List.of("saturday", "sunday"),
                List.of("2026-12-25"), "operator");
        assertThat(Files.readString(created)).contains("uk-banking").contains("2026-12-25");
    }

    @Test
    void toggleAddsThenRemovesAHolidayThroughTheDraftFlow(@TempDir Path dir) throws Exception {
        // Click-to-toggle (studio-ux-refresh slice 6): toggling rides the same validated
        // draft flow as the form save, and successive toggles accumulate on the draft —
        // calendarEditState reads the PENDING draft, not the served source.
        StudioService studio = studio(dir, false);

        studio.toggleCalendarHoliday("jp-banking", "2026-12-31", "it");
        StudioService.CalendarEditState added = studio.calendarEditState("jp-banking");
        assertThat(added.dates()).containsExactly("2026-06-05", "2026-12-31");
        assertThat(added.draftPending()).isTrue();

        studio.toggleCalendarHoliday("jp-banking", "2026-12-31", "it");
        assertThat(studio.calendarEditState("jp-banking").dates())
                .containsExactly("2026-06-05");
    }

    @Test
    void toggleRefusesBadDatesUnknownAndTableBackedCalendars(@TempDir Path dir)
            throws Exception {
        StudioService studio = studio(dir, false);

        assertThatThrownBy(() -> studio.toggleCalendarHoliday("jp-banking", "not-a-date", "it"))
                .isInstanceOf(TqlException.class).hasMessageContaining("ISO date");
        assertThatThrownBy(() -> studio.toggleCalendarHoliday("nope", "2026-12-31", "it"))
                .isInstanceOf(TqlException.class).hasMessageContaining("No calendar");
        assertThatThrownBy(() -> studio.toggleCalendarHoliday("market", "2026-12-31", "it"))
                .isInstanceOf(TqlException.class).hasMessageContaining("table-backed");
    }

    private static StudioService.CalendarDayCell cell(StudioService.CalendarMonthGrid grid,
            int day) {
        return grid.weeks().stream().flatMap(List::stream)
                .filter(c -> c.inMonth() && c.day() == day)
                .findFirst().orElseThrow();
    }
}
