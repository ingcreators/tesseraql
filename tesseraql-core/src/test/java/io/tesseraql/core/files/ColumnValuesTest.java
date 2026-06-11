package io.tesseraql.core.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ColumnValuesTest {

    @Test
    void typedParsingProducesSqlReadyValues() {
        ColumnMapping date = new ColumnMapping("heldOn", null, null, "date", "yyyy/MM/dd");
        assertThat(ColumnValues.parse(date, "2026/06/11", Locale.JAPAN))
                .isEqualTo(LocalDate.of(2026, 6, 11));

        ColumnMapping plain = new ColumnMapping("qty", null, null, "number", null);
        assertThat(ColumnValues.parse(plain, "12.5", Locale.US))
                .isEqualTo(new BigDecimal("12.5"));

        assertThat(ColumnValues.parse(date, "  ", Locale.US)).isNull();
    }

    @Test
    void localizedNumberFormatsParsePerLocale() {
        ColumnMapping fee = new ColumnMapping("fee", null, null, "number", "#,##0.00");
        assertThat(ColumnValues.parse(fee, "1.234,56", Locale.GERMANY))
                .isEqualTo(new BigDecimal("1234.56"));
        assertThat(ColumnValues.parse(fee, "1,234.56", Locale.US))
                .isEqualTo(new BigDecimal("1234.56"));
    }

    @Test
    void badValuesFailWithTheColumnAndPattern() {
        ColumnMapping date = new ColumnMapping("heldOn", null, null, "date", "yyyy/MM/dd");
        assertThatThrownBy(() -> ColumnValues.parse(date, "11-06-2026", Locale.US))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heldOn")
                .hasMessageContaining("yyyy/MM/dd");
    }

    @Test
    void formattingRendersWithLocaleAndTimeZone() {
        ColumnMapping stamp = new ColumnMapping("createdAt", null, null, null, "yyyy/MM/dd HH:mm");
        Timestamp utcMidnight = Timestamp.from(java.time.Instant.parse("2026-06-10T23:30:00Z"));
        assertThat(ColumnValues.format(stamp, utcMidnight,
                Locale.JAPAN, ZoneId.of("Asia/Tokyo")))
                .isEqualTo("2026/06/11 08:30");

        ColumnMapping fee = new ColumnMapping("fee", null, null, "number", "#,##0.00");
        assertThat(ColumnValues.format(fee, new BigDecimal("1234.5"),
                Locale.GERMANY, ZoneId.systemDefault()))
                .isEqualTo("1.234,50");

        // No format and no temporal type: the value passes through untouched.
        ColumnMapping plain = ColumnMapping.of("name");
        assertThat(ColumnValues.format(plain, "alpha", Locale.US, ZoneId.systemDefault()))
                .isEqualTo("alpha");
    }
}
