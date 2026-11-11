package com.nexora.core.error;

public class DomainException extends RuntimeException {
    private final String code;

    public DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() { return code; }

    public static DomainException notFound(String resource) {
        return new DomainException("NOT_FOUND", resource + " was not found");
    }

    public static DomainException conflict(String message) {
        return new DomainException("CONFLICT", message);
    }

    public static DomainException invalid(String message) {
        return new DomainException("INVALID_REQUEST", message);
    }
}
