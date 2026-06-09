package io.tesseraql.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TesseraQLTest {
    @Test
    void normalizesId() {
        assertEquals("users.search", TesseraQL.normalizeId(" users.search "));
    }

    @Test
    void rejectsNullId() {
        assertThrows(NullPointerException.class, () -> TesseraQL.normalizeId(null));
    }
}
