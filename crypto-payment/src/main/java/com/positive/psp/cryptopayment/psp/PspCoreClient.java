package com.positive.psp.cryptopayment.psp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PspCoreClient {

    private final RestClient http;
    private final String callbackUrl;
    private final String bearer;

    public PspCoreClient(
            @Value("${psp.core.order-status-callback-url}") String callbackUrl,
            @Value("${psp.core.auth.bearer-token}") String bearer
    ) {
        this.callbackUrl = callbackUrl;
        this.bearer = bearer;
        this.http = RestClient.builder().build();
    }

    public void notifyStatus(OrderStatusCallbackRequest req) {
        http.post()
                .uri(callbackUrl)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .toBodilessEntity();
    }
}
