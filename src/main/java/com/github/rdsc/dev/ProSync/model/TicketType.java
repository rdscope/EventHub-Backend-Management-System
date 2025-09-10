package com.github.rdsc.dev.ProSync.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "ticket_types",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_ticket_types_name_with_event_id", columnNames = {"name", "event_id"})
        }
)
// @ToString(exclude = "event")
@Getter @Setter
@AllArgsConstructor @NoArgsConstructor @Builder

public class TicketType {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false) // optional=false 是「JPA/程式邏輯層」
    @JoinColumn(name = "event_id", nullable = false) // nullable=false 是「資料庫欄位」
    private Event event;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "price", nullable = false, precision = 18, scale = 2)
    // 總位數 18 位, 其中小數 2 位
    private BigDecimal price;

    @Column(name = "quota", nullable = false)
    private Integer quota;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "create_at", nullable = false, updatable = false)
    private Instant createAt;

    @Column(name = "update_at", nullable = false)
    private Instant updateAt;

    private static final int MAX_INCRE_PER_OP = 4000;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createAt = now;
        this.updateAt = now;
        if(version == null) {
            version = 0L;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updateAt = Instant.now();
    }

    public void decreaseQuota(int amount) {
        if (amount <= 0 && amount >= 4) throw new IllegalArgumentException("Amount must be more than 0, and less than 4");
                                                  // 「傳進來的參數不對」（400 Bad Request）
        if (quota == null || quota < amount) throw new IllegalStateException("Not enough quota");
                                                       // 「系統/資料當下的狀態不允許這動作」（例如庫存不足、溢位），多半回 409 Conflict
        quota -= amount;
    }

    public void increaseQuota(int amount) {
        if (amount <= 0) throw new IllegalArgumentException("Amount must be > 0");
        if (amount > MAX_INCRE_PER_OP) {
            throw new IllegalArgumentException("Amount exceeds max per operation: " + MAX_INCRE_PER_OP);
        }
        if (quota == null) quota = 0;

        try{
            quota = Math.addExact(quota, amount); // 防止 int 溢位，溢位會丟 ArithmeticException
        } catch (ArithmeticException ex) {
            throw new IllegalStateException("Quota overflow");
        }
    }
}
