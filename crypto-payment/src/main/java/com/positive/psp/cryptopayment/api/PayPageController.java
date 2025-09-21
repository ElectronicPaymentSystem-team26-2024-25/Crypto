// src/main/java/com/positive/psp/cryptopayment/api/PayPageController.java
package com.positive.psp.cryptopayment.api;

import com.positive.psp.cryptopayment.service.domain.PaymentIntentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class PayPageController {
    private static final Logger log = LoggerFactory.getLogger(PayPageController.class);

    private final PaymentIntentRepository repo;
    public PayPageController(PaymentIntentRepository repo){ this.repo = repo; }

    @GetMapping("/pay/{paymentId}")
    public String pay(@PathVariable String paymentId, Model model) {
        log.info("Open pay page for paymentId={}", paymentId);
        var intent = repo.findByPaymentId(paymentId).orElse(null);
        if (intent == null) {
            log.error("PaymentIntent not found for paymentId={}", paymentId);
            return "redirect:/pay-not-found"; // vidi korak 2
        }
        model.addAttribute("paymentId", intent.getPaymentId());
        model.addAttribute("merchantOrderId", intent.getMerchantOrderId());
        model.addAttribute("btcAddress", intent.getBtcAddress());
        model.addAttribute("btcUri", "bitcoin:" + intent.getBtcAddress());
        model.addAttribute("successUrl", intent.getSuccessUrl());
        model.addAttribute("failedUrl", intent.getFailedUrl());
        model.addAttribute("errorUrl", intent.getErrorUrl());


        // NOVO:
        model.addAttribute("fiatAmount", intent.getFiatAmount());       // BigDecimal (opciono, ako si dodao u entitet)
        model.addAttribute("fiatCurrency", intent.getFiatCurrency());   // npr. "RSD" / "EUR"
        model.addAttribute("btcAmount", intent.getBtcAmount());         // String (npr. "0.00005678")
        model.addAttribute("expiresAt", intent.getExpiresAt());         // OffsetDateTime -> Thymeleaf će ubaciti ISO string

        return "crypto-pay"; // <-- NOVI naziv templejta
    }


}
