package com.github.rdsc.dev.ProSync.controller;

import com.github.rdsc.dev.ProSync.dto.OrderDto;
import com.github.rdsc.dev.ProSync.enums.OrderStatus;
import com.github.rdsc.dev.ProSync.enums.PaymentMethod;
import com.github.rdsc.dev.ProSync.enums.PaymentStatus;
import com.github.rdsc.dev.ProSync.model.OrderList;
import com.github.rdsc.dev.ProSync.model.User;
import com.github.rdsc.dev.ProSync.repository.OrderDetailRepository;
import com.github.rdsc.dev.ProSync.repository.OrderListRepository;
import com.github.rdsc.dev.ProSync.repository.UserRepository;
import com.github.rdsc.dev.ProSync.service.TicketOrderService;

import com.github.rdsc.dev.ProSync.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@PreAuthorize("hasRole('USER')")
@RequestMapping("/api/public/orders")
@RequiredArgsConstructor
@Validated // 開啟 Spring 的參數驗證支持
@Slf4j
public class PublicOrderController {

    private final TicketOrderService ticketOrderService;
    private final OrderListRepository orderListRepo;
    private final OrderDetailRepository orderDetailRepo;
    private final UserService userService;

    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("Public orders ok");
    }

    @GetMapping("/my")
    public ResponseEntity<List<OrderDto.MyOrderResponse>> myOrders() {
        Long userId = currentUserId();
        List<OrderList> orderLists = orderListRepo.findAllByUserIdOrderByCreateAtDesc(userId);

        var body = orderLists.stream().map(o -> new OrderDto.MyOrderResponse(
                o.getId(),
                o.getStatus(),
                o.getTotalCost(),
//                orderDetailRepo.totalCostByOrderListId(o.getId()),
                o.getExpiresAt()
        )).toList();

        return ResponseEntity.ok(body);
    }

    // 下單
    @PostMapping("/create-order") // 訂單驗證：扣配額→建訂單
    public ResponseEntity<OrderDto.ReserveResponse> createOrder(@RequestBody @Valid OrderDto.ReserveRequest req) {

        if (req == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (req.getTicketTypeId() == null) {
            throw new IllegalArgumentException("ticketTypeId is required");
        }
        if (req.getQuantity() == null || req.getQuantity() <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null ) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated user");
        }
        String email = auth.getName();
        User user = userService.findByEmail(email).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + email));

        // 3/ 取得/建立訂單清單（支援帶 orderListId 續加）
        OrderList ord;
        if (req.getOrderListId() != null) {
            // 3a/ 取既有訂單
            ord = orderListRepo.findById(req.getOrderListId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found: " + req.getOrderListId()));

            // 3b/ 驗證所有權（不是本人的訂單 → 403）
            if (ord.getUser() == null || !ord.getUser().getId().equals(user.getId())) {
                // 用 Spring Security 的 AccessDeniedException 讓全域處理器回 403
                throw new AccessDeniedException("Forbidden: not the owner of this order");
            }

            // 3c/ 狀態需可續加（不是待付款/可續加 → 409）
            if (!ord.isPending()) {
                throw new IllegalStateException("This orderList is in " + ord.getStatus()
                        + " state, can only add items in PENDING_PAYMENT state.");
            }
            if (ord.isExpired()) {
                throw new IllegalStateException("This orderList has expired on " + ord.getExpiresAt());
            }
        } else {
            // 3d/ 沒帶 id → 新建一筆屬於此使用者的訂單清單
            ord = OrderList.builder().user(user).build();
            ord = orderListRepo.save(ord);
        }

        Long orderListId = ticketOrderService.reserve(ord.getId(), req.getTicketTypeId(), req.getQuantity());

        log.info("createOrder: orderListId = {}, ticketTypeId = {}, quantity = {}", ord.getId(), req.getTicketTypeId(), req.getQuantity());

        OrderList orderList = orderListRepo.findById(orderListId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found: " + orderListId));
        var body = new OrderDto.ReserveResponse(
                orderList.getId(),
                orderList.getStatus(),
                orderList.getTotalCost(),
                orderList.getExpiresAt()
        );

        return ResponseEntity.ok(body);
    }

    // 付款
    @PostMapping("/pay-order/{id}") // 付款驗證
    public ResponseEntity<Map<String, Object>> payOrder(@PathVariable("id") Long orderListId,
                                               @RequestParam(value = "method", required = false) PaymentMethod method) {

        if (method == null) {
            throw new IllegalArgumentException("Choose a payment method");
        }

        if (method != null && method.equals(PaymentMethod.CRYPTO)) {
            throw new IllegalArgumentException("Crypto payment must use /api/payments/{paymentId}/confirm with a valid txHash.");
        }

        ticketOrderService.confirmPayment(orderListId);

        log.info("POST /api/orders/{}/pay, method = {}", orderListId, method != null ? method : "none");

        Map<String, Object> body = new HashMap<>();
        body.put("orderListId", orderListId);
        body.put("status", OrderStatus.CONFIRMED);
        body.put("message", "payment confirmed");
        body.put("method", method);
        return ResponseEntity.ok(body); // 200 OK
    }


//    /**
//     * 改成呼叫 TicketService.reserve(...)
//    **/
//    @PostMapping // 訂單驗證：扣配額→建訂單
//    public Map<String, Object> createOrder(@RequestBody @Valid OrderDto.ReserveRequest req) {
//           // 等於回傳 JSON                 @RequestBody 把 HTTP 請求的 Body（通常是 JSON）去自動轉成 Java 物件
//                                           // 把請求的 JSON 轉成 ReserveRequest 物件，並且進行驗證
//                                           // 驗證在 ReserveRequest 上寫的註解，有任何一條不符，就「不會執行方法本體」，直接回 400
//                                           // @Validated：給「方法參數本身」用（例如 @PathVariable @Min(1) id）。
//                                           // @Valid：給「複合物件」用（像 ReserveRequest 這種物件、還能往下驗證巢狀欄位）。
//                                           // → 簡單記：@RequestBody 收物件就用 @Valid，路徑/查詢參數這種單值用 @Validated（或直接在參數上放約束）。
//                                           // 建單是必填表單，不給就錯，所以不要 required=false
//        log.info("POST /api/orders (stub) userId = {}, ticketTypeId = {}, quantity = {}",
//                req.getUserId(), req.getTicketTypeId(), req.getQuantity());
//
//        Map<String, Object> body = new HashMap<>();
//        body.put("message", "Order reserved (stub)");
//        body.put("orderListId", -1L); // -1（Long 型別）會換成真的訂單編號
//        body.put("echo", req); // 回傳剛剛用 @RequestBody 傳進來的請求物件（裡面有 userId、ticketTypeId、quantity）
//        return body;
//    }
//
//    /**
//     * 改成呼叫 TicketService.confirmPayment(...)
//    **/
//    @PostMapping("/{id}/pay") // 付款驗證
//    public Map<String, Object> payOrder(@PathVariable("id") Long orderListId,
//                                        @RequestBody(required = false) OrderDto.PayRequest req) {
//        log.info("POST /api/orders/{}/pay (stub)", orderListId);
//
//        Map<String, Object> body = new HashMap<>();
//        body.put("message", "payment confirmed (stub)");
//        body.put("orderListId", orderListId);
//        if (req != null) body.put("echo", req);
//        return body; // 200 OK
//    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated user");
        }
        String email = auth.getName();
        User user = userService.findByEmail(email).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + email));
        return user.getId();
    }


}
