package com.github.rdsc.dev.ProSync.model;

import org.springframework.stereotype.Component;

@Component
public class EventOrganizeManager {
    public void assign (Event event, User user){
        event.addCoOrganizer(user);
    }
    public void discharge (Event event, User user){
        event.removeCoOrganizer(user);
    }
}
