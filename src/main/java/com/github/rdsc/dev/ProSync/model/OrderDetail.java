package com.github.rdsc.dev.ProSync.model;


import jakarta.persistence.*;

import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "order_detail",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_order_detail_with_order_list_id_ticket_type_id",
                        columnNames = {"order_list_id", "ticket_type_id"}
                )
        }
)
// @ToString(exclude = {"orderList", "ticketType"})
@Getter @Setter
@AllArgsConstructor @NoArgsConstructor @Builder
public class OrderDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_list_id", nullable = false)
    private OrderList orderList;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_type_id", nullable = false)
    private TicketType ticketType;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false, precision = 18, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "cost", nullable = false, precision = 18, scale = 2)
    private BigDecimal cost;

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
        if (unitPrice == null) unitPrice = BigDecimal.ZERO;
        if (quantity == null) quantity = 0;
        recalcCost();
    }

    @PreUpdate // 在「更新 DB 前」會自動呼叫的方法
    void onUpdate() {
        this.updateAt = Instant.now();
        recalcCost();
    }

    public void recalcCost() {
        if (unitPrice == null || quantity == null) {
            cost = BigDecimal.ZERO;
        } else {
            cost = unitPrice.multiply(BigDecimal.valueOf(quantity.longValue()));
            // 把整數「3」先變成 long，再安全地變成 BigDecimal（變成 3）
            // 避免用 double 造成小數誤差
        }
        this.setCost(cost);
    }

}
