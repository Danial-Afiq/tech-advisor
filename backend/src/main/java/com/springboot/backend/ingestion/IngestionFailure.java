package com.springboot.backend.ingestion;

/** Only these code-owned reasons may cross the sanitized run-log boundary. */
public final class IngestionFailure extends RuntimeException {
    public enum Code {
        SEARCHAPI_HTTP_400, SEARCHAPI_AUTH_FAILED, SEARCHAPI_HTTP_FAILED,
        SEARCHAPI_INVALID_TOKEN, SEARCHAPI_MALFORMED_RESPONSE,
        SEARCHAPI_NO_MATCH, SEARCHAPI_AMBIGUOUS_MATCH, SEARCHAPI_NO_ELIGIBLE_PRODUCT,
        EMBEDDING_FAILED, EMBEDDING_CONTRACT_MISMATCH
    }
    private final Code code;
    public IngestionFailure(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
