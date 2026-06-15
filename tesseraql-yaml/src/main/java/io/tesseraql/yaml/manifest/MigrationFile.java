package io.tesseraql.yaml.manifest;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One Flyway migration SQL file listed in the manifest (spec layer, design ch. 48; documentation
 * portal v1). The manifest records migrations so the portal can list the schema an application
 * applies without standing up a database — there is no DDL parsing here, only the file's
 * Flyway-convention coordinates.
 *
 * <p>Migrations resolve exactly as the runtime applies them (see {@code AppMigrations}): a
 * {@code main} set under {@code db/migration} plus its vendor overlays {@code db/migration-<vendor>},
 * and one set per named datasource under {@code db/<datasource>/migration} (with overlays
 * {@code db/<datasource>/migration-<vendor>}). Files are scanned recursively, as Flyway scans a
 * location, so only files matching the Flyway naming convention are listed.
 *
 * <p>The natural ordering is the listing order: by datasource, the common set before its vendor
 * overlays, then by version (compared numerically per segment so {@code V2} precedes {@code V10},
 * with repeatable migrations last as Flyway runs them), then by description.
 *
 * @param datasource  the datasource the migration runs against ({@code main} for {@code db/migration})
 * @param vendor      the vendor whose overlay this file belongs to, or {@code null} for the common set
 * @param version     the Flyway version (e.g. {@code 1}, {@code 2.1}), or {@code null} for a
 *                    repeatable ({@code R__}) migration
 * @param description the description segment after {@code __}, with the {@code .sql} suffix removed
 * @param path        the source file path, within the app home
 */
public record MigrationFile(String datasource, String vendor, String version, String description,
        Path path) implements Comparable<MigrationFile> {

    private static final Pattern FLYWAY = Pattern.compile(
            "^(?:V(?<version>.+?)|R)__(?<description>.+)\\.sql$", Pattern.CASE_INSENSITIVE);

    private static final Comparator<MigrationFile> ORDER = Comparator
            .comparing(MigrationFile::datasource)
            .thenComparing(migration -> migration.vendor() == null ? "" : migration.vendor())
            .thenComparing(MigrationFile::version, MigrationFile::compareVersions)
            .thenComparing(MigrationFile::description);

    /**
     * Parses a Flyway-convention migration filename ({@code V<version>__<desc>.sql} or
     * {@code R__<desc>.sql}); returns {@code null} for a file that is not a migration.
     */
    public static MigrationFile parse(String datasource, String vendor, Path path) {
        Matcher matcher = FLYWAY.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            return null;
        }
        return new MigrationFile(datasource, vendor, matcher.group("version"),
                matcher.group("description"), path);
    }

    @Override
    public int compareTo(MigrationFile other) {
        return ORDER.compare(this, other);
    }

    /** Orders versions numerically per segment; a repeatable ({@code null}) version sorts last. */
    private static int compareVersions(String left, String right) {
        if (left == null || right == null) {
            return left == null ? (right == null ? 0 : 1) : -1;
        }
        String[] leftParts = left.split("[._]");
        String[] rightParts = right.split("[._]");
        for (int i = 0; i < Math.max(leftParts.length, rightParts.length); i++) {
            int cmp = compareSegment(i < leftParts.length ? leftParts[i] : "",
                    i < rightParts.length ? rightParts[i] : "");
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    /** Compares two version segments numerically when both are digits, lexically otherwise. */
    private static int compareSegment(String left, String right) {
        if (isNumeric(left) && isNumeric(right)) {
            String leftTrimmed = left.replaceFirst("^0+(?=\\d)", "");
            String rightTrimmed = right.replaceFirst("^0+(?=\\d)", "");
            return leftTrimmed.length() != rightTrimmed.length()
                    ? Integer.compare(leftTrimmed.length(), rightTrimmed.length())
                    : leftTrimmed.compareTo(rightTrimmed);
        }
        return left.compareTo(right);
    }

    private static boolean isNumeric(String segment) {
        return !segment.isEmpty() && segment.chars().allMatch(Character::isDigit);
    }
}
