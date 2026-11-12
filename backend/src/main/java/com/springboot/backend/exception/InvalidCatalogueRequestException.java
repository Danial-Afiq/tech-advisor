package com.springboot.backend.exception;

/** A catalogue request that is well-formed but cannot be applied; mapped to 400. */
public class InvalidCatalogueRequestException extends RuntimeException {

    public InvalidCatalogueRequestException(String message) {
        super(message);
    }
}
