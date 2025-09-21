package com.positive.psp.cryptopayment.service;

import com.positive.psp.cryptopayment.api.dto.CreatePaymentRequest;
import com.positive.psp.cryptopayment.api.dto.CreatePaymentResponse;
import com.positive.psp.cryptopayment.api.dto.GetStatusResponse;
import com.positive.psp.cryptopayment.provider.BitcoinClient;
import com.positive.psp.cryptopayment.psp.OrderStatusCallbackRequest;
import com.positive.psp.cryptopayment.psp.PspCoreClient;
import com.positive.psp.cryptopayment.service.domain.PaymentIntent;
import com.positive.psp.cryptopayment.service.domain.PaymentIntentRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Service
public class CryptoPaymentService {

    private static final Logger log = LoggerFactory.getLogger(CryptoPaymentService.class);

    private final BitcoinClient bitcoinClient;
    private final PaymentIntentRepository repo;
    private final PspCoreClient pspCoreClient;
    private final FxRateService fx;
    private final int confirmationsRequired;
    private final String webhookSecret;

    public CryptoPaymentService(
            BitcoinClient bitcoinClient,
            PaymentIntentRepository repo,
            PspCoreClient pspCoreClient,
            FxRateService fx,
            @Value("${crypto.blockcypher.confirmations-required:1}") int confirmationsRequired,
            @Value("${crypto.blockcypher.webhook-secret:change-me}") String webhookSecret
    ) {
        this.bitcoinClient = bitcoinClient;
        this.repo = repo;
        this.pspCoreClient = pspCoreClient;
        this.fx = fx;
        this.confirmationsRequired = Math.max(0, confirmationsRequired);
        this.webhookSecret = webhookSecret;
    }

    @Transactional
    public CreatePaymentResponse createPayment(CreatePaymentRequest req) {
        // 1) identifikator i rok
        final String paymentId = UUID.randomUUID().toString();
        final long ttl = Optional.ofNullable(req.expiresInSec()).orElse(900);
        final OffsetDateTime expiresAt = OffsetDateTime.now().plusSeconds(ttl);

        // 2) nova (testnet) adresa
        final String address = bitcoinClient.allocateNewAddress(paymentId);
        log.info("Created crypto paymentId={}, address={}", paymentId, address);

        // 3) FX preračun: fiat -> BTC (UP zaokruživanje radi “ne manje od”)
        final String fiat = Optional.ofNullable(req.currencyFiat()).orElse("RSD");
        final BigDecimal fiatAmount = Optional.ofNullable(req.amountFiat()).orElse(BigDecimal.ZERO);
        final BigDecimal btcAmount = fx.toBtc(fiatAmount, fiat); // već ima scale i RoundingMode.UP

        // 4) upis (PENDING)
        PaymentIntent intent = new PaymentIntent();
        intent.setPaymentId(paymentId);
        intent.setMerchantOrderId(req.merchantOrderId());
        intent.setBtcAddress(address);
        intent.setBtcAmount(btcAmount.signum() > 0 ? btcAmount.toPlainString() : null);
        intent.setFiatAmount(fiatAmount);
        intent.setFiatCurrency(fiat);
        intent.setStatus("PENDING");
        intent.setExpiresAt(expiresAt);
        repo.save(intent);

        // 5) webhook registracija (best-effort)
        try {
            bitcoinClient.registerWebhook(address, webhookSecret);
        } catch (Exception e) {
            log.warn("Webhook registration failed for {}: {}", address, e.getMessage());
        }

        // 6) BTC URI
        final String btcUri = "bitcoin:" + address + (intent.getBtcAmount() != null ? "?amount=" + intent.getBtcAmount() : "");

        return new CreatePaymentResponse(
                paymentId,
                address,
                intent.getBtcAmount(),
                btcUri,
                null,
                expiresAt
        );
    }

    /** Attach success/failed/error URL-ove koje dobijaš od PSP-a. */
    @Transactional
    public void attachRedirectUrls(String paymentId, String successUrl, String failedUrl, String errorUrl) {
        var intent = repo.findByPaymentId(paymentId)
                .orElseThrow(() -> new NoSuchElementException("Payment not found: " + paymentId));
        intent.setSuccessUrl(nullIfBlank(successUrl));
        intent.setFailedUrl(nullIfBlank(failedUrl));
        intent.setErrorUrl(nullIfBlank(errorUrl));
        repo.save(intent);
    }

    @Transactional
    public GetStatusResponse getStatusByPayment(String paymentId, String merchantOrderId, String btcAddress) {
        PaymentIntent intent = (paymentId != null && !paymentId.isBlank())
                ? repo.findByPaymentId(paymentId).orElseThrow(() -> new NoSuchElementException("Payment not found: " + paymentId))
                : repo.findByMerchantOrderId(merchantOrderId).orElseThrow(() -> new NoSuchElementException("Order not found: " + merchantOrderId));

        if (btcAddress != null && !btcAddress.isBlank()) {
            intent.setBtcAddress(btcAddress);
        }

        // timeout -> EXPIRED (idempotentno)
        if (hasExpired(intent) && !isFinal(intent)) {
            intent.setStatus("EXPIRED");
            repo.save(intent);
            safeNotifyPsp(intent.getMerchantOrderId(), "TIMEOUT", intent.getTxid());
        }

        // ako je finalno, vrati bez poziva provajdera
        if (isFinal(intent)) {
            return responseOf(intent, null, null);
        }

        // provider status
        var st = bitcoinClient.checkStatus(intent);
        String raw = safe(st.status());
        Integer confs = st.confirmations();
        int conf = confs == null ? 0 : confs;

        // === NOVO: UNDERPAID => FAIL odmah ===
        if ("PENDING_UNDERPAID".equalsIgnoreCase(raw)) {
            if (!"FAILED".equals(intent.getStatus())) {
                intent.setStatus("FAILED");
                if (st.txid() != null) intent.setTxid(st.txid());
                repo.save(intent);
                safeNotifyPsp(intent.getMerchantOrderId(), "FAILED", intent.getTxid());
            }
            return responseOf(intent, confs, st.txid());
        }

        // ostala mapiranja (postojeća logika)
        String mapped = switch (raw) {
            case "CONFIRMED" -> "CONFIRMED";
            case "FAILED"    -> "FAILED";
            default          -> "PENDING"; // uključuje i PENDING_OVERPAID, PENDING itd.
        };

        boolean becameConfirmed = false;
        boolean becameFailed = false;

        if ("CONFIRMED".equals(mapped) && conf >= confirmationsRequired) {
            if (!"CONFIRMED".equals(intent.getStatus())) becameConfirmed = true;
            intent.setStatus("CONFIRMED");
            if (st.txid() != null) intent.setTxid(st.txid());
            repo.save(intent);
        } else if ("FAILED".equals(mapped) && !"FAILED".equals(intent.getStatus())) {
            becameFailed = true;
            intent.setStatus("FAILED");
            if (st.txid() != null) intent.setTxid(st.txid());
            repo.save(intent);
        }

        if (becameConfirmed) {
            safeNotifyPsp(intent.getMerchantOrderId(), "SUCCESS", intent.getTxid());
        } else if (becameFailed) {
            safeNotifyPsp(intent.getMerchantOrderId(), "FAILED", intent.getTxid());
        }

        return responseOf(intent, confs, st.txid());
    }


    @Transactional
    public GetStatusResponse getStatusByMerchantOrder(String merchantOrderId) {
        var intent = repo.findByMerchantOrderId(merchantOrderId)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + merchantOrderId));
        return getStatusByPayment(intent.getPaymentId(), merchantOrderId, intent.getBtcAddress());
    }

    // --- webhook (opciono, ako ga koristiš) ---
    @Transactional
    public void processWebhook(Map<String, Object> body) {
        String txid = extractTxHash(body);
        String address = extractAnyAddress(body);

        if (address == null || address.isBlank()) {
            log.warn("Webhook without address, ignoring. body={}", body);
            return;
        }

        var opt = repo.findByBtcAddress(address);
        if (opt.isEmpty()) {
            log.warn("Webhook for unknown address={}, ignoring. txid={}", address, txid);
            return;
        }
        var intent = opt.get();

        if (isFinal(intent)) { // idempotentno
            log.info("Webhook idempotent for paymentId={}, status={}", intent.getPaymentId(), intent.getStatus());
            return;
        }

        var st = bitcoinClient.checkStatus(intent);
        String raw = safe(st.status());
        Integer confs = st.confirmations();
        int conf = confs == null ? 0 : confs;

        // === NOVO: UNDERPAID => FAIL odmah ===
        if ("PENDING_UNDERPAID".equalsIgnoreCase(raw)) {
            intent.setStatus("FAILED");
            intent.setTxid(st.txid() != null ? st.txid() : txid);
            repo.save(intent);
            safeNotifyPsp(intent.getMerchantOrderId(), "FAILED", intent.getTxid());
            log.info("Payment FAILED (UNDERPAID) via webhook: paymentId={}, txid={}", intent.getPaymentId(), intent.getTxid());
            return;
        }

        if ("CONFIRMED".equalsIgnoreCase(raw) && conf >= confirmationsRequired) {
            intent.setStatus("CONFIRMED");
            intent.setTxid(st.txid() != null ? st.txid() : txid);
            repo.save(intent);
            safeNotifyPsp(intent.getMerchantOrderId(), "SUCCESS", intent.getTxid());
            log.info("Payment CONFIRMED via webhook: paymentId={}, txid={}, conf={}", intent.getPaymentId(), intent.getTxid(), conf);
            return;
        }

        if ("FAILED".equalsIgnoreCase(raw)) {
            intent.setStatus("FAILED");
            intent.setTxid(st.txid() != null ? st.txid() : txid);
            repo.save(intent);
            safeNotifyPsp(intent.getMerchantOrderId(), "FAILED", intent.getTxid());
            log.info("Payment FAILED via webhook: paymentId={}, txid={}", intent.getPaymentId(), intent.getTxid());
            return;
        }

        // ostavi PENDING (uključivo PENDING_OVERPAID / PENDING)
        log.info("Webhook left payment PENDING: paymentId={}, addr={}, txidCandidate={}, rawStatus={}",
                intent.getPaymentId(), address, txid, raw);
    }

    // --- helpers ---

    private boolean isFinal(PaymentIntent i) {
        String s = i.getStatus();
        return "CONFIRMED".equals(s) || "FAILED".equals(s) || "EXPIRED".equals(s);
    }

    private boolean hasExpired(PaymentIntent i) {
        return i.getExpiresAt() != null && OffsetDateTime.now().isAfter(i.getExpiresAt());
    }

    private GetStatusResponse responseOf(PaymentIntent i, Integer confirmations, String txid) {
        return new GetStatusResponse(
                i.getPaymentId(),
                i.getMerchantOrderId(),
                i.getStatus(),
                confirmations,
                txid != null ? txid : i.getTxid(),
                null,
                "CONFIRMED".equals(i.getStatus()) ? OffsetDateTime.now() : null
        );
    }

    private void safeNotifyPsp(String merchantOrderId, String status, String txid) {
        try {
            pspCoreClient.notifyStatus(new OrderStatusCallbackRequest(merchantOrderId, status, txid));
        } catch (Exception ex) {
            log.warn("PSP callback failed for order {}: {}", merchantOrderId, ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String extractTxHash(Map<String, Object> body) {
        Object txObj = body.get("tx");
        if (txObj instanceof Map<?, ?> tx) {
            Object h = ((Map<String, Object>) tx).get("hash");
            if (h != null) return String.valueOf(h);
        }
        Object direct = body.get("hash");
        return direct != null ? String.valueOf(direct) : null;
    }

    @SuppressWarnings("unchecked")
    private String extractAnyAddress(Map<String, Object> body) {
        Object adds = body.get("addresses");
        if (adds instanceof java.util.List<?> list && !list.isEmpty()) {
            Object a0 = list.get(0);
            if (a0 != null) return String.valueOf(a0);
        }
        Object single = body.get("address");
        if (single != null) return String.valueOf(single);
        Object outs = body.get("outputs");
        if (outs instanceof java.util.List<?> outsList) {
            for (Object o : outsList) {
                if (o instanceof Map<?, ?> m) {
                    Object oa = ((Map<String, Object>) m).get("addresses");
                    if (oa instanceof java.util.List<?> la && !la.isEmpty()) {
                        Object a0 = la.get(0);
                        if (a0 != null) return String.valueOf(a0);
                    }
                }
            }
        }
        return null;
    }

    private String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
