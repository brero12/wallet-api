package com.bryan.economy.wallet_api.fraud;

import java.util.Map;

public record FraudFlag(String accountId, String rule, int score, String eventId,
                        Map<String, Object> evidence) {}