package com.github.rdsc.dev.ProSync.service;

import com.github.rdsc.dev.ProSync.crypto.FakePriceFeed;
import com.github.rdsc.dev.ProSync.crypto.PriceFeed;
import com.github.rdsc.dev.ProSync.enums.OrderStatus;
import com.github.rdsc.dev.ProSync.enums.PaymentStatus;
import com.github.rdsc.dev.ProSync.model.OrderList;
import com.github.rdsc.dev.ProSync.model.Payment;
import com.github.rdsc.dev.ProSync.repository.OrderListRepository;
import com.github.rdsc.dev.ProSync.repository.PaymentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.parameters.P;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@RequiredArgsConstructor
@Slf4j
@Service
// 建立報價與確認交易
public class PaymentService {

    private final PriceFeed priceFeed; // 報價來源 / 固定價
    private final TicketOrderService ticketOrderService; // 付款成功後要呼叫 confirmPayment()
    private final OrderListRepository orderRepo; // 讀訂單
    private final PaymentRepository paymentRepo; // 存取付款

    @PersistenceContext
    private EntityManager em;

    /**
     * 建立加密付款報價：產生一筆 PENDING 的 Payment
     * 流程：找訂單 → 取報價 → 算要付多少幣 → 存 Payment（PENDING，15 分鐘過期）
    **/
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Payment createCryptoQuote(Long orderListId, String asset) {
        if (orderListId == null) {
            throw new IllegalArgumentException("orderListId cannot be null");
        }
        if (asset == null || asset.isBlank()) {
            throw new IllegalArgumentException("asset cannot be blank");
        }

        OrderList orderList = orderRepo.findByIdAndStatus(orderListId, OrderStatus.PENDING_PAYMENT)
                .or(() -> orderRepo.findById(orderListId)
                        .map(o -> {
                            throw new IllegalStateException("The order is in " + o.getStatus() + " statement, can only quote in PENDING_PAYMENT statement.");
                        })
                )
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found: " + orderListId));

        if (orderList.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                                                    "Order status is " + orderList.getStatus() + " — can only quote in PENDING_PAYMENT");
        }


        if (orderList.getExpiresAt() != null && orderList.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order expired at " + orderList.getExpiresAt());
        }

//        OrderList orderList = orderRepo.findById(orderListId)
//                .orElseThrow(() -> new IllegalArgumentException("Cannot find this orderList: " + orderListId));
//        OrderList orderList = em.find(OrderList.class, orderListId);
//        if (orderList == null) throw new IllegalArgumentException("Cannot find this orderList: " + orderListId);

        // 1/ 報價（1 asset = ? TWD）

        // 如果這張訂單「已經有一筆最新的 PENDING 報價，且尚未過期」 → 直接回那一筆就好
        Payment latestPending = paymentRepo.findFirstByOrderListIdAndStatusOrderByCreateAtDesc(orderListId, PaymentStatus.PENDING).orElse(null);
        if (latestPending != null && latestPending.getExpiresAt() != null
                && latestPending.getExpiresAt().isAfter(LocalDateTime.now())) {
            return latestPending; // 直接回，不另外新增
        }

        BigDecimal quoteRate = priceFeed.getQuote(asset); // 拿報價
        String base = priceFeed.getBaseCurrency(); // 拿報價單位：eg TWD
        log.info("Create quote: orderListId = {}, asset = {}, quoteRate = {} {}", orderListId, asset, quoteRate, base);

        // 2/ 計算要付多少幣 「要付多少顆幣」 = 「台幣金額 ÷ 幣價」
        // 先放 0，實作把「訂單應付總額」接進來（例如 order.getTotalAmount() 或加總明細
        BigDecimal amountFiat = computeOrderFiatAmount(orderList);
        if (amountFiat == null || amountFiat.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order totalCost must be positive");
        }
        BigDecimal amountCrypto = amountFiat.divide(quoteRate, 18, RoundingMode.UP);
                                  // BigDecimal + precision/scale + RoundingMode.UP
                                  // 金額要很精準，不能用 double。scale=18 表示存到小數 18 位，向上進位避免少收錢。
                                  // 保留到小數 18 位；向上進位（確保不會少收）

        // 3/ 建立 Payment（TX_PENDING，15 分鐘過期）
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime quoteExpires = now.plus(15, ChronoUnit.MINUTES);
        LocalDateTime orderExpires = orderList.getExpiresAt();

        // 取較早的那個，確保 payment 不會比 order 活得更久
        LocalDateTime finalExpires = (orderExpires != null && quoteExpires.isAfter(orderExpires))
                ? orderExpires : quoteExpires;

        Payment p = Payment.builder()
                            .asset(asset.trim().toUpperCase())
                            .quoteRate(quoteRate)
                            .amountCrypto(amountCrypto)
                            .status(PaymentStatus.PENDING)
                            .expiresAt(finalExpires)
                            .build();

        p.setOrderList(orderList);   // 1/ 擁有端（ManyToOne）先設定
        orderList.addPayment(p);     // 2/ 反向端（OneToMany）也維護一下關聯

//        em.persist(p);
        return paymentRepo.save(p);
    }

    /**
     * 確認鏈上交易：寫入 txHash → 將 Payment 標記為 CONFIRMED → 呼叫 TicketService.confirmPayment()
    **/
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Payment confirmCryptoTx(Long paymentId, String txHash) {
        if (paymentId == null) {
            throw new IllegalArgumentException("paymentId cannot be null");
        }
        if (txHash == null || txHash.isBlank()) {
            throw new IllegalArgumentException("txHash is required");
        }
        Payment p = paymentRepo.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found: " + paymentId));
//        Payment payment = em.find(Payment.class, paymentId);
//        if (payment == null) throw new IllegalArgumentException("Cannot find this payment: " + paymentId);

        if (p.getStatus() == PaymentStatus.CONFIRMED) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment already confirmed"); }

        if (p.getExpiresAt() != null && p.getExpiresAt().isBefore(LocalDateTime.now())) { throw new ResponseStatusException(
                                                            HttpStatus.CONFLICT, "Quote expired at " + p.getExpiresAt()); }

        // 驗過期、驗狀態
        // 訂單不是等待付款，就不能確認付款
        OrderList orderList = p.getOrderList();

        if (orderList.getStatus() == OrderStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order already confirmed");
        }

        if (!"PENDING_PAYMENT".equalsIgnoreCase(orderList.getStatus().toString())) {
            log.warn("Order not payable: orderListId={}, status={}", orderList.getId(), orderList.getStatus());
            p.setStatus(PaymentStatus.FAILED);
            paymentRepo.save(p);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order not payable in status: " + orderList.getStatus());
        }

        if(orderList.getStatus() == OrderStatus.EXPIRED && orderList.getExpiresAt() != null && LocalDateTime.now().isAfter(orderList.getExpiresAt())) {
            log.warn("Order expired when confirming: orderListId={}, paymentId={}", orderList.getId(), p.getId());
            p.setStatus(PaymentStatus.EXPIRED);
            paymentRepo.save(p);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order expired at " + orderList.getExpiresAt());
        }

        // txHash 不可被其他 payment 佔用
        Payment payDup = paymentRepo.findByTxHash(txHash).orElse(null);
        if (payDup != null && !payDup.getId().equals(paymentId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "txHash already used by payment " + payDup.getId());
        }

        // 設定交易哈希並標記已確認
        p.setTxHash(txHash); // 寫入鏈上交易哈希（txHash）
        p.setStatus(PaymentStatus.CONFIRMED);
//        p = em.merge(p);
        Payment saved = paymentRepo.save(p);

        // 付款成功 → 確認訂單
        ticketOrderService.confirmPayment(saved.getOrderList().getId());
        return saved;
    }

    private BigDecimal computeOrderFiatAmount(OrderList orderList) {
        if(orderList.getTotalCost() == null) {
            log.warn("computeOrderFiatAmount: Cannot define totalCost, Default: 0");
            return BigDecimal.ZERO;
        }
        return orderList.getTotalCost();
    }
}
