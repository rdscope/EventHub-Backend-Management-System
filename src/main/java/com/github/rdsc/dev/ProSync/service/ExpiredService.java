package com.github.rdsc.dev.ProSync.service;

import com.github.rdsc.dev.ProSync.enums.OrderStatus;
import com.github.rdsc.dev.ProSync.enums.PaymentStatus;
import com.github.rdsc.dev.ProSync.model.OrderDetail;
import com.github.rdsc.dev.ProSync.model.OrderList;
import com.github.rdsc.dev.ProSync.model.Payment;
import com.github.rdsc.dev.ProSync.model.TicketType;
import com.github.rdsc.dev.ProSync.repository.OrderListRepository;
import com.github.rdsc.dev.ProSync.repository.PaymentRepository;
import com.github.rdsc.dev.ProSync.repository.TicketTypeRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor // Repository自動被注入，不用寫 @Autowired
@Slf4j
@Service // 把這個類別註冊成 Spring Bean, 自動發現並管理這個排程任務
public class ExpiredService {

    private final OrderListRepository orderListRepo;
    private final TicketTypeRepository ticketTypeRepo;
    private final PaymentRepository paymentRepo;

    /**
     * 標記「已過期未付款」的付款單為 EXPIRED
     * 每 15 秒掃一次（只改 Payment，不回補庫存）
    **/
    @Transactional(isolation = Isolation.REPEATABLE_READ)
//    @Scheduled(fixedDelay = 15_000, initialDelay = 5_000)
    @Scheduled(
            fixedDelayString = "${app.jobs.expire-payments.delay-ms:15000}",
            initialDelayString = "${app.jobs.expire-payments.initial-delay-ms:5000}"
    )
    public void expirePaymentsService() {

        LocalDateTime now = LocalDateTime.now();

        // 找出「狀態=PENDING 且 已過期」的付款單
        List<Payment> expiredPayments = paymentRepo.findAllByStatusAndExpiresAtBefore(PaymentStatus.PENDING, now);

        if (expiredPayments.isEmpty()) return;

        int itemsCount = 0;
        for (var p : expiredPayments) {

            p.setStatus(PaymentStatus.EXPIRED);
            paymentRepo.save(p);

            itemsCount++;
        }
        log.info("expirePayments: expired payments marked = {}", itemsCount);
    }

    /**
     * 作廢過期的訂單
     * 每 30 秒掃一次：把逾時未付的訂單回補庫存，並把訂單狀態改成 EXPIRED
     * 用 REPEATABLE_READ 配合 TicketType 的 @Version（樂觀鎖），避免超賣/少補。
    **/
    @Transactional(isolation = Isolation.REPEATABLE_READ)
//    @Scheduled(fixedDelay = 30_000, initialDelay = 5_000)
    @Scheduled(
            fixedDelayString = "${app.jobs.restock-orders.delay-ms:30000}",
            initialDelayString = "${app.jobs.restock-orders.initial-delay-ms:5000}"
    )
    // 排程註解：fixedDelayString(上一輪結束後，等多久再啟動下一輪)
    // @Scheduled(fixedDelayString = "${app.jobs.expired-orders.delay-ms:30000}") // <- 這裡是預設，實際執行要看yml
    public void restockExpiredOrder() {

        LocalDateTime now = LocalDateTime.now();
        // 1/ 找過期未付款：status=PENDING_PAYMENT 且 expiresAt < now
        List<OrderList> expiredLists = orderListRepo.findAllByStatusAndExpiresAtBefore(OrderStatus.PENDING_PAYMENT, now);

        if (expiredLists.isEmpty()) {
            return;
        }

        for (OrderList list : expiredLists) {

            try{
                // 重新抓「含明細」的一筆，避免 LAZY 取不到 detail
                Optional<OrderList> listWithDetail = orderListRepo.findByIdWithDetail(list.getId());

                if (listWithDetail.isEmpty()) continue;

                OrderList lwd = listWithDetail.get();

                // 再次確認狀態與到期
                if (lwd.getStatus() != OrderStatus.PENDING_PAYMENT
                        || lwd.getExpiresAt() == null
                        || !lwd.getExpiresAt().isBefore(now)) {
                    continue; // ← 新增：不是待付款或其實未過期，就略過
                }

                int ttCount = 0; // 計數器：統計「實際回補的張數（qty 加總）」

                // 把每一筆明細的數量加回該票種的 quota
                for(OrderDetail item : lwd.getDetail()) {

                    TicketType tt = item.getTicketType();
                    Integer qty = item.getQuantity();
                    if (tt == null || qty == null || qty <= 0) continue; // continue 跳出，換下一筆

                    try{
                        // 回補庫存
                        tt.increaseQuota(qty);
                        ticketTypeRepo.save(tt);
                        ttCount += qty; // 累加張數
                    } catch (ObjectOptimisticLockingFailureException e) {
                        // 有別的交易剛改過這筆票種，先略過，下次排程再補
                        log.warn("Refund quota optimistic lock failed, ticketTypeId = {}, orderDetailId = {}, orderListId = {}",
                                tt.getId(), item.getId(), lwd.getId());
                    }
                }

                // 狀態改 EXPIRED
                lwd.setStatus(OrderStatus.EXPIRED);
                orderListRepo.save(lwd);


                log.info("reclaimExpiredOrders: orderListId = {}, unitsRestocked = {}, newStatus = EXPIRED", lwd.getId(), ttCount);

            } catch (Exception ex) {

                // 單筆失敗不影響其他筆，記一條警告
                log.warn("reclaimExpiredOrders failed for orderListId = {}", list.getId(), ex);
            }

        }
    }
}
