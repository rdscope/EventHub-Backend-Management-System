package com.github.rdsc.dev.ProSync.model;

import com.github.rdsc.dev.ProSync.enums.UserRole;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
      name = "roles",
      uniqueConstraints = @UniqueConstraint(name = "uk_roles_name", columnNames = "name")
)
@Getter @Setter
@AllArgsConstructor @NoArgsConstructor @Builder
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "name", nullable = false, length = 50)
    private UserRole userRole;

    @CreationTimestamp
    @Column(name = "create_at", nullable = false, updatable = false)
    private LocalDateTime createAt;

    @UpdateTimestamp
    @Column(name = "update_at", nullable = false)
    private LocalDateTime updateAt;

    // 1. save(...)
    // 2. Spring Data JPA 交給 Hibernate 管（實體變「受管控」）。
    // 3. 當 Hibernate 準備要把變更寫到 DB（flush）：
    //      如果是新資料 → 先跑 @PrePersist → 再下 INSERT。
    //      如果是已存在且有變更 → 先跑 @PreUpdate → 再下 UPDATE。
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createAt = now;
        this.updateAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updateAt = LocalDateTime.now();
    }
    // void：不會有回傳值
    // 規範上：private 其實也是允許的，Hibernate 仍能靠反射叫到它。
    // 建議別用 private？因為：
    //      有些環境對反射比較「嚴格」（例如開啟 Java Module、做原生映像），private 可能被權限擋。
    //      非 private（package/protected/public）在大多數情況下更保險、較少踩雷，測試或工具也較容易呼叫。
    // 結論：可以 private，但為了穩定度，常用 package/protected。
}
