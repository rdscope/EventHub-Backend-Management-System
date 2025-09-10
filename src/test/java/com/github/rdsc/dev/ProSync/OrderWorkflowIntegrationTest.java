package com.github.rdsc.dev.ProSync;

import com.github.rdsc.dev.ProSync.enums.OrderStatus;
import com.github.rdsc.dev.ProSync.enums.PaymentStatus;
import com.github.rdsc.dev.ProSync.enums.UserStatus;
import com.github.rdsc.dev.ProSync.model.*;
import com.github.rdsc.dev.ProSync.repository.*;
import com.github.rdsc.dev.ProSync.security.RedisLockHelper;
import com.github.rdsc.dev.ProSync.service.PaymentService;
import com.github.rdsc.dev.ProSync.service.TicketOrderService;
import jakarta.annotation.Resource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;


/**
 * 目標（白話）：
 * 1) 建一個活動 + 票種（有庫存、價格）
 * 2) 建一張訂單（PENDING_PAYMENT、未過期）
 * 3) 呼叫 reserve() 把該票種加進訂單（庫存會減）
 * 4) 呼叫 createCryptoQuote() 產一筆付款報價（PENDING）
 * 5) 呼叫 confirmCryptoTx() → 訂單變 CONFIRMED、付款變 CONFIRMED
 *
 * 技巧：
 * - 測試用 @MockBean 把 RedisLockHelper 鎖「假成功」，避免本機沒跑 Redis 也能測。
 * - 用 @WithMockUser 模擬已登入的 USER（reserve() 裡會從 SecurityContext 取 email）。
**/
@SpringBootTest
// @Transactional
// @Rollback // 每次跑完自動回滾，避免弄髒資料庫
class OrderWorkflowIntegrationTest {

    // 服務
    @PersistenceContext
    EntityManager em;
    @Resource
    TicketOrderService ts;
    @Resource
    PaymentService ps;

    // 資料存取
    @Resource
    UserRepository ur;
    @Resource
    EventRepository er;
    @Resource
    TicketTypeRepository tr;
    @Resource
    OrderListRepository lr;
    @Resource
    OrderDetailRepository dr;
    @Resource
    PaymentRepository pr;

    // 把分散式鎖「假成功」，讓測試環境不用真的連 Redis
    @MockitoBean
    RedisLockHelper rlock;

    @Test
    @DisplayName("// FULL WORKFLOW // createOrder -> reserve //-> createQuote -> createCryptoQuote //-> confirmTx -> confirmCryptoTx //-> payOrder -> confirmPayment //")
    @WithMockUser(username = "test@example.com", roles = {"USER"})
        // 幫測試假裝登入，把帳號與角色塞進 SecurityContext。
        // 只是在測試時，把 登入者帳號 放進 SecurityContext（記憶體）。它不會在資料庫建立使用者。
    void OrderWorkflow() {
        // 0/ 測試前置——建立使用者
        final String email = "test@example.com"; // 或 "test.user@example.com"：請跟 @WithMockUser 對齊
        User u = ur.findByEmail(email).orElseGet(() -> {
            User nu = new User();
            nu.setEmail(email);
            nu.setStatus(UserStatus.ACTIVE);
            nu.setPasswordHash("{noop}pw"); // 測試用，滿足非空
            return ur.save(nu);
        });

        // 1/ 建活動
        Event e = new Event();
        e.setName("Testing Concert");
        e.setDescription("Integration testing activities");
        e.setStartAt(LocalDateTime.now().plusDays(3));
        e.setEndAt(LocalDateTime.now().plusDays(6).plusHours(8));
        er.save(e);

        // 2/ 建票種
        TicketType t = new TicketType();
        t.setEvent(e);
        t.setName("Standard");
        t.setPrice(new BigDecimal("1000"));
        t.setQuota(10);
        tr.save(t);

        // 3/ 建訂單
        OrderList ol = new OrderList();
        ol.setUser(u);
        ol.setStatus(OrderStatus.PENDING_PAYMENT);
        ol.setTotalCost(new BigDecimal("0.00"));
        ol.setExpiresAt(LocalDateTime.now().plusMinutes(30));
        lr.save(ol);

        // 4/ 模擬「搶鎖成功」：任何 ticketTypeId 都回傳 token
        Mockito.when(rlock.lockSeat(Mockito.anyLong(), Mockito.any()))
                .thenReturn("test-token");
        // 測試時「一定搶得到鎖」，這樣就不需要真的啟動 -> Redis, lockSeat(...) 一律回 "test-token"
        Mockito.doNothing().when(rlock).unlockSeat(Mockito.anyLong(), Mockito.anyString());
        // 解除鎖也「裝作成功」 -> unlockSeat(...) 什麼事都不做，也不會丟錯

        // 5/ 執行 reserve()：把 2 張「standard」加到訂單
        Long olId = ts.reserve(ol.getId(), t.getId(), 2);
        assertThat(olId).isEqualTo(ol.getId());

        // 6/ 驗證庫存被扣、明細/總價正確
        TicketType after = tr.findById(t.getId()).orElseThrow(); // 再抓一次該票種最新資料
        assertThat(after.getQuota()).isEqualTo(8); // 10 - 2

        OrderList afterOl = lr.findById(ol.getId()).orElseThrow(); // 再抓一次訂單最新資料
        assertThat(afterOl.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(afterOl.getTotalCost()).isEqualByComparingTo(new BigDecimal("2000.00")); // 1000 * 2
        assertThat(dr.findByOrderListIdAndTicketTypeId(ol.getId(), t.getId())).isPresent();

        // 7/ 產生一筆加密支付報價（PENDING）
        Payment p = ps.createCryptoQuote(ol.getId(), "BTC");
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(p.getOrderList().getId()).isEqualTo(ol.getId());
        assertThat(p.getAmountCrypto()).isNotNull();
        assertThat(p.getExpiresAt()).isAfter(LocalDateTime.now());

        // 8/ 確認鏈上交易（CONFIRMED）
        String txhash = "0x" + Instant.now().toEpochMilli(); // 簡單做一個不重複的字串
        Payment confirmedP = ps.confirmCryptoTx(p.getId(), txhash);
        assertThat(confirmedP.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);

        // 9) 驗證訂單已轉成 CONFIRMED
        OrderList finalOl = lr.findById(ol.getId()).orElseThrow();
        assertThat(finalOl.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }
}
