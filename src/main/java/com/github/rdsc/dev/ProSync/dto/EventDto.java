package com.github.rdsc.dev.ProSync.dto;


import com.github.rdsc.dev.ProSync.model.Event;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.List;

@Validated
public final class EventDto {

    @Value
    // = 不可變 + 全欄位建構子（等同 @AllArgsConstructor）
    // @AllArgsConstructor
    // @Builder
    public static class EventInfo {
        private Long id;
        private String name;
        private String description;
        private LocalDateTime startAt;
        private LocalDateTime endAt;

        // T5
        public static EventInfo of(Event e) {
        // static：靜態。這方法屬於類別本身，不是物件；呼叫時不用先 new，可直接 EventInfo.of(...)
            return new EventInfo(
                    e.getId(),
                    e.getName(),
                    e.getDescription(),
                    e.getStartAt(),
                    e.getEndAt()
            );
        }
        // T2
//        public EventInfo(Event e) {
//            this(e.getId(), e.getName(), e.getDescription(), e.getStartAt(), e.getEndAt());
//        }
        // T3
//        public static EventInfo toInfo(Event e) {
//            return new EventInfo(e.getId(), e.getName(), e.getDescription(), e.getStartAt(), e.getEndAt());
//        }

    }

    @Getter
    @NoArgsConstructor
    public static class UpsertRequest {
        @NotBlank(message = "Name is required")
        private String name;

        private String description;

        @NotNull(message = "startAt is required")
        private LocalDateTime startAt;

        @NotNull(message = "endAt is required")
        private LocalDateTime endAt;
    }

    @Data
    @AllArgsConstructor
    public static class PagedResponse<T> { // <T> 是泛型：表示裡面裝什麼型別都可以
        private List<T> content; // 這一頁的資料陣列
        private int page; // 第幾頁（從 0 算）
        private int size; // 一頁幾筆
        private Long totalElements; // 總筆數
        private int totalPages; // 總頁數
    }

    @Data
    @NoArgsConstructor
    public static class CoOrganizerRequest {
        @NotBlank @Email
        private String email;
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
    }

}
