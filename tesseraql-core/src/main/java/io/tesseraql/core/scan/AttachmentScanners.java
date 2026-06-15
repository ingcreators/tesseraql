package io.tesseraql.core.scan;

import java.util.Iterator;
import java.util.ServiceLoader;

/**
 * Discovers the installed {@link AttachmentScanner} (roadmap Phase 30 slice 3): the first
 * ServiceLoader-registered scanner, or {@link NoopAttachmentScanner} when none is installed — the
 * FileCodec/SecretResolver discovery idiom. An app enables real scanning by adding a scanner module
 * to the classpath; no config flag is needed.
 */
public final class AttachmentScanners {

    private AttachmentScanners() {
    }

    /** The installed scanner, or the no-op default. */
    public static AttachmentScanner discover() {
        Iterator<AttachmentScanner> scanners = ServiceLoader.load(AttachmentScanner.class)
                .iterator();
        return scanners.hasNext() ? scanners.next() : new NoopAttachmentScanner();
    }
}
