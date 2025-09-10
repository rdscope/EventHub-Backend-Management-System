package com.github.rdsc.dev.ProSync.model;

import com.github.rdsc.dev.ProSync.enums.OrderStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "order_list"
)
// @ToString(exclude = "user")
@Getter @Setter
@AllArgsConstructor @NoArgsConstructor @Builder
public class OrderList {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    // 懶載入（LAZY）就是「先不拿、用到再去拿」
    // 用 LAZY = 先只拿 order_list 表；真的要 orderList.getUser() 才去抓那一筆 user
    // 避免 EAGER 回傳 JSON 爆炸：循環引用、資料外洩、或變成超大回應
    // By Default:
    //      @ManyToOne、@OneToOne → 預設 EAGER
    //      @OneToMany、@ManyToMany → 預設 LAZY
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OrderStatus status;

    @Column(name = "total_cost", nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal totalCost = BigDecimal.ZERO;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "create_at", nullable = false, updatable = false)
    private Instant createAt;

    @UpdateTimestamp
    @Column(name = "update_at", nullable = false)
    private Instant updateAt;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToMany(mappedBy = "orderList", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<OrderDetail> detail = new ArrayList<>();
    // 一個訂單有多個明細；由 OrderDetail.orderList 反向維護；

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToMany(mappedBy = "orderList", cascade = CascadeType.ALL, orphanRemoval = false)
                                                                  // 付款是歷史紀錄，不要因為從集合移除就真的刪庫
                                       // CascadeType.ALL = PERSIST + MERGE + REMOVE + REFRESH + DETACH
                                       // 如果要「保留付款歷史」，不要用 ALL，改回 {PERSIST, MERGE} 比安全。
    @Builder.Default
    private List<Payment> txHistory = new ArrayList<>();

    @PrePersist // 在「第一次存進 DB 前」會自動呼叫的方法
    void onCreate() {
        Instant now = Instant.now();
        this.createAt = now;
        this.updateAt = now;
        if (status == null) status = OrderStatus.PENDING_PAYMENT;
        if (totalCost == null) totalCost = BigDecimal.ZERO;
    }

    @PreUpdate // 在「更新 DB 前」會自動呼叫的方法
    void onUpdate() {
        this.updateAt = Instant.now();
    }

    // 增減品項，自動更新總金額
    public void addItem(OrderDetail item) {
        detail.add(item);
        item.setOrderList(this); // 反向設定：有 @JoinColumn 的那一邊進行設定。
        recalcTotal();
    }

    public void removeItem(OrderDetail item) {
        detail.remove(item);
        item.setOrderList(null);
        recalcTotal();
    }
    // 雙向關聯＝兩邊都能找到彼此：
    // 從 OrderList 可拿到 detail 清單
    // 從 OrderDetail 可拿到 orderList 這個父物件
    // 在 JPA 裡，真正決定外鍵的是「有 @JoinColumn 的那一邊」（= 擁有方/Owner）。

    public void recalcTotal() {
        BigDecimal sum = BigDecimal.ZERO;
        for(OrderDetail item : detail) {
            if (item.getCost() != null) {
                sum = sum.add(item.getCost());
                // 不能寫 sum = sum + i.getCost()，因為 i.getCost() 是 BigDecimal，
                // 而 + 只能加「基本型別」(int/long/double…) 或字串，不能加「物件」
            }
        }
        this.totalCost = sum;
    }

    public void addPayment(Payment payment) {
        if (payment == null) return;

        this.txHistory.add(payment);
        payment.setOrderList(this);
    }

    public void removePayment(Payment payment) {
        if (payment == null) return;

        this.txHistory.remove(payment);
        // order.getPayments().remove(p) 只會影響這次交易/這段程式跑著的那份集合怎麼被遍歷/序列化；不會寫回資料庫。
        // 真的要影響資料庫，要「動擁有端」＝ p.setOrder(...)

    }

    // OrderStatus Defining
    public boolean isPending() {
        return this.status == OrderStatus.PENDING_PAYMENT;
    }

    public boolean isExpired() {
        return this.expiresAt != null && LocalDateTime.now().isAfter(this.expiresAt);
        // 如果 有設定截止時間 而且 現在時間已經超過，就算逾時
    }

    // public boolean isPending() {
    //    if (this.status == OrderStatus.PENDING_PAYMENT) {
    //        return true;
    //    }
    //    return false;
    //}
    //
    //public boolean isExpired() {
    //    if (this.expiresAt != null && LocalDateTime.now().isAfter(this.expiresAt)) {
    //        return true;
    //    }
    //    return false;
    //}
}
