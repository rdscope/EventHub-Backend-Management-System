package com.github.rdsc.dev.ProSync.dto;

import com.github.rdsc.dev.ProSync.enums.OrderStatus;
import com.github.rdsc.dev.ProSync.enums.PaymentMethod;
import com.github.rdsc.dev.ProSync.model.Event;
import com.github.rdsc.dev.ProSync.model.OrderDetail;
import com.github.rdsc.dev.ProSync.model.OrderList;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Validated
public final class OrderDto {

    private OrderDto() {}

    @Getter
    @AllArgsConstructor
    public static class OrderSummary {
        private Long orderListId;
        private OrderStatus status;
        private BigDecimal totalCost;
        private LocalDateTime expiresAt;
        private List<OrderDetailInfo> details;
        private List<PaymentDto.CryptoQuoteResponse> payments;

        public static OrderSummary of(OrderList e, List<OrderDetailInfo> details, List<PaymentDto.CryptoQuoteResponse> payments) {
            // static：靜態。這方法屬於類別本身，不是物件；呼叫時不用先 new，可直接 EventInfo.of(...)
            return new OrderSummary(
                    e.getId(),
                    e.getStatus(),
                    e.getTotalCost(),
                    e.getExpiresAt(),
                    details,
                    payments
            );
        }
    }

    @Getter
    @AllArgsConstructor
    public static class OrderDetailInfo {
        private Long orderDetailId;
        private Long orderListId;
        private Long ticketTypeId;
        private BigDecimal unitPrice; // 若沒有 name 欄位，可刪除此欄
        private Integer quantity;
        private BigDecimal cost;

        public static OrderDetailInfo of(OrderDetail e) {
            return new OrderDetailInfo(
                    e.getId(),
                    e.getOrderList().getId(),
                    e.getTicketType().getId(),
                    e.getUnitPrice(),
                    e.getQuantity(),
                    e.getCost()
                    );
        }
    }

    @Data
    @AllArgsConstructor
    public static class MyOrderResponse {
        private Long orderListId;
        private OrderStatus status;
        private BigDecimal totalCost;
        private LocalDateTime expiresAt;
    }

    @Data
    @NoArgsConstructor
    public static class ReserveRequest {
        // @NotNull private Long userId;
        private Long orderListId;
        @NotNull private Long ticketTypeId;
        @Min(1) private Integer quantity = 1; // 數量至少 1
    }

    @Data
    @AllArgsConstructor
    public static class ReserveResponse {
        private Long orderListId;
        private OrderStatus status;
        private BigDecimal totalCost;
        private LocalDateTime expiresAt;
    }
}
