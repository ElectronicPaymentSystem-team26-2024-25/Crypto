package com.positive.psp.cryptopayment.service.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "payment_intents")
public class PaymentIntent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String paymentId;          // interni ID plug-ina

    @Column(nullable = false)
    private String merchantOrderId;    // ID narudžbine iz PSP core-a

    @Column(nullable = false, unique = true)
    private String btcAddress;         // dodeljena testnet adresa

    @Column
    private String btcAmount;          // opcionalno (fiksni iznos u BTC)

    @Column(nullable = false)
    private String status;             // PENDING | CONFIRMED | FAILED | EXPIRED

    @Column
    private String txid;               // blockchain tx hash, ako postoji

    @Column
    private OffsetDateTime expiresAt;  // rok važenja adrese

    @Column(updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column
    private OffsetDateTime updatedAt;

    @Column private String successUrl;
    @Column private String failedUrl;
    @Column private String errorUrl;

    @Column(precision = 19, scale = 4)     // dovoljno za RSD/EUR
    private java.math.BigDecimal fiatAmount;

    @Column(length = 8)
    private String fiatCurrency;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
    // get/set ...
    public java.math.BigDecimal getFiatAmount() { return fiatAmount; }
    public void setFiatAmount(java.math.BigDecimal fiatAmount) { this.fiatAmount = fiatAmount; }
    public String getFiatCurrency() { return fiatCurrency; }
    public void setFiatCurrency(String fiatCurrency) { this.fiatCurrency = fiatCurrency; }


    // get/set
    public String getSuccessUrl() { return successUrl; }
    public void setSuccessUrl(String v) { successUrl = v; }
    public String getFailedUrl() { return failedUrl; }
    public void setFailedUrl(String v) { failedUrl = v; }
    public String getErrorUrl() { return errorUrl; }
    public void setErrorUrl(String v) { errorUrl = v; }
    // === Getteri i setteri ===
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }

    public String getMerchantOrderId() { return merchantOrderId; }
    public void setMerchantOrderId(String merchantOrderId) { this.merchantOrderId = merchantOrderId; }

    public String getBtcAddress() { return btcAddress; }
    public void setBtcAddress(String btcAddress) { this.btcAddress = btcAddress; }

    public String getBtcAmount() { return btcAmount; }
    public void setBtcAmount(String btcAmount) { this.btcAmount = btcAmount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getTxid() { return txid; }
    public void setTxid(String txid) { this.txid = txid; }

    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
