package com.bryan.economy.wallet_api.demo;

import com.bryan.economy.wallet_api.ledger.Dtos.*;
import com.bryan.economy.wallet_api.ledger.LedgerExceptions.BadRequestException;
import com.bryan.economy.wallet_api.ledger.LedgerService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** Demo-only: fires canned fraud scenarios so a viewer can see detection live. */
@RestController
@RequestMapping("/v1/demo")
public class DemoController {

    private final LedgerService ledger;

    public DemoController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @PostMapping("/attack/{kind}")
    public Map<String, String> attack(@PathVariable String kind) {
        String p = kind + "-" + UUID.randomUUID().toString().substring(0, 6);
        switch (kind) {
            case "velocity" -> {
                grant(p, 1000, "NA");
                for (int i = 0; i < 8; i++) purchase(p, 1, "NA");
            }
            case "travel" -> {
                grant(p, 1000, "NA");
                purchase(p, 10, "EU");
            }
            case "mule" -> {
                grant(p, 1000, "NA");
                ledger.transfer(key(), new TransferRequest(p, p + "-recv", 950, "fc26", "NA"));
            }
            default -> throw new BadRequestException("Unknown attack: " + kind);
        }
        return Map.of("attack", kind, "playerId", p);
    }

    private void grant(String p, long amt, String region) {
        ledger.grant(key(), new GrantRequest(p, amt, "fc26", region));
    }

    private void purchase(String p, long amt, String region) {
        ledger.purchase(key(), new PurchaseRequest(p, amt, "potion", "fc26", region));
    }

    private static String key() {
        return "demo-" + UUID.randomUUID();
    }
}