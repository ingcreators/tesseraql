package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Job / route trigger declaration (design ch. 6.1, 6.5).
 *
 * @param schedule scheduled trigger for batch jobs
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TriggerSpec(Schedule schedule) {

    /**
     * A scheduled trigger.
     *
     * @param cron       a cron expression, e.g. {@code "0 0 2 * * ?"}
     * @param fixedDelay a fixed delay between runs, e.g. {@code "5s"}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Schedule(String cron, String fixedDelay) {
    }
}
