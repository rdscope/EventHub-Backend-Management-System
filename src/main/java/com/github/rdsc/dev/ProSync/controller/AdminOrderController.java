package com.github.rdsc.dev.ProSync.controller;

import com.github.rdsc.dev.ProSync.dto.EventDto;
import com.github.rdsc.dev.ProSync.dto.OrderDto;
import com.github.rdsc.dev.ProSync.dto.PaymentDto;
import com.github.rdsc.dev.ProSync.enums.OrderStatus;
import com.github.rdsc.dev.ProSync.model.Event;
import com.github.rdsc.dev.ProSync.model.OrderDetail;
import com.github.rdsc.dev.ProSync.model.OrderList;
import com.github.rdsc.dev.ProSync.model.Payment;
import com.github.rdsc.dev.ProSync.repository.OrderDetailRepository;
import com.github.rdsc.dev.ProSync.repository.OrderListRepository;
import com.github.rdsc.dev.ProSync.repository.PaymentRepository;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
@Validated
@Slf4j
public class AdminOrderController {

    private final OrderListRepository orderListRepo;
    private final OrderDetailRepository orderDetailRepo;
    private final PaymentRepository paymentRepo;

    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("Admin orders ok");
    }

    @GetMapping("/by-status")
    public ResponseEntity<List<OrderDto.OrderSummary>> listByStatus (@RequestParam String status) {
        OrderStatus ot;
        try{
            ot = OrderStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid order status: " + status);
        }

        List<OrderList> orders = orderListRepo.findAllByStatus(ot);
        List<OrderDto.OrderSummary> lists = orders.stream().map(o -> {
            List<OrderDetail> details = orderDetailRepo.findAllByOrderListId(o.getId());
            List<OrderDto.OrderDetailInfo> orderDetailInfo = details.stream().map(
                    OrderDto.OrderDetailInfo::of).toList();
            List<Payment> payments = paymentRepo.findAllByOrderListId(o.getId());
            List<PaymentDto.CryptoQuoteResponse> paymentInfo = payments.stream().map(
                    PaymentDto.CryptoQuoteResponse::of).toList();

            return OrderDto.OrderSummary.of(o, orderDetailInfo, paymentInfo);
        }).toList();

        return ResponseEntity.ok(lists);
    }
}
