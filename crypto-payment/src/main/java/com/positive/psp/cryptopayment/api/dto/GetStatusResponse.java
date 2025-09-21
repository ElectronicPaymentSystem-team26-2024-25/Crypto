package com.positive.psp.cryptopayment.api.dto;

import java.time.OffsetDateTime;

public record GetStatusResponse(
        String paymentId,
        String merchantOrderId,
        String status,           // PENDING | CONFIRMED | FAILED | EXPIRED
        Integer confirmations,
        String txid,
        OffsetDateTime seenAt,
        OffsetDateTime confirmedAt
) {}
