package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A single step in a batch pipeline (design ch. 6.5).
 *
 * <p>The first milestone supports SQL steps; {@code send}/{@code transform} steps are added with
 * the large-data and file integration work.
 *
 * @param id  unique step id within the job
 * @param sql the SQL execution binding for this step
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PipelineStep(String id, SqlBinding sql) {
}
