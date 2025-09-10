package com.github.rdsc.dev.ProSync.model;

import com.github.rdsc.dev.ProSync.enums.OrderStatus;
import com.github.rdsc.dev.ProSync.enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "payments",
        uniqueConstraints = @UniqueConstraint(name = "uk_payments_tx_hash", columnNames = "tx_hash")
)
// @ToString(exclude = "orderList") // 避免雙向關聯循環
// @EqualsAndHashCode(of = "id") // 只用 id 這個欄位來判斷兩個物件是否相等、以及計算雜湊值。
@Getter @Setter
@AllArgsConstructor @NoArgsConstructor @Builder
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_list_id", nullable = false)
    private OrderList orderList;

    @Column(name = "asset", nullable = false, length = 20)
    private String asset; // 幣別（例：BTC、ETH）

    @Column(name = "quote_rate", nullable = false, precision = 18, scale = 8)
    private BigDecimal quoteRate; // 報價：1 單位 asset = ? 法幣

    @Column(name = "amount_crypto", nullable = false, precision = 38, scale = 18)
    private BigDecimal amountCrypto;

    @Column(name = "tx_hash", nullable = true, length = 128)
    private String txHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;


    @CreationTimestamp
    @Column(name = "create_at", nullable = false, updatable = false)
    private Instant createAt;

    @UpdateTimestamp
    @Column(name = "update_at", nullable = false)
    private Instant updateAt;

    @PrePersist // 在「第一次存進 DB 前」會自動呼叫的方法
    void onCreate() {
        Instant now = Instant.now();
        this.createAt = now;
        this.updateAt = now;
        if (status == null) status = PaymentStatus.PENDING;
    }

    @PreUpdate // 在「更新 DB 前」會自動呼叫的方法
    void onUpdate() {
        this.updateAt = Instant.now();
    }

}
