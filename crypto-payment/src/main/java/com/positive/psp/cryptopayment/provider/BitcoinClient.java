package com.positive.psp.cryptopayment.provider;

import com.positive.psp.cryptopayment.service.domain.PaymentIntent;
import java.util.Optional;

public interface BitcoinClient {

    /** Kreira novu adresu za uplatu (jedna adresa po payment intentu). */
    String allocateNewAddress(String paymentId);

    /** Opcioni fiksni BTC iznos: konverzija FIAT->BTC radiš lokalno ili preko provajdera (za POC možeš ostaviti null). */
    default Optional<String> quoteBtcAmount(String fiatCurrency, String fiatAmount) { return Optional.empty(); }

    /** Proveri status (confirmations/txid) za adresu ili paymentId. */
    PaymentStatus checkStatus(PaymentIntent intent);

    /** Registruje webhook kod provajdera (ako je podržano). */
    void registerWebhook(String address, String secret);

    record PaymentStatus(String status, Integer confirmations, String txid) {}
}
