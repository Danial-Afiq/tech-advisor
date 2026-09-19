package com.springboot.backend.recommendation;

/** The AI service call itself failed (network, timeout, non-2xx, bad body). */
public class AiServiceException extends RuntimeException {
    public AiServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
