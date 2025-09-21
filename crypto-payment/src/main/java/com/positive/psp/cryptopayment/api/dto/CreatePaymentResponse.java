package com.positive.psp.cryptopayment.api.dto;

import java.time.OffsetDateTime;

public record CreatePaymentResponse(
        String paymentId,        // interni ID plug-ina
        String btcAddress,       // nova testnet adresa
        String btcAmount,        // opcionalno, ako radiš fiksni BTC iznos
        String btcUri,           // "bitcoin:<address>?amount=<btcAmount>"
        String qrBase64,         // opcionalno (može FE da generiše)
        OffsetDateTime expiresAt
) {}
