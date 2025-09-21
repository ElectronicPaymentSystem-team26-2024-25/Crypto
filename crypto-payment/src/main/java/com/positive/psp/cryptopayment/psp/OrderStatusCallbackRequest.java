package com.positive.psp.cryptopayment.psp;

public record OrderStatusCallbackRequest(
        String merchantOrderId,
        String status,       // SUCCESS | FAILED | TIMEOUT
        String txid
) {}
