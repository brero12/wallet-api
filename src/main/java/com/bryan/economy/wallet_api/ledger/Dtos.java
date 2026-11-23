package com.bryan.economy.wallet_api.ledger;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.UUID;

public final class Dtos {
    private Dtos() {}

    public record GrantRequest(
            @NotBlank String playerId,
            @Positive long amount,
            @NotBlank String gameId,
            @NotBlank String region) {}

    public record PurchaseRequest(
            @NotBlank String playerId,
            @Positive long amount,
            @NotBlank String itemId,
            @NotBlank String gameId,
            @NotBlank String region) {}

    public record TransferRequest(
            @NotBlank String fromPlayerId,
            @NotBlank String toPlayerId,
            @Positive long amount,
            @NotBlank String gameId,
            @NotBlank String region) {}

    public record LedgerResult(UUID txnId, String type, boolean replayed) {}

    public record BalanceResponse(String playerId, long balance) {}

    /** The event published to Kafka via the outbox. */
    public record TxnEvent(
            String eventId, String type, String playerId, String counterpartyId,
            long amount, String currency, String itemId,
            String gameId, String region, Instant occurredAt) {}
}