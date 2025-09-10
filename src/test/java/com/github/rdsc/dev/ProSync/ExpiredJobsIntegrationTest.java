package com.github.rdsc.dev.ProSync;

import com.github.rdsc.dev.ProSync.enums.OrderStatus;
import com.github.rdsc.dev.ProSync.enums.PaymentStatus;
import com.github.rdsc.dev.ProSync.enums.UserStatus;
import com.github.rdsc.dev.ProSync.model.*;
import com.github.rdsc.dev.ProSync.repository.*;
import com.github.rdsc.dev.ProSync.security.RedisLockHelper;
import com.github.rdsc.dev.ProSync.service.TicketOrderService;
import jakarta.annotation.Resource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目的（白話）：
 * - 建一張會「過期」的訂單與付款
 * - 模擬排程的掃描邏輯：把過期的 Payment 標成 EXPIRED、把過期的 Order 標成 EXPIRED 並回補庫存
 *
 * 說明：
 * - 直接在測試內呼叫 Repository 做「排程該做的事」，等同驗證排程效果（不需要真的等 @Scheduled 時間到）
 * - 若已有 ExpiredOrderService/ExpiredPaymentService，可把「模擬段」改成直接呼叫那兩個 Service 的方法。
**/
@SpringBootTest
// @Transactional // 測試方法整段加了 @Transactional，導致 reserve() 裡面用 REQUIRES_NEW 看不到存的訂單
        // 把訂單存起來但「還沒交作業（沒 commit）」；reserve() 開了另一張作業本（新交易）去找，當然找不到 → 404
// @Rollback
class ExpiredJobsIntegrationTest {

    @PersistenceContext
    EntityManager em;
    @Resource UserRepository ur;
    @Resource EventRepository er;
    @Resource TicketTypeRepository tr;
    @Resource OrderListRepository lr;
    @Resource OrderDetailRepository dr;
    @Resource PaymentRepository pr;
    @Resource TicketOrderService ts;

    // 測試時把分散式鎖假成功（避免依賴本機 Redis）
    @MockitoBean
    RedisLockHelper rlock;

    @Test
    @DisplayName("Expired Payment：PENDING + expires_at < now → 轉 EXPIRED")
    @WithMockUser(username = "expire-test@example.com", roles = {"USER"})
    void expired_payment_should_be_marked_expired() {
        // 準備基本資料（使用者/活動/票種/訂單）
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

        // 鎖假成功
        Mockito.when(rlock.lockSeat(Mockito.anyLong(), Mockito.any())).thenReturn("test-token");
        Mockito.doNothing().when(rlock).unlockSeat(Mockito.anyLong(), Mockito.anyString());

        // 下單 1 張（讓 order 有金額）
        ts.reserve(ol.getId(), t.getId(), 1);

        // 建一筆已過期的 PENDING 付款
        Payment p = Payment.builder()
                .orderList(ol)
                .asset("BTC")
                .quoteRate(new BigDecimal("1000000.00000000"))
                .amountCrypto(new BigDecimal("0.000500000000000000"))
                .status(PaymentStatus.PENDING)
                .expiresAt(LocalDateTime.now().minusMinutes(1)) // 已過期
                .build();
        pr.save(p);

        // === 模擬排程：把過期的 PENDING 付款轉成 EXPIRED ===
        List<Payment> expiredPs = pr.findAllByStatusAndExpiresAtBefore(PaymentStatus.PENDING, LocalDateTime.now());
        expiredPs.forEach(pay -> pay.setStatus(PaymentStatus.EXPIRED));
        pr.saveAll(expiredPs);

        // 驗證
        Payment afterP = pr.findById(p.getId()).orElseThrow();
        assertThat(afterP.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
    }

    @Test
    @DisplayName("Expired Orderlist：PENDING_PAYMENT + expires_at < now → change to EXPIRED and restock")
    @WithMockUser(username = "expire-test@example.com", roles = {"USER"})
    void expired_order_should_be_marked_expired_and_restock_quota() {
        // 準備基本資料
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

        // 鎖假成功
        Mockito.when(rlock.lockSeat(Mockito.anyLong(), Mockito.any())).thenReturn("test-token");
        Mockito.doNothing().when(rlock).unlockSeat(Mockito.anyLong(), Mockito.anyString());

        // 預留 3 張（庫存 10 → 7）
        ts.reserve(ol.getId(), t.getId(), 3);

        // 把訂單手動改成「已過期」
        ol = lr.findById(ol.getId()).orElseThrow();
        LocalDateTime past = LocalDateTime.now().minusMinutes(1);
        ol.setExpiresAt(past);
        lr.saveAndFlush(ol);

        em.clear();

        // === 模擬排程：找出過期的 PENDING_PAYMENT 訂單，轉 EXPIRED 並回補庫存 ===
        List<OrderList> expiredOrders =
                lr.findAllByStatusAndExpiresAtBefore(OrderStatus.PENDING_PAYMENT, LocalDateTime.now());
        for (OrderList o : expiredOrders) {
            // 回補此訂單所有明細使用的庫存
            var ods = dr.findAllByOrderListId(o.getId());
            for (OrderDetail od : ods) {
                // 先拿關聯的 id，不會觸發 LAZY 初始化
                Long ticketTypeId = od.getTicketType().getId();
                // 用 Repository 重新撈一個「已管理」的 TicketType 實體
                TicketType odT = tr.findById(ticketTypeId).orElseThrow();
                int base = (odT.getQuota() == null ? 0 : odT.getQuota());
                odT.setQuota(base + od.getQuantity());
                tr.save(odT);
            }
            // 訂單標記 EXPIRED
            o.setStatus(OrderStatus.EXPIRED);
            lr.save(o);
        }

        // 驗證：訂單變 EXPIRED、庫存回補到 10
        OrderList finalOl = lr.findById(ol.getId()).orElseThrow();
        TicketType finalT = tr.findById(t.getId()).orElseThrow();

        assertThat(finalOl.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(finalT.getQuota()).isEqualTo(10);
    }
}
