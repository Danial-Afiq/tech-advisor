package com.springboot.backend.exception;

/**
 * An unknown {@code sort} query value. Mapped to 400 by
 * {@link GlobalExceptionHandler}; it extends {@link IllegalArgumentException}
 * but has its own, more specific handler, so it is not caught by the generic
 * 409 mapping.
 */
public class InvalidSortException
        extends IllegalArgumentException {

    public InvalidSortException(
            String message) {

        super(message);
    }
}
