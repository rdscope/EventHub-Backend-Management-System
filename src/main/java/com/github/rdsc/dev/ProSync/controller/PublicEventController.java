package com.github.rdsc.dev.ProSync.controller;

import com.github.rdsc.dev.ProSync.dto.EventDto;
import com.github.rdsc.dev.ProSync.dto.TicketTypeDto;
import com.github.rdsc.dev.ProSync.model.Event;
import com.github.rdsc.dev.ProSync.model.TicketType;
import com.github.rdsc.dev.ProSync.repository.EventRepository;
import com.github.rdsc.dev.ProSync.repository.TicketTypeRepository;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/public/events")
@RequiredArgsConstructor
@Validated
@Slf4j
public class PublicEventController {

    private final EventRepository eventRepo;
    private final TicketTypeRepository ticketTypeRepo;


    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("Public events ok");
    }

    // 公開：活動分頁列表
    @GetMapping("/page")
    public ResponseEntity<EventDto.PagedResponse<EventDto.EventInfo>> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) int size,
            @RequestParam(defaultValue = "id,desc") String sort
    ) {
        String[] parts = sort.split(",", 2);
        String field = parts[0];
        Sort.Direction dir = (parts.length > 1 && "asc".equalsIgnoreCase(parts[1]))
                ? Sort.Direction.ASC : Sort.Direction.DESC;

        Pageable pageable = PageRequest.of(page, size, Sort.by(dir, field));
        Page<Event> result = eventRepo.findAll(pageable);
        Page<EventDto.EventInfo> mapped = result.map(EventDto.EventInfo::of);

        EventDto.PagedResponse<EventDto.EventInfo> body = new EventDto.PagedResponse<>(
                mapped.getContent(),
                mapped.getNumber(),
                mapped.getSize(),
                mapped.getTotalElements(),
                mapped.getTotalPages()
        );
        return ResponseEntity.ok(body);
    }

    // 公開：查單一活動
    @GetMapping("/find-one/{id}")
    public ResponseEntity<EventDto.EventInfo> findOne(@PathVariable("id") Long eventId) {
        return eventRepo.findById(eventId)
                .map(EventDto.EventInfo::of)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found: " + eventId));
    }

    // 公開：依活動列票種
    @GetMapping("/ticket/by-event/{id}")
    public ResponseEntity<List<TicketTypeDto.TicketTypeInfo>> listTicketsByEvent(@PathVariable("id") Long eventId) {
        if (!eventRepo.existsById(eventId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found: " + eventId);
        }

        List<TicketType> list = ticketTypeRepo.findAllByEventId(eventId);
        return ResponseEntity.ok(list.stream().map(TicketTypeDto.TicketTypeInfo::of).toList());
    }
}