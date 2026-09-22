package com.springboot.backend.exception;

/**
 * A referenced domain row does not exist, or exists but lacks the context a
 * request needs.
 *
 * <p>Distinct from {@link IllegalArgumentException}, which the global handler
 * maps to 409 CONFLICT - a missing device is a 404, not a conflict.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
