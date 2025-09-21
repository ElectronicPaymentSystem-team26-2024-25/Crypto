package com.positive.psp.cryptopayment.provider.blockcypher;

import com.positive.psp.cryptopayment.provider.BitcoinClient;
import com.positive.psp.cryptopayment.service.domain.PaymentIntent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

@Component
public class BlockCypherClient implements BitcoinClient {

    private final RestClient http;
    private final String baseUrl;
    private final String token;

    /** Javni base URL crypto servisa – koristi se za webhook URL */
    private final String publicBaseUrl;

    /** Demo flag – dozvoljava 0-conf “instant” potvrdu kada stigne očekivani iznos */
    private final boolean demoZeroConf;

    /** Dozvoljena tolerancija u satoshijima za poređenje iznosa */
    private final long toleranceSats;


    public BlockCypherClient(
            @Value("${crypto.blockcypher.base-url}") String baseUrl,
            @Value("${crypto.blockcypher.token:}") String token,
            @Value("${app.public-base-url:https://localhost:8095}") String publicBaseUrl,
            @Value("${crypto.demo.zero-conf:true}") boolean demoZeroConf,
            @Value("${crypto.payment.underpaid-tolerance-sats:0}") long toleranceSats
    ) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.publicBaseUrl = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
        this.demoZeroConf = demoZeroConf;
        this.toleranceSats = Math.max(0L, toleranceSats);
        this.http = RestClient.builder().build();
    }

    @Override
    public String allocateNewAddress(String paymentId) {
        String uri = baseUrl + "/addrs?bech32=true" + (token.isBlank() ? "" : "&token=" + token);
        try {
            var resp = http.post().uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(Map.class);
            return resp != null ? (String) resp.get("address") : null;
        } catch (HttpClientErrorException.TooManyRequests e) {
            // rate-limit – prosledi dalje
            throw e;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public PaymentStatus checkStatus(PaymentIntent intent) {
        try {
            String uri = baseUrl + "/addrs/" + intent.getBtcAddress() + "/full"
                    + (token.isBlank() ? "" : "?token=" + token);

            Map<String, Object> resp = http.get().uri(uri).retrieve().body(Map.class);
            if (resp == null) return new PaymentStatus("PENDING", 0, null);

            long expectedSats = 0L;
            if (intent.getBtcAmount() != null && !intent.getBtcAmount().isBlank()) {
                expectedSats = new java.math.BigDecimal(intent.getBtcAmount())
                        .movePointRight(8) // BTC -> sats
                        .setScale(0, java.math.RoundingMode.UP)
                        .longValue();
            }

            Acc acc = new Acc(intent.getBtcAddress());

            // confirmed txs (detalji sa outputs)
            var txs = (java.util.List<Map<String, Object>>) resp.get("txs");
            if (txs != null) for (var tx : txs) acc.addConfirmed(tx);

            // unconfirmed sažeci
            var unconf = (java.util.List<Map<String, Object>>) resp.get("unconfirmed_txrefs");
            if (unconf != null) for (var u : unconf) acc.addUnconfirmed(u);

            long receivedTotal = acc.receivedConfirmedSats + acc.receivedUnconfirmedSats;

            // 1) NEMA uplate još uvek -> PENDING
            if (receivedTotal == 0L) {
                return new PaymentStatus("PENDING", acc.maxConfirmations, null);
            }

            // 2) Ako je dovoljno (>= expected - tolerance)
            boolean enough = (expectedSats > 0)
                    ? receivedTotal >= Math.max(0L, expectedSats - toleranceSats)
                    : true; // ako nisi setovao BTC iznos, sve tretiraj kao "enough"

            if (enough) {
                // demo 0-conf: odmah CONFIRMED (service će uporediti confirmationsRequired)
                if (demoZeroConf) {
                    return new PaymentStatus("CONFIRMED", acc.maxConfirmations, acc.anyTxid());
                }
                // realnije: CONFIRMED tek kada postoji bar 1 conf (service posle proverava prag)
                if (acc.maxConfirmations > 0) {
                    return new PaymentStatus("CONFIRMED", acc.maxConfirmations, acc.firstConfirmedTxid);
                }
                // još nema potvrda → i dalje pending
                return new PaymentStatus("PENDING", 0, null);
            }

            // 3) UNDERPAID (manje od očekivanog):
            //    - bez potvrda → još PENDING (ne failuj prerano)
            //    - sa >=1 potvrdom → FAILED (potvrđen underpay)
            if (acc.maxConfirmations > 0) {
                return new PaymentStatus("FAILED", acc.maxConfirmations, acc.firstConfirmedTxid);
            }
            return new PaymentStatus("PENDING", 0, null);

        } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests e) {
            System.err.println("BlockCypher 429: " + e.getMessage());
            return new PaymentStatus("PENDING", 0, null);
        } catch (Exception ex) {
            System.err.println("BlockCypher error: " + ex.getMessage());
            return new PaymentStatus("PENDING", 0, null);
        }
    }

    /** Registracija webhook-a (opciono). Ako je demo zero-conf, može i 'unconfirmed-tx'. */
    @Override
    public void registerWebhook(String address, String secret) {
        String event = demoZeroConf ? "unconfirmed-tx" : "confirmed-tx";
        String url = baseUrl + "/hooks" + (token.isBlank() ? "" : "?token=" + token);

        Map<String, Object> body = Map.of(
                "event", event,
                "address", address,
                "url", publicBaseUrl + "/api/crypto/webhook?secret=" + secret
        );

        http.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    // === helpers ===

    private static class Acc {
        final String addr;
        long receivedConfirmedSats = 0L;
        long receivedUnconfirmedSats = 0L;
        int maxConfirmations = 0;
        String firstConfirmedTxid = null;
        String firstUnconfirmedTxid = null;

        Acc(String addr) { this.addr = addr; }

        @SuppressWarnings("unchecked")
        void addConfirmed(Map<String, Object> tx) {
            var outs = (List<Map<String, Object>>) tx.get("outputs");
            if (outs != null) {
                for (var out : outs) {
                    var addrs = (List<String>) out.get("addresses");
                    if (addrs != null && addrs.contains(addr)) {
                        receivedConfirmedSats += ((Number) out.getOrDefault("value", 0)).longValue();
                    }
                }
            }
            Integer conf = toInt(tx.get("confirmations"));
            if (conf != null) maxConfirmations = Math.max(maxConfirmations, conf);
            if (firstConfirmedTxid == null) firstConfirmedTxid = (String) tx.get("hash");
        }

        void addUnconfirmed(Map<String, Object> u) {
            Object a = u.get("address");
            if (a != null && a.toString().equalsIgnoreCase(addr)) {
                receivedUnconfirmedSats += ((Number) u.getOrDefault("value", 0)).longValue();
            }
            if (firstUnconfirmedTxid == null) firstUnconfirmedTxid = (String) u.get("tx_hash");
        }

        String anyTxid() {
            return firstConfirmedTxid != null ? firstConfirmedTxid : firstUnconfirmedTxid;
        }

        private Integer toInt(Object o) {
            if (o instanceof Integer i) return i;
            if (o instanceof Number n) return n.intValue();
            try { return o == null ? null : Integer.parseInt(o.toString()); } catch (Exception ignored) {}
            return null;
        }
    }
}
