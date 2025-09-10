package com.github.rdsc.dev.ProSync.controller;

import com.github.rdsc.dev.ProSync.enums.UserRole;
import com.github.rdsc.dev.ProSync.model.Role;
import com.github.rdsc.dev.ProSync.repository.RoleRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/roles")
@RequiredArgsConstructor
public class AdminRoleController {

    private final RoleRepository roleRepo;

//    public RoleController(RoleRepository roleRepo){
//        this.roleRepo = roleRepo;
//    }

    @GetMapping("/list")
    public List<UserRole> listRoles() {
        return roleRepo.findAll()
                .stream()
                .map(Role::getUserRole)
                .sorted()
                .toList();
    }

}
