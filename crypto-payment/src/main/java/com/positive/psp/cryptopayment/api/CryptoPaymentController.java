package com.positive.psp.cryptopayment.api;

import com.positive.psp.cryptopayment.api.dto.CreatePaymentRequest;
import com.positive.psp.cryptopayment.api.dto.CreatePaymentResponse;
import com.positive.psp.cryptopayment.api.dto.GetStatusResponse;
import com.positive.psp.cryptopayment.api.dto.PaymentUrlResponse;
import com.positive.psp.cryptopayment.service.CryptoPaymentService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/crypto")
public class CryptoPaymentController {

    private static final Logger log = LoggerFactory.getLogger(CryptoPaymentController.class);

    private final CryptoPaymentService service;
    private final String publicBaseUrl;     // npr. https://localhost:8095
    private final String webhookSecret;     // npr. crypto.blockcypher.webhook-secret

    public CryptoPaymentController(
            CryptoPaymentService service,
            @Value("${app.public-base-url:https://localhost:8095}") String publicBaseUrl,
            @Value("${crypto.blockcypher.webhook-secret:change-me}") String webhookSecret
    ) {
        this.service = service;
        this.publicBaseUrl = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
        this.webhookSecret = webhookSecret;
    }

    /**
     * DROP-IN: prihvata PSP PaymentExecutionRequest payload i vraća paymentUrl ka crypto pay stranici.
     */
    @PostMapping("/payment")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentUrlResponse createPaymentViaPsp(@RequestBody Map<String, Object> req) {
        final String merchantOrderId = String.valueOf(req.get("merchantOrderId"));
        final BigDecimal amountFiat = new BigDecimal(String.valueOf(req.get("amount")));
        final String currencyFiat = String.valueOf(req.getOrDefault("currency", "RSD"));

        final String successUrl = String.valueOf(req.get("successUrl"));
        final String failedUrl  = String.valueOf(req.get("failedUrl"));
        final String errorUrl   = String.valueOf(req.get("errorUrl"));

        // 1) kreiraj intent/adresu
        CreatePaymentResponse created = service.createPayment(
                new CreatePaymentRequest(merchantOrderId, amountFiat, currencyFiat, 900)
        );

        // 2) upiši redirect URL-ove uz intent
        service.attachRedirectUrls(created.paymentId(), successUrl, failedUrl, errorUrl);

        // 3) vrati URL koji FE treba da otvori
        String paymentUrl = publicBaseUrl + "/pay/" + created.paymentId();
        log.info("Returning paymentUrl={}", paymentUrl);

        return new PaymentUrlResponse(created.paymentId(), paymentUrl);
    }

    /**
     * Status proveru koristi pay.html (polling) i/ili drugi klijenti.
     */
    @GetMapping("/status")
    public GetStatusResponse status(
            @RequestParam(required = false) String paymentId,
            @RequestParam(required = false) String merchantOrderId,
            @RequestParam(required = false) String btcAddress
    ) {
        return service.getStatusByPayment(paymentId, merchantOrderId, btcAddress);
    }

    /**
     * Direktno kreiranje (nije deo PSP toka – zadržano kao pomoćni endpoint).
     */
    @PostMapping("/payment/direct")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatePaymentResponse createPaymentDirect(@Valid @RequestBody CreatePaymentRequest req) {
        return service.createPayment(req);
    }

    /**
     * Webhook od provajdera (npr. BlockCypher). Validira secret i prepušta servisu.
     * Očekuje payload u obliku Map (tx hash, addresses, ...), kakav provider šalje.
     */
    @PostMapping("/webhook")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void webhook(
            @RequestParam String secret,
            @RequestBody Map<String, Object> body
    ) {
        if (!Objects.equals(secret, webhookSecret)) {
            log.warn("Invalid webhook secret");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook secret");
        }
        try {
            service.processWebhook(body); // <-- Implementacija u servisu (sledeći korak)
        } catch (Exception ex) {
            log.error("Webhook processing failed: {}", ex.getMessage(), ex);
            // 204 je već postavljen; ako želiš strože, izbaci 500:
            // throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Webhook processing failed");
        }
    }
}
