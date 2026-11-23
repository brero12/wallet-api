package com.bryan.economy.wallet_api.ledger;

import com.bryan.economy.wallet_api.ledger.Dtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
public class WalletController {

    private final LedgerService ledger;

    public WalletController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @PostMapping("/grants")
    public ResponseEntity<LedgerResult> grant(@RequestHeader("Idempotency-Key") String key,
                                              @Valid @RequestBody GrantRequest req) {
        return respond(ledger.grant(key, req));
    }

    @PostMapping("/purchases")
    public ResponseEntity<LedgerResult> purchase(@RequestHeader("Idempotency-Key") String key,
                                                 @Valid @RequestBody PurchaseRequest req) {
        return respond(ledger.purchase(key, req));
    }

    @PostMapping("/transfers")
    public ResponseEntity<LedgerResult> transfer(@RequestHeader("Idempotency-Key") String key,
                                                 @Valid @RequestBody TransferRequest req) {
        return respond(ledger.transfer(key, req));
    }

    @GetMapping("/players/{playerId}/balance")
    public BalanceResponse balance(@PathVariable String playerId) {
        return ledger.balance(playerId);
    }

    /** 201 for a new transaction, 200 for an idempotent replay. */
    private ResponseEntity<LedgerResult> respond(LedgerResult result) {
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }
}