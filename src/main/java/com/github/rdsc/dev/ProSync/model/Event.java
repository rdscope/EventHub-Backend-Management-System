package com.github.rdsc.dev.ProSync.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(
        name = "events"
)
@Getter @Setter
@AllArgsConstructor @NoArgsConstructor @Builder

public class Event {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organizer_id", nullable = true)
    private User organizer;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToMany
    @JoinTable(
            name = "event_organizers",
            joinColumns = @JoinColumn(name = "event_id", foreignKey = @ForeignKey(name = "fk_event_organizers_dep_event_id")),
            inverseJoinColumns = @JoinColumn(name = "user_id", foreignKey = @ForeignKey(name = "fk_event_organizers_dep_user_id"))
    )
    @Builder.Default
    private Set<User> coOrganizers = new HashSet<>();

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endAt;

    @CreationTimestamp
    @Column(name = "create_at", nullable = false, updatable = false)
    private Instant createAt;

    @UpdateTimestamp
    @Column(name = "update_at", nullable = false)
    private Instant updateAt;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
    // 告訴 JPA：「外鍵(FK)在對方那邊」
    //      在 TicketType 這個實體裡，有一個 Java 欄位 叫 event，它是這段關係的「擁有端（owner）」
    //      event_id 是 資料庫欄位名
    // cascade = CascadeType.ALL：當存 Event，底下的 TicketType 也自動存；當刪 Event，底下的票種也一起刪
    // orphanRemoval = true：如果某張票種從 list 移除（或把它的 event 設成 null），
    //                       JPA 覺得它「沒有爸媽了」，就會對 DB 發 DELETE 把這筆 ticket_type 刪掉。
    //                       用途：讓「移除關聯＝刪子項」這件事更直覺，不用再自己呼叫 repository.delete(...)
    @Builder.Default // 製作一個空清單，Event.builder().build() 裡的 ticketTypes 會是 null，而 NPE。
    private List<TicketType> ticketTypes = new ArrayList<>();

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createAt = now;
        this.updateAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updateAt = Instant.now();
    }

    public void addTicketType(TicketType type) {
        ticketTypes.add(type);
        type.setEvent(this);
    }

    public void removeTicketType(TicketType type) {
        ticketTypes.remove(type);
        type.setEvent(null); // 設成「沒有活動了」（空值），斷開關係
    }

    public boolean isOrganizer(User u) {
        if (u == null || u.getId() == null) return false;
        if (this.organizer != null && u.getId().equals(this.organizer.getId())) return true;
        return coOrganizers.stream().anyMatch(x -> x != null && u.getId().equals(x.getId()));
    }

    public void addCoOrganizer(User u) {
        if (u != null) {
            coOrganizers.add(u);
        }
    }

    public void removeCoOrganizer(User u) {
        if (u != null) {
            coOrganizers.remove(u);
        }
    }
}
