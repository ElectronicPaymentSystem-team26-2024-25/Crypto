package com.positive.psp.cryptopayment.service.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, Long> {

    Optional<PaymentIntent> findByPaymentId(String paymentId);

    Optional<PaymentIntent> findByMerchantOrderId(String merchantOrderId);

    Optional<PaymentIntent> findByBtcAddress(String btcAddress);
}
