package com.github.rdsc.dev.ProSync.model;

import org.springframework.stereotype.Component;

@Component
public class UserRoleManager {
    public void assign (User user, Role role){
        user.addRole(role);
    }

    public void revoke (User user, Role role){
        user.removeRole(role);
    }
}
