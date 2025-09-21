package com.positive.psp.cryptopayment.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CreatePaymentRequest(
        @NotBlank String merchantOrderId,
        @NotNull BigDecimal amountFiat,      // npr. 1200.00
        @NotBlank String currencyFiat,       // npr. RSD ili EUR
        Integer expiresInSec                 // opcionalno: npr. 900 (15min)
) {}
