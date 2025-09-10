package com.github.rdsc.dev.ProSync.controller;

import com.github.rdsc.dev.ProSync.dto.EventDto;
import com.github.rdsc.dev.ProSync.model.Event;
import com.github.rdsc.dev.ProSync.model.EventOrganizeManager;
import com.github.rdsc.dev.ProSync.model.User;
import com.github.rdsc.dev.ProSync.repository.EventRepository;
import com.github.rdsc.dev.ProSync.service.UserService;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.Value;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController // 做 REST API 的控制器
// @PreAuthorize("hasRole('ORGANIZER')") // 這支 Controller 底下的 API 都需要 ADMIN
@RequestMapping("/api/events")
@RequiredArgsConstructor // 自動幫這個 class 產生一個帶有 final 欄位的建構子（eg 下面的 EntityManager em）
@Validated // 開啟 Spring 的參數驗證支持
@Slf4j
public class EventController {

    private final EventRepository eventRepo;
    private final UserService userService;
    private final EventOrganizeManager organizeManager;

    private final EntityManager em;

    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("Admin/Organizer events ok");
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

    private void checkPrimaryOwnerOr403(Event e) {
        User me = currentUser();
        if (e.getOrganizer() == null || e.getOrganizer().getId() == null
                || !e.getOrganizer().getId().equals(me.getId())) {
            throw new AccessDeniedException("Forbidden: only primary organizer can manage co-organizers");
        }
    }

    private void checkOwnerOr403(Event e) {
        User me = currentUser();
        if (e == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found");
        }
        // ★ 用 Event.isOrganizer(me)（主要主辦 or 協同主辦 都算）
        if (!e.isOrganizer(me)) {
            throw new AccessDeniedException("Forbidden: not the owner of this event");
        }
    }


    // 1/ 分頁列表
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/admin/page")
    public ResponseEntity<EventDto.PagedResponse<EventDto.EventInfo>> elistForAdmin(
            // 使用者怎麼查
            @RequestParam(defaultValue = "0") int page,
            // 讀 ?page= 這個查詢參數，如果沒帶，就用預設 0（第一頁）
            @RequestParam(defaultValue = "10") int size,
            // 讀 ?size=，沒帶就用 10；代表一頁幾筆。
            @RequestParam(defaultValue = "id,desc") String sort
            // 讀 ?sort=，沒帶就預設 "id,desc" 「欄位,方向」
    ) {
        String[] parts = sort.split(",", 2);
        String field = parts[0];
        Sort.Direction dir = (parts.length > 1 && "asc".equalsIgnoreCase(parts[1])) ? Sort.Direction.ASC : Sort.Direction.DESC;

        Pageable pageable = PageRequest.of(page, size, Sort.by(dir, field)); // 分頁需求，做出一個 Pageable 實例
        Page<Event> result = eventRepo.findAll(pageable);

        Page<EventDto.EventInfo> mapped = result.map(ei -> new EventDto.EventInfo(ei.getId(), ei.getName(),
                                                            ei.getDescription(), ei.getStartAt(), ei.getEndAt()));
        // 把這一頁的 T（這裡是 Event）用 converter 轉成 U（這裡是 EventInfo），並回傳新的 Page<U>，但沿用同一份分頁資訊
        EventDto.PagedResponse<EventDto.EventInfo> body = new EventDto.PagedResponse<>(
                // 查到的資料
                mapped.getContent(),
                mapped.getNumber(),
                mapped.getSize(),
                mapped.getTotalElements(),
                mapped.getTotalPages()
        ); // 統一格式輸出
        return ResponseEntity.ok(body);
    }

    // 1-1/ 分頁列表 (For Organizer)
    @PreAuthorize("hasRole('ORGANIZER')")
    @GetMapping("/organizer/page")
    public ResponseEntity<EventDto.PagedResponse<EventDto.EventInfo>> elistForOrgan(
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

        // 2/ 取目前登入使用者
        Long me = currentUser().getId();

        // 3/ 只查自己的活動
        Page<Event> result = eventRepo.findAllOwnedBy(me, pageable);

        // 4/ 映射成 DTO + 包裝分頁
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

    // 2/ 單筆查詢
    @PreAuthorize("hasRole('ORGANIZER')")
    @GetMapping("/organizer/find-one/{id}")
    public ResponseEntity<EventDto.EventInfo> findOne(@PathVariable("id") Long eventId) {
        return eventRepo.findById(eventId)
                .map(ei -> {
                    checkOwnerOr403(ei);
                    return ResponseEntity.ok(new EventDto.EventInfo(ei.getId(), ei.getName(),
                            ei.getDescription(), ei.getStartAt(), ei.getEndAt()));
                })
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found: " + eventId));
    }

    @PreAuthorize("hasRole('ORGANIZER')")
    @PostMapping("/organizer/create")
    // Create
    public ResponseEntity<EventDto.EventInfo> createEvent(@RequestBody @Valid EventDto.UpsertRequest req) {

        if (req == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (req.getName() == null || req.getName().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        // 新增：時間區間檢查（兩者都有給才檢查）
        if (req.getStartAt() != null && req.getEndAt() != null && req.getEndAt().isBefore(req.getStartAt())) {
            throw new IllegalArgumentException("endAt must be after startAt");
        }

        Event e = new Event();
        e.setName(req.getName());
        e.setDescription(req.getDescription());
        e.setStartAt(req.getStartAt());
        e.setEndAt(req.getEndAt());
        e.setOrganizer(currentUser());

        // T1
//        EventDto.EventInfo ei = new EventDto.EventInfo(e.getId(),
//                                                       e.getName(),
//                                                       e.getDescription(),
//                                                       e.getStartAt(),
//                                                       e.getEndAt());
        // T2
//        EventDto.EventInfo ei = new EventDto.EventInfo(e);
        // T3
//        return EventDto.EventInfo.toInfo(e);
        // T4
//        return EventDto.EventInfo.builder().id(e.getId()).name(e.getName())
//                .description(e.getDescription()).startAt(e.getStartAt())
//                .endAt(e.getEndAt()).build();

        Event saved = eventRepo.save(e);
//        em.persist(e); // 新增到資料庫
//        em.flush(); // 立刻送出 SQL，拿到 eventId
        log.info("Event created: eventId = {}, title = {}", saved.getId(), req.getName());

        return ResponseEntity.status(201).body(EventDto.EventInfo.of(e));
    }

    @PreAuthorize("hasRole('ORGANIZER')")
    // Update
    @PutMapping("/organizer/update/{id}")
    public ResponseEntity<EventDto.EventInfo> update(@PathVariable("id") Long eventId, @RequestBody @Valid EventDto.UpsertRequest req) {

        if (req == null ||
                ((req.getName() == null || req.getName().isBlank())
                        && req.getStartAt() == null
                        && req.getEndAt() == null)) {
            throw new IllegalArgumentException("at least one field (name/startAt/endAt) must be provided");
        }
        if (req.getStartAt() != null && req.getEndAt() != null && req.getEndAt().isBefore(req.getStartAt())) {
            throw new IllegalArgumentException("endAt must be after startAt");
        }

//        Event e = em.find(Event.class, eventId);
//        if (e == null) throw new IllegalArgumentException("Event not found: " + eventId);
//
//        e.setName(req.getName());
//        e.setDescription(req.getDescription());
//        e.setStartAt(req.getStartAt());
//        e.setEndAt(req.getEndAt());
//
//        em.flush();

//        return EventDto.EventInfo.of(e);

        return eventRepo.findById(eventId)
                .map(e -> {
                    checkOwnerOr403(e);
                    if (req.getName() != null && !req.getName().isBlank()) {
                        e.setName(req.getName());
                    }
                    if (req.getDescription() != null) {
                        if (req.getDescription().isBlank()) {
                            e.setDescription("No Description");
                        } else {
                            e.setDescription(req.getDescription());
                        }
                    }
                    if (req.getStartAt() != null) {
                        e.setStartAt(req.getStartAt());
                    }
                    if (req.getEndAt() != null) {
                        e.setEndAt(req.getEndAt());
                    }

                    Event saved = eventRepo.save(e);
                    log.info("Event updated: eventId = {}, title = {}", saved.getId(), req.getName());
                    return ResponseEntity.ok(EventDto.EventInfo.of(e));
                })
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found: " + eventId));

    }

//    // Get one
//    @GetMapping("/{id}")
//    public EventDto.EventInfo getOne(@PathVariable ("id") Long eventId) {
//
//        Event e = em.find(Event.class, eventId);
//        if (e == null) throw new IllegalArgumentException("Event not found: " + eventId);
//        return EventDto.EventInfo.of(e);
//    }

    // List all
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/admin/list-all")
    public List<EventDto.EventInfo> listAll() {

        List<Event> elist = em.createQuery(
                "select elist from Event elist order by elist.startAt desc", Event.class
                // 把所有 Event 依照 startAt 由大到小排序，告訴 JPA 回傳的結果型別是 Event
        ).getResultList(); // 回傳一個 List<Event>

//        List<EventDto.EventInfo> eilist = new ArrayList<>();
//        for(Event e : elist) {
//            EventDto.EventInfo dto =  EventDto.EventInfo.of(e);
//            eilist.add(dto);
//        }
//        return eilist;

        return elist.stream().map(EventDto.EventInfo::of).toList(); // :: 把這個方法借來用
                                                                    // .map() 不能直接把 Event 實體回給前端（會有 Lazy 載入 / 循環關聯問題）
                                                                    //        逐一轉換：對每個元素做轉換。
        //
        //這裡就是把 Event → EventResp
    }

    @PreAuthorize("hasRole('ORGANIZER')")
    // Delete
    @DeleteMapping("/organizer/delete/{id}")
    public ResponseEntity<?> delete(@PathVariable("id") Long eventId) {
//        Event ref = em.find(Event.class, eventId);
//        if (ref == null) throw new IllegalArgumentException("Event not found: " + eventId);
//
//        em.remove(ref);
//        em.flush();
//
//        return ResponseEntity.noContent().build(); // HTTP 204 No Content

        if (!eventRepo.existsById(eventId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found: " + eventId);
        }

        Event e = eventRepo.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found: " + eventId));
        checkOwnerOr403(e);

        eventRepo.deleteById(eventId);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasRole('ORGANIZER')")
    @PostMapping("/organizer/co-organizers/add/{id}")
    public ResponseEntity<Map<String, Object>> addCoOrganizer(
            @PathVariable("id") Long eventId,
            @RequestBody @Valid EventDto.CoOrganizerRequest req) {

        Event e = eventRepo.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found: " + eventId));

        // 只有主要主辦人能維護名單
        checkPrimaryOwnerOr403(e);

        var u = userService.findByEmail(req.getEmail())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + req.getEmail()));

        organizeManager.assign(e, u);
        eventRepo.save(e);

        var body = Map.of(
                "eventId", e.getId(),
                "added", u.getEmail(),
                "coOrganizers", e.getCoOrganizers().stream().map(User::getEmail).toList()
        );
        return ResponseEntity.ok(body);
    }

    @PreAuthorize("hasRole('ORGANIZER')")
    @DeleteMapping("/organizer/co-organizers/remove/{id}")
    public ResponseEntity<Map<String, Object>> removeCoOrganizer(
            @PathVariable("id") Long eventId,
            @RequestParam("email") String email) {

        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email is required");
        }

        Event e = eventRepo.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found: " + eventId));

        // 只有主要主辦人能維護名單
        checkPrimaryOwnerOr403(e);

        var u = userService.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + email));

        organizeManager.discharge(e, u);
        eventRepo.save(e);

        var body = Map.of(
                "eventId", e.getId(),
                "removed", u.getEmail(),
                "coOrganizers", e.getCoOrganizers().stream().map(User::getEmail).toList()
        );
        return ResponseEntity.ok(body);
    }

}
