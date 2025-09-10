package com.github.rdsc.dev.ProSync.controller;

import com.github.rdsc.dev.ProSync.dto.UserDto;
import com.github.rdsc.dev.ProSync.enums.UserRole;
import com.github.rdsc.dev.ProSync.enums.UserStatus;
import com.github.rdsc.dev.ProSync.model.Role;
import com.github.rdsc.dev.ProSync.model.User;
import com.github.rdsc.dev.ProSync.model.UserRoleManager;
import com.github.rdsc.dev.ProSync.repository.RoleRepository;
import com.github.rdsc.dev.ProSync.repository.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Validated
// @PreAuthorize("hasRole('ADMIN')")
// Admin 走 URL 級授權
public class AdminController {

    private final UserRepository userRepo;
    private final RoleRepository roleRepo;
    private final UserRoleManager uRManager;

    private static final Set<String> ALLOWED = Set.of("USER", "ORGANIZER", "EXTERNAL_PROVIDER");

    @GetMapping("/ping") // 只有 ROLE_ADMIN 能存取（規則已在 SecurityConfig 設好）
    // 如果用 POST/PUT 會被回 405（方法不被允許）
    // 「已登入 + 具備 ADMIN 角色」
    public Map<String, Object> ping() {
        return Map.of(
             "ok", true,
             "roleRequired", "ROLE_ADMIN",
             "timestamp", Instant.now().toString()
        );
    }

    @PutMapping("/users/status/update/{id}")
    public ResponseEntity<UserDto.UserResponse> updateStatus(@PathVariable("id") Long userId,
                                                                   @RequestBody @Valid UserDto.UpdateStatusRequest req) {
        if (req == null || req.getStatus() == null || req.getStatus().isBlank()) {
            throw new IllegalArgumentException("status is required");
        }

        final UserStatus status;
        try{
            status = UserStatus.valueOf(normalize(req.getStatus()));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid user status: " + req.getStatus());
        }

        User user = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + userId));

        user.setStatus(status);
        userRepo.save(user);

        return ResponseEntity.ok(UserDto.UserResponse.of(user));
    }

    /** 賦予單一角色（USER / ORGANIZER / EXTERNAL_PROVIDER） */
    @PostMapping("/users/{id}/roles/grant")
    public ResponseEntity<UserDto.UserRolesResponse> grantRole(@PathVariable("id") Long userId,
                                                               @RequestBody @Valid UserDto.RoleChangeRequest req) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + userId));
        Role role = AllowedRole(req.getRole());
        uRManager.assign(user, role);
        userRepo.save(user);
        List<UserRole> userRoles = user.getRoles().stream().map(Role::getUserRole).toList();

        return ResponseEntity.ok(new UserDto.UserRolesResponse(userId, userRoles));
    }

    /** 移除單一角色（USER / ORGANIZER / EXTERNAL_PROVIDER） */
    @PostMapping("/users/{id}/roles/revoke")
    public ResponseEntity<UserDto.UserRolesResponse> revokeRole(@PathVariable("id") Long userId,
                                                                @RequestBody @Valid UserDto.RoleChangeRequest req) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + userId));
        Role role = AllowedRole(req.getRole());
        uRManager.revoke(user, role);
        userRepo.save(user);
        List<UserRole> userRoles = user.getRoles().stream().map(Role::getUserRole).toList();
        return ResponseEntity.ok(new UserDto.UserRolesResponse(userId, userRoles));
    }

    /** 用一組清單「整批覆蓋」角色（只接受 USER / ORGANIZER / EXTERNAL_PROVIDER） */
    @PutMapping("/users/{id}/roles")
    public ResponseEntity<UserDto.UserRolesResponse> replaceRoles(@PathVariable("id") Long userId,
                                                                  @RequestBody @Valid UserDto.RolesReplaceRequest req) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + userId));
        // 轉成可用的 Role 實體集合（去重）
        Set<Role> newRoles = new LinkedHashSet<>();
        for (String s : req.getRoles() == null ? List.<String>of() : req.getRoles()) {
            newRoles.add(AllowedRole(s));
        }
        user.removeAllRole();
        user.getRoles().addAll(newRoles);
        userRepo.save(user);
        List<UserRole> userRoles = user.getRoles().stream().map(Role::getUserRole).toList();
        return ResponseEntity.ok(new UserDto.UserRolesResponse(userId, userRoles));
    }

    private Role AllowedRole(String rawR) {
        if (rawR == null || rawR.isBlank()) {
            throw new IllegalArgumentException("role is required");
        }

        String norR = normalize(rawR);

        UserRole uR;
        try {
            uR = UserRole.valueOf(norR);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid role: " + rawR);
        }

        return roleRepo.findByUserRole(uR)
                .orElseGet(() -> roleRepo.save(Role.builder().userRole(uR).build()));
    }

    // 寫法對齊
    private static String normalize(String input) {
        String s = input.trim().toUpperCase();
        s = s.replace('-', '_').replace(' ', '_');
        if ("EXTERNALPROVIDER".equals(s)) return "EXTERNAL_PROVIDER";
        return s;
    }
}
