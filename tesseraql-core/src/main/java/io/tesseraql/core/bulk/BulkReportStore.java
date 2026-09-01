package io.tesseraql.core.bulk;

import java.util.Optional;

/**
 * Holds a bulk action's outcome report for the redirect round trip
 * (docs/bulk-report.md decision 6): the browser leg of a bulk endpoint stores the report
 * under a handle, answers a redirect carrying that handle, and the re-rendered list picks
 * the report up. Scoped to the acting principal, TTL-bounded, re-readable — a refresh
 * re-reads it — and gone without ceremony after expiry: the report is a convenience of the
 * moment, and the durable record is workflow history.
 */
public interface BulkReportStore {

    /** Stores the report payload under a fresh handle for the subject; returns the handle. */
    String put(String subject, String payload, long ttlMillis);

    /**
     * The payload stored under the handle, when it exists, has not expired, and belongs to
     * this subject — a foreign or expired handle is simply absent, never an error.
     */
    Optional<String> find(String handle, String subject);
}
