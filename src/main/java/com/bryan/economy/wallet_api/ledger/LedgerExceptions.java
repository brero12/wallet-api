package com.bryan.economy.wallet_api.ledger;

public final class LedgerExceptions {
    private LedgerExceptions() {}

    public static class InsufficientFundsException extends RuntimeException {
        public InsufficientFundsException(String msg) { super(msg); }
    }

    /** Same Idempotency-Key reused with a different request body. */
    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String msg) { super(msg); }
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String msg) { super(msg); }
    }

    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String msg) { super(msg); }
    }
}