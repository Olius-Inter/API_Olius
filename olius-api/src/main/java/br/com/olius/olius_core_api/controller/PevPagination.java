package br.com.olius.olius_core_api.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class PevPagination {
    private PevPagination() { }

    static int page(String value) {
        return parse(value, 0, 0, Integer.MAX_VALUE);
    }

    static int size(String value) {
        return parse(value, 20, 1, 100);
    }

    private static int parse(String value, int defaultValue, int min, int max) {
        if (value == null) return defaultValue;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= min && parsed <= max) return parsed;
        } catch (NumberFormatException ignored) {
            // Invalid values use the same HTTP response as out-of-range values.
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }
}
