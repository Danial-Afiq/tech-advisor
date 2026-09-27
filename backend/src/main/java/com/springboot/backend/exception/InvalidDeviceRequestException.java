package com.springboot.backend.exception;

public class InvalidDeviceRequestException
        extends RuntimeException {

    public InvalidDeviceRequestException(
            String message) {

        super(message);
    }
}