package com.github.rdsc.dev.ProSync.model;

import com.github.rdsc.dev.ProSync.enums.UserRole;
import com.github.rdsc.dev.ProSync.enums.UserStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

// @Data
@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_users_email", columnNames = {"email"})
                // 取名 uk_users_email，鎖住的欄位清單只有 email，一樣不能重複。
                // 告訴 JPA/Hibernate 建表時，幫 users.email 建一條唯一鍵，名字叫 uk_users_email
        }
)
@Getter
@AllArgsConstructor @NoArgsConstructor

public class User{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // @Column(nullable = false, length = 191, unique = true)
    @Column(name = "email", nullable = false, length = 191) // 191 是為了 MySQL utf8mb4 索引安全上限
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255) // 只存雜湊，不存明碼
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20) // enum: ACTIVE, DISABLE,...
    private UserStatus status;

    @CreationTimestamp
    @Column(name = "create_at", nullable = false)
    private Instant createAt;

    @UpdateTimestamp
    @Column(name = "update_at", nullable = false)
    private Instant updateAt;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id", foreignKey = @ForeignKey(name = "fk_user_roles_dep_user_id")),
            inverseJoinColumns = @JoinColumn(name = "role_id", foreignKey = @ForeignKey(name = "fk_user_roles_dep_role_id"))
    )
    private Set<Role> roles = new HashSet<>();

    //Setter
    public void setEmail(String email){
        this.email = email;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void setStatus(UserStatus status){
        this.status = status;
    }

    public void setFailedLoginCount(int failedLoginCount) {
        this.failedLoginCount = failedLoginCount;
    }

    public void addRole(Role role){
        this.roles.add(role);
    }

    public void removeRole(Role role){
        this.roles.remove(role);
    }

    public void removeAllRole(){
        this.getRoles().clear();
    }

    public void incrementFailedLogin() {
        this.failedLoginCount++;
    }

//    public void resetFailedLogin() {
//        this.failedLoginCount = 0;
//    }
}
