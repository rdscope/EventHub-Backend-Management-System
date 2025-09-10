package com.github.rdsc.dev.ProSync;

import com.github.rdsc.dev.ProSync.enums.OrderStatus;
import com.github.rdsc.dev.ProSync.enums.PaymentStatus;
import com.github.rdsc.dev.ProSync.enums.UserStatus;
import com.github.rdsc.dev.ProSync.model.*;
import com.github.rdsc.dev.ProSync.repository.*;
import com.github.rdsc.dev.ProSync.security.RedisLockHelper;
import com.github.rdsc.dev.ProSync.service.ExpiredService;
import com.github.rdsc.dev.ProSync.service.TicketOrderService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
//@Transactional
//@Rollback
class ExpiredServiceIntegrationTest {

    // === Services ===
    @Resource ExpiredService es;
    @Resource TicketOrderService ts;

    // === Repositories ===
    @Resource UserRepository ur;
    @Resource EventRepository er;
    @Resource TicketTypeRepository tr;
    @Resource OrderListRepository lr;
    @Resource OrderDetailRepository dr;
    @Resource PaymentRepository pr;

    // reserve() 會用到鎖；測試中把它「假成功」
    @MockitoBean
    RedisLockHelper rlock;

    @Test
    @DisplayName("Expired Payment：PENDING + expires_at < now → 轉 EXPIRED")
    @WithMockUser(username = "expire-test@example.com", roles = {"USER"})
    void expirePaymentsService_marks_expired() {
        // 1/ 基本資料
        final String email = "expire-test@example.com"; // 或 "test.user@example.com"：請跟 @WithMockUser 對齊
        User u = ur.findByEmail(email).orElseGet(() -> {
            User nu = new User();
            nu.setEmail(email);
            nu.setStatus(UserStatus.ACTIVE);
            nu.setPasswordHash("{noop}pw"); // 測試用，滿足非空
            return ur.save(nu);
        });

        Event e = new Event();
        e.setName("Payment Exp Test");
        e.setDescription("Payment Expired testing activities");
        e.setStartAt(LocalDateTime.now().plusDays(1));
        e.setEndAt(LocalDateTime.now().plusDays(1).plusHours(2));
        er.save(e);

        TicketType t = new TicketType();
        t.setEvent(e);
        t.setName("Standard");
        t.setPrice(new BigDecimal("500.00"));
        t.setQuota(5);
        tr.save(t);

        OrderList ol = new OrderList();
        ol.setUser(u);
        ol.setStatus(OrderStatus.PENDING_PAYMENT);
        ol.setTotalCost(new BigDecimal("0.00"));
        ol.setExpiresAt(LocalDateTime.now().plusMinutes(30));
        ol = lr.save(ol);

        // 2/ 預留 1 張（讓訂單有金額，方便支付模擬）
        Mockito.when(rlock.lockSeat(Mockito.anyLong(), Mockito.any())).thenReturn("test-token");
        Mockito.doNothing().when(rlock).unlockSeat(Mockito.anyLong(), Mockito.anyString());
        ts.reserve(ol.getId(), t.getId(), 1);

        // 3/ 建一筆「已過期」的 PENDING 付款
        Payment p = Payment.builder()
                .orderList(ol)
                .asset("BTC")
                .quoteRate(new BigDecimal("1000000.00000000"))
                .amountCrypto(new BigDecimal("0.000500000000000000"))
                .status(PaymentStatus.PENDING)
                .expiresAt(LocalDateTime.now().minusMinutes(1)) // 已過期
                .build();
        pr.save(p);

        // 4/ 呼叫服務：應把過期的 PENDING 標為 EXPIRED
        es.expirePaymentsService();

        Payment afterP = pr.findById(p.getId()).orElseThrow();
        assertThat(afterP.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
    }

    @Test
    @DisplayName("Expired Orderlist：PENDING_PAYMENT + expires_at < now → change to EXPIRED and restock")
    @WithMockUser(username = "expire-test@example.com", roles = {"USER"})
    void restockExpiredOrder_marks_and_restock() {
        // 1/ 基本資料
        final String email = "expire-test@example.com"; // 或 "test.user@example.com"：請跟 @WithMockUser 對齊
        User u = ur.findByEmail(email).orElseGet(() -> {
            User nu = new User();
            nu.setEmail(email);
            nu.setStatus(UserStatus.ACTIVE);
            nu.setPasswordHash("{noop}pw"); // 測試用，滿足非空
            return ur.save(nu);
        });

        Event e = new Event();
        e.setName("Order Exp Test");
        e.setDescription("Orderlist Expired testing activities");
        e.setStartAt(LocalDateTime.now().plusDays(1));
        e.setEndAt(LocalDateTime.now().plusDays(1).plusHours(2));
        er.save(e);

        TicketType t = new TicketType();
        t.setEvent(e);
        t.setName("EarlyBird");
        t.setPrice(new BigDecimal("800.00"));
        t.setQuota(10);
        tr.save(t);

        OrderList ol = new OrderList();
        ol.setUser(u);
        ol.setStatus(OrderStatus.PENDING_PAYMENT);
        ol.setTotalCost(new BigDecimal("0.00"));
        ol.setExpiresAt(LocalDateTime.now().plusMinutes(30));
        ol = lr.save(ol);

        // 2/ 預留 3 張（庫存 10 → 7）
        Mockito.when(rlock.lockSeat(Mockito.anyLong(), Mockito.any())).thenReturn("test-token");
        Mockito.doNothing().when(rlock).unlockSeat(Mockito.anyLong(), Mockito.anyString());
        ts.reserve(ol.getId(), t.getId(), 3);

        // 3/ 手動把訂單設為「已過期」
        ol.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        lr.save(ol);

        // 4/ 呼叫服務：應將訂單轉 EXPIRED 並回補庫存
        es.restockExpiredOrder();

        OrderList finalOl = lr.findById(ol.getId()).orElseThrow();
        TicketType finalT = tr.findById(t.getId()).orElseThrow();

        assertThat(finalOl.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(finalT.getQuota()).isEqualTo(10); // 回補到原本的庫存
    }
}
