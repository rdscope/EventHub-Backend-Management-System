package com.github.rdsc.dev.ProSync.controller;

import com.github.rdsc.dev.ProSync.dto.EventDto;
import com.github.rdsc.dev.ProSync.dto.TicketTypeDto;
import com.github.rdsc.dev.ProSync.model.Event;
import com.github.rdsc.dev.ProSync.model.TicketType;
import com.github.rdsc.dev.ProSync.model.User;
import com.github.rdsc.dev.ProSync.repository.EventRepository;
import com.github.rdsc.dev.ProSync.repository.TicketTypeRepository;
import com.github.rdsc.dev.ProSync.service.UserService;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

import static org.springframework.data.jpa.domain.AbstractPersistable_.id;

@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
@Validated
@Slf4j
public class TicketsController {

    private final TicketTypeRepository ticketTypeRepo;
    private final UserService userService;
    private final EventRepository eventRepo;

    private final EntityManager em;

    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("Admin/Organizer tickets ok");
    }


    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated user");
        }
        String email = auth.getName();
        return userService.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + email));
    }

    private void checkOwnerOr403(Event e) {
        User me = currentUser();
        if (e == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found");
        }
        if (!e.isOrganizer(me)) {
            // 丟 AccessDenied，GlobalExceptionHandler 會回 403 JSON
            throw new AccessDeniedException("Forbidden: not the owner of this event");
        }
    }


    // 1/ 分頁列表
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/admin/page")
    public ResponseEntity<EventDto.PagedResponse<TicketTypeDto.TicketTypeInfo>> tlistForAdmin(

            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id,desc") String sort
    ) {

        String[] parts = sort.split(",", 2);
        String field = parts[0];
        Sort.Direction dir = (parts.length > 1 && "asc".equalsIgnoreCase(parts[1])) ? Sort.Direction.ASC : Sort.Direction.DESC;

        Pageable pageable = PageRequest.of(page, size, Sort.by(dir, field));
        Page<TicketType> result = ticketTypeRepo.findAll(pageable);

        Page<TicketTypeDto.TicketTypeInfo> mapped = result.map(ti -> TicketTypeDto.TicketTypeInfo.of(ti));

        EventDto.PagedResponse<TicketTypeDto.TicketTypeInfo> body = new EventDto.PagedResponse<>(

                mapped.getContent(),
                mapped.getNumber(),
                mapped.getSize(),
                mapped.getTotalElements(),
                mapped.getTotalPages()
        );
        return ResponseEntity.ok(body);
    }

    // 1-1/ 分頁列表 (For Organizer)
    @PreAuthorize("hasRole('ORGANIZER')")
    @GetMapping("/organizer/page")
    public ResponseEntity<EventDto.PagedResponse<TicketTypeDto.TicketTypeInfo>> tlistForOrgan(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) int size,
            @RequestParam(defaultValue = "id,desc") String sort
    ) {
        // 1/ 解析排序
        String[] parts = sort.split(",", 2);
        String field = parts[0];
        Sort.Direction dir = (parts.length > 1 && "asc".equalsIgnoreCase(parts[1]))
                ? Sort.Direction.ASC : Sort.Direction.DESC;

        Pageable pageable = PageRequest.of(page, size, Sort.by(dir, field));

        // 2/ 拿目前登入者 id
        Long me = currentUser().getId();

        // 3/ 只查自己的（Repository 已新增 findAllByEvent_Organizer_Id）
        Page<TicketType> result = ticketTypeRepo.findAllOwnedByUser(me, pageable);

        // 4/ 映射 DTO + 包裝分頁
        Page<TicketTypeDto.TicketTypeInfo> mapped = result.map(TicketTypeDto.TicketTypeInfo::of);
        EventDto.PagedResponse<TicketTypeDto.TicketTypeInfo> body =
                new EventDto.PagedResponse<>(
                        mapped.getContent(),
                        mapped.getNumber(),
                        mapped.getSize(),
                        mapped.getTotalElements(),
                        mapped.getTotalPages()
                );
        return ResponseEntity.ok(body);
    }

    // 2/ 單筆查詢
    @PreAuthorize("hasRole('ORGANIZER')")
    @GetMapping("/organizer/find-one/{id}")
    public ResponseEntity<TicketTypeDto.TicketTypeInfo> findOne(@PathVariable("id") Long ticketTypeId) {
        return ticketTypeRepo.findById(ticketTypeId)
                .map(ti -> {
                    checkOwnerOr403(ti.getEvent());
                    return ResponseEntity.ok(TicketTypeDto.TicketTypeInfo.of(ti));
                })
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "TicketType not found: " + ticketTypeId));
    }

    @PreAuthorize("hasRole('ORGANIZER')")
    // Create
    @PostMapping("/organizer/create")
    public ResponseEntity<TicketTypeDto.TicketTypeInfo> create(@RequestBody @Valid TicketTypeDto.CreateRequest req) {

        if (req == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (req.getEventId() == null) {
            throw new IllegalArgumentException("eventId is required");
        }
        if (req.getName() == null || req.getName().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (req.getPrice() == null || req.getPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("price must be >= 0");
        }
        if (req.getQuota() == null || req.getQuota() < 0) {
            throw new IllegalArgumentException("quota must be >= 0");
        }

//        log.info("ADMIN created a ticketType: {} for event _{}", req.getName(), req.getEventId());

        Event e = eventRepo.findById(req.getEventId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found: " + req.getEventId()));
//        Event e = em.find(Event.class, req.getEventId());
//        if (e == null) throw new IllegalArgumentException("Event not found: " + req.getEventId());

        checkOwnerOr403(e);

        TicketType t = new TicketType();
        t.setName(req.getName());
        t.setEvent(e);
        t.setQuota(req.getQuota());
        t.setPrice(req.getPrice());

        TicketType saved = ticketTypeRepo.save(t);
//        em.persist(t);
//        em.flush();
        log.info("ticketType created: ticketTypeId = {}, eventId = {}, name = {}, price = {}, quota = {}",
                saved.getId(), req.getEventId(), req.getName(), req.getPrice(), req.getQuota());

        return ResponseEntity.status(201).body(TicketTypeDto.TicketTypeInfo.of(t));
    }

    @PreAuthorize("hasRole('ORGANIZER')")
    // Update
    @PutMapping("/organizer/update/{id}")
    public ResponseEntity<TicketTypeDto.TicketTypeInfo> update(@PathVariable("id") Long ticketTypeId, @RequestBody @Valid TicketTypeDto.UpsertRequest req) {

        // 至少要有一個欄位要改；若有給值再檢查格式
        if (req == null ||
                ((req.getName() == null || req.getName().isBlank())
                        && req.getPrice() == null
                        && req.getQuota() == null)) {
            throw new IllegalArgumentException("at least one field (name/price/quota) must be provided");
        }

        // 欄位格式 → 400
        if (req.getPrice() != null && req.getPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("price must be >= 0");
        }
        if (req.getQuota() != null && req.getQuota() < 0) {
            throw new IllegalArgumentException("quota must be >= 0");
        }
//        TicketType t = em.find(TicketType.class, ticketTypeId);
//        if (t == null) throw new IllegalArgumentException("ticketType not found: " + ticketTypeId);
//
//        Event e = em.find(Event.class, req.getEventId());
//        if (e == null) throw new IllegalArgumentException("Event not found: " + req.getEventId());
//
//        em.flush();
//
//        return TicketTypeDto.TicketTypeInfo.of(t);

        return ticketTypeRepo.findById(ticketTypeId)
                .map(t -> {
                    checkOwnerOr403(t.getEvent());
                    if (req.getName() != null && !req.getName().isBlank()) {
                        t.setName(req.getName());
                    }
                    if (req.getPrice() != null) {
                        t.setPrice(req.getPrice());
                    }
                    if (req.getQuota() != null) {
                        t.setQuota(req.getQuota());
                    }
                    TicketType saved = ticketTypeRepo.save(t);
                    log.info("ticketType updated: ticketTypeId = {}, name = {}, price = {}, quota = {}",
                            saved.getId(),
                            req.getName() != null ? req.getName() : "(keep)",
                            req.getPrice() != null ? req.getPrice() : "(keep)",
                            req.getQuota() != null ? req.getQuota() : "(keep)");
                    return ResponseEntity.ok(TicketTypeDto.TicketTypeInfo.of(t));
                })
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "TicketType not found: " + ticketTypeId));

    }

//    // Get One
//    @GetMapping("/{id}")
//    public TicketTypeDto.TicketTypeInfo getOne(@PathVariable("id") Long ticketTypeId, @RequestBody @Valid TicketTypeDto.UpsertRequest req) {
//        TicketType t = em.find(TicketType.class, ticketTypeId);
//        if (t == null) throw new IllegalArgumentException("ticketType not found: " + ticketTypeId);
//
//        return TicketTypeDto.TicketTypeInfo.of(t);
//    }

//    // List All
//    @GetMapping
//    public List<TicketTypeDto.TicketTypeInfo> listAll() {
//        List<TicketType> tlist = em.createQuery(
//                "select t from TicketType t order by t.id desc", TicketType.class
//        ).getResultList();
//        return tlist.stream().map(TicketTypeDto.TicketTypeInfo::of).toList();
//    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/admin/by-event/{id}")
    public ResponseEntity<List<TicketTypeDto.TicketTypeInfo>> listByEventForAdmin(@PathVariable("id") Long eventId) {

        Event e = eventRepo.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found: " + eventId));


        List<TicketType> list = ticketTypeRepo.findAllByEventId(eventId);
        return ResponseEntity.ok(list.stream().map(TicketTypeDto.TicketTypeInfo::of).toList());
    }

    @PreAuthorize("hasRole('ORGANIZER')")
    @GetMapping("/organizer/by-event/{id}")
    public ResponseEntity<List<TicketTypeDto.TicketTypeInfo>> listByEventForOrgan(@PathVariable("id") Long eventId) {

        Event e = eventRepo.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found: " + eventId));

        checkOwnerOr403(e);

        List<TicketType> list = ticketTypeRepo.findAllByEventId(eventId);
        return ResponseEntity.ok(list.stream().map(TicketTypeDto.TicketTypeInfo::of).toList());
    }

    @PreAuthorize("hasRole('ORGANIZER')")
    // Delete
    @DeleteMapping("/organizer/delete/{id}")
    public ResponseEntity<?> delete(@PathVariable("id") Long ticketTypeId) {
//        TicketType tref = em.find(TicketType.class, ticketTypeId);
//        if (tref == null) throw new IllegalArgumentException("ticketType not found: " + ticketTypeId);
//
//        em.remove(tref);
//        em.flush();
//
//        return ResponseEntity.noContent().build();

//        TicketType t = ticketTypeRepo.findById(ticketTypeId)
//                .orElseThrow(() ->
//                        new ResponseStatusException(HttpStatus.NOT_FOUND, "TicketType not found: " + ticketTypeId));
//
//        Event e = t.getEvent();
//        checkOwnerOr403(e);
//
//        ticketTypeRepo.delete(t);
//        log.info("Organizer deleted ticketType: id={}, eventId={}", ticketTypeId, e.getId() != null ? e.getId() : null);
//        return ResponseEntity.noContent().build();

        return ticketTypeRepo.findById(ticketTypeId)
                .map(t -> {
                    checkOwnerOr403(t.getEvent());
                    ticketTypeRepo.delete(t);
                    log.info("ticketType deleted: ticketTypeId = {}", ticketTypeId);
                    return ResponseEntity.noContent().build(); // 204
                })
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "TicketType not found: " + ticketTypeId));
    }
}
