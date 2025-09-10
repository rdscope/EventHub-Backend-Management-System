package com.github.rdsc.dev.ProSync.dto;

import com.github.rdsc.dev.ProSync.model.TicketType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Value;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

@Validated
public final class TicketTypeDto {

    @Value
    public static class TicketTypeInfo {
        private Long id;
        private Long eventId;
        private String name;
        private Integer quota;
        private BigDecimal price;


        public static TicketTypeInfo of(TicketType t) {

            return new TicketTypeInfo(
                    t.getId(),
                    t.getEvent() != null ? t.getEvent().getId() : null,
                    t.getName(),
                    t.getQuota(),
                    t.getPrice()
            );
        }
    }

    @Getter
    @NoArgsConstructor
    public static class CreateRequest {
        @NotBlank(message = "Name is required")
        private String name;

        @NotNull(message = "eventId is required")
        private Long eventId;

        @Min(value = 0, message = "Quota must be >= 0")
        private Integer quota;

        @NotNull(message = "startAt is required")
        private BigDecimal price;
    }

    @Getter
    @NoArgsConstructor
    public static class UpsertRequest {
        @NotBlank(message = "Name is required")
        private String name;

        @Min(value = 0, message = "Quota must be >= 0")
        private Integer quota;

        @NotNull(message = "startAt is required")
        private BigDecimal price;
    }

}
