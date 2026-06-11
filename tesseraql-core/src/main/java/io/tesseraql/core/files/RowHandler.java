package io.tesseraql.core.files;

import java.util.Map;

/** Receives one parsed row of an uploaded file; row numbers are 1-based data rows. */
@FunctionalInterface
public interface RowHandler {

    void row(long rowNumber, Map<String, Object> values) throws Exception;
}
