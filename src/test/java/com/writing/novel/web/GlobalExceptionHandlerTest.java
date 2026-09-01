package com.writing.novel.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** p14-02: null message must not collapse to a bare 内部错误. */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void genericException_nullMessage_includesExceptionClassName() {
        ResponseEntity<Map<String, String>> resp = handler.handleGeneric(new NullPointerException());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        String error = resp.getBody().get("error");
        assertTrue(error.contains("NullPointerException"), error);
        assertTrue(error.startsWith("内部错误"), error);
    }
}
