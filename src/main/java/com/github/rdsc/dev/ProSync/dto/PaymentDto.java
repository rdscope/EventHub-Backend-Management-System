package com.github.rdsc.dev.ProSync.dto;

import com.github.rdsc.dev.ProSync.enums.PaymentStatus;
import com.github.rdsc.dev.ProSync.model.OrderDetail;
import com.github.rdsc.dev.ProSync.model.Payment;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.*;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;

@Validated
public final class PaymentDto {

    @Value
    @Builder
    public static class PaymentInfo {
        private Long id;
        private Long orderListId;
        private String asset;
        private BigDecimal quoteRate;
        private BigDecimal amountCrypto;
        private PaymentStatus status;
        private LocalDateTime expiresAt;
        private Instant createAt;
        private Instant updateAt;

        public static PaymentInfo toDto(Payment p) {
            return PaymentInfo.builder()
                    .id(p.getId())
                    .orderListId(p.getOrderList().getId())
                    .asset(p.getAsset())
                    .quoteRate(p.getQuoteRate())
                    .amountCrypto(p.getAmountCrypto())
                    .status(p.getStatus())
                    .expiresAt(p.getExpiresAt())
                    .build();
        }
    }

    @Data
    @NoArgsConstructor
    public static class QuoteRequest {
        @NotBlank
        private String asset;              // 例如：BTC、ETH、USDT

        @NotNull
        @DecimalMin(value = "0.00000001")  // 1 單位加密資產=多少 TWD
        private BigDecimal price;
    }

    @Data
    @NoArgsConstructor
    public static class CryptoQuoteRequest {
        @NotNull(message = "orderListId cannot be null")
        private Long orderListId;

        @NotBlank(message = "Asset cannot be blank")
        private String asset;
    }

    @Getter
    @AllArgsConstructor
    public static class CryptoQuoteResponse {
        private Long paymentId;
        private Long orderList;
        private String asset;
        private BigDecimal quoteRate;    // 法幣→加密幣匯率
        private BigDecimal amountCrypto; // 需付款加密幣金額
        private PaymentStatus status;                     // PENDING / CONFIRMED ...
        private LocalDateTime expiresAt;       // 報價/支付截止時間

        public static CryptoQuoteResponse of(Payment p) {
            return new CryptoQuoteResponse(
                    p.getId(),
                    p.getOrderList().getId(),
                    p.getAsset(),
                    p.getQuoteRate(),
                    p.getAmountCrypto(),
                    p.getStatus(),
                    p.getExpiresAt()
            );
        }
    }

    @Data
    @NoArgsConstructor
    public static class ConfirmPaymentRequest {
        @NotBlank(message = "txHash cannot be blank")
        private String txHash;
    }

    @Getter
    @AllArgsConstructor
    public static class ConfirmPaymentResponse {
        private Long paymentId;
        private Long orderListId;
        private PaymentStatus status; // 預期為 CONFIRMED
    }

    @Value
    @AllArgsConstructor
    public static class TxHash {

        public static String fakeTxHash() {
            SecureRandom rnd = new SecureRandom();
            byte[] bytes = new byte[32]; // 32 bytes = 256-bit
            rnd.nextBytes(bytes);
            StringBuilder sb = new StringBuilder("0x");
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString(); // 例：0x3a5f... 共 66 字元（含 0x）
        }
    }
}
