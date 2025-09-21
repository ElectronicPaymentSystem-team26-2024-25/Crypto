package com.positive.psp.cryptopayment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

@Service
public class FxRateService {
    private static final Logger log = LoggerFactory.getLogger(FxRateService.class);

    /**
     * Kurs je definisan kao: 1 FIAT = X BTC  (BTC-per-fiat)
     * Primer: za RSD 0.00000005 znači da 1 RSD vredi 5e-8 BTC.
     */
    private final Map<String, BigDecimal> rates = new HashMap<>();
    private final int scale;

    public FxRateService(
            @Value("${crypto.fx.rate.RSD:0.00000005}") String rsdStr, // default za demo
            @Value("${crypto.fx.rate.EUR:0.00001500}") String eurStr, // default za demo
            @Value("${crypto.fx.decimals:8}") int scale
    ) {
        this.scale = Math.max(0, scale);

        rates.put("RSD", parseRate("RSD", rsdStr, new BigDecimal("0.00000005"))); // 5e-8
        rates.put("EUR", parseRate("EUR", eurStr, new BigDecimal("0.00001500"))); // 1.5e-5
    }

    /** Vraća kurs (BTC po 1 FIAT). */
    public BigDecimal quote(String fiat) {
        String k = fiat == null ? "RSD" : fiat.toUpperCase();
        return rates.getOrDefault(k, BigDecimal.ZERO);
    }

    /** Koliko BTC za zadati iznos u FIAT-u. */
    public BigDecimal toBtc(BigDecimal fiatAmount, String fiat) {
        if (fiatAmount == null) return BigDecimal.ZERO;
        BigDecimal rate = quote(fiat);
        return fiatAmount.multiply(rate).setScale(scale, RoundingMode.UP);
    }

    public int scale() { return scale; }

    private BigDecimal parseRate(String code, String raw, BigDecimal fallback) {
        try {
            if (raw == null || raw.isBlank()) return fallback;
            // podrži i zarez i tačku
            String norm = raw.trim().replace(',', '.');
            BigDecimal v = new BigDecimal(norm);
            if (v.signum() <= 0) {
                log.warn("FX rate for {} is non-positive ({}). Using fallback {}.", code, raw, fallback);
                return fallback;
            }
            return v;
        } catch (Exception e) {
            log.warn("Cannot parse FX rate for {} from '{}': {}. Using fallback {}.",
                    code, raw, e.getMessage(), fallback);
            return fallback;
        }
    }
}
