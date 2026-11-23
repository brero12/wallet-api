package com.bryan.economy.wallet_api.ledger;

import com.bryan.economy.wallet_api.ledger.LedgerExceptions.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private final io.micrometer.core.instrument.MeterRegistry registry;

    public ApiExceptionHandler(io.micrometer.core.instrument.MeterRegistry registry) {
        this.registry = registry;
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ProblemDetail insufficient(InsufficientFundsException e) {
        registry.counter("ledger.rejections", "reason", "insufficient_funds").increment();
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail conflict(IdempotencyConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    public ProblemDetail badRequest(BadRequestException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}