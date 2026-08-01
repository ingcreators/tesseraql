package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A job's deadline expectations (docs/batch-platform.md track E), checked by a periodic
 * managed sweep that alerts through the configured alerts channel. <b>Alert-only</b>:
 * killing an in-flight JDBC statement safely is its own project, and a false sense of
 * "timeout means stopped" is worse than an honest page.
 *
 * @param completeBy        local wall-clock time ({@code HH:mm}) by which a day's run must
 *                          have completed for that business date
 * @param runningLongerThan a duration ({@code 2h}, {@code 30m}) beyond which a still-running
 *                          execution raises the alert
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SlaSpec(String completeBy, String runningLongerThan) {
}
