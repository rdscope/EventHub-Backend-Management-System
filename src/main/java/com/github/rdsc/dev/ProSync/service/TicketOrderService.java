package com.github.rdsc.dev.ProSync.service;

import com.github.rdsc.dev.ProSync.enums.OrderStatus;
import com.github.rdsc.dev.ProSync.enums.PaymentStatus;
import com.github.rdsc.dev.ProSync.model.*;
import com.github.rdsc.dev.ProSync.repository.OrderDetailRepository;
import com.github.rdsc.dev.ProSync.repository.OrderListRepository;
import com.github.rdsc.dev.ProSync.repository.PaymentRepository;
import com.github.rdsc.dev.ProSync.repository.TicketTypeRepository;
import com.github.rdsc.dev.ProSync.security.RedisLockHelper;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@RequiredArgsConstructor
@Slf4j
@Service
public class TicketOrderService {

    private final TicketTypeRepository ticketTypeRepo;
    private final OrderListRepository orderListRepo;
    private final OrderDetailRepository orderDetailRepo;
    private final PaymentRepository paymentRepo;
    private final UserService userService;
    private final RedisLockHelper rLock;

    private final PlatformTransactionManager txManager;
    private final EntityManager em;
    // 因為 OrderList.user 是 @ManyToOne User user，
    // 需要用 em.getReference(User.class, userId) 取得
    // 不查 DB 的代理 物件來設到訂單上（只帶 id，不會去 insert User）
    // 這是在 Service 裡面拿到 JPA 的「原生工具」。
    // em.getReference(User.class, userId) 可以只用 userId 建個「殼」，
    // 不用真的去 SELECT 一次 User，省查詢

    /**
     * 預約流程：
     * 1) 讀取票種（含 @Version 樂觀鎖）
     * 2) 檢查 quota 是否足夠
     * 3) quota--（靠版本版號防併發）
     * 4) 建立 PENDING_PAYMENT 訂單 + 明細
    **/
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    // @ 告訴 Spring：下面這個方法要放在資料庫交易裡處理（要嘛全成功、要嘛全回滾）。
    // 幫一個方法套「保護罩」＝資料庫交易（transaction）。
    // 同生共死：方法裡一連串 DB 動作，要嘛全部成功（commit），要嘛全部失敗一起回復（rollback）。
    // 例：讀票種 → 檢查庫存 → 扣庫存 → 建訂單，任何一步失敗就整包撤銷，資料不會半套。
    // 隔離級別設為 REPEATABLE_READ，配合 @Version 可處理併發扣庫存
    // 同一個交易裡重讀同一筆資料，不會忽然變（避免幻讀/不可重複讀）。
    // 這是配合票券扣庫存 + 樂觀鎖的建議級別。
    public Long reserve(Long orderListId, Long ticketTypeId, int quantity) {
//        if (userId == null) throw new IllegalArgumentException("userId is required");
        if (orderListId == null) throw new IllegalArgumentException("orderListId is required");
        if (ticketTypeId == null) throw new IllegalArgumentException("ticketTypeId is required");
        if (quantity < 1) throw new IllegalArgumentException("quantity must be >= 1");


//        User user = userService.findById(userId).orElseThrow(() -> new IllegalStateException("User not found: " + userId));
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated user");
        }
        String email = auth.getName();
        User user = userService.findByEmail(email).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + email));

        OrderList list= orderListRepo.findById(orderListId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found: " + orderListId));
        if (!list.isPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                                                "Order status is " + list.getStatus() + " — can only add items in PENDING_PAYMENT");
        }
        if (list.isExpired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order expired at " + list.getExpiresAt());
        }


        // 先搶鎖（seat:{ticketTypeId}，預設 5 秒）
        String lockToken = rLock.lockSeat(ticketTypeId, Duration.ofSeconds(5));
        if (lockToken == null) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many concurrent reservations, please retry");
        }



        try {

            final int maxRetry = 3; // 不可再被改變（常數）
            for (int attempt = 1; attempt <= maxRetry; attempt++) {

                try{ // 每一次嘗試，都在自己的交易裡做
                    TransactionTemplate tpl = new TransactionTemplate(txManager);
                    tpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); // 每次重試用新交易
                    // 一旦某次嘗試拋出 ObjectOptimisticLockingFailureException，外層交易可能被標成 rollback-only，
                    // 之後的重試也會跟著失敗。設為 REQUIRES_NEW 會 暫停外層交易，讓每次重試都是乾淨的新交易。
                    tpl.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

                    final int attemptNo = attempt;

                    return tpl.execute(status -> {

                        OrderList orderList = orderListRepo.findById(orderListId)
                                .orElseThrow(() -> new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Order not found: " + orderListId));
                        if (!orderList.isPending()) {
                            throw new ResponseStatusException(HttpStatus.CONFLICT,
                                    "Order status is " + orderList.getStatus() + " — can only add items in PENDING_PAYMENT");
                        }
                        if (orderList.isExpired()) {
                            throw new ResponseStatusException(HttpStatus.CONFLICT,
                                    "Order expired at " + orderList.getExpiresAt());
                        }

                        // 1/ 讀票種（@Version 會在更新時，做版本比對）
                        TicketType tt = ticketTypeRepo.findByIdWithOptimisticLock(ticketTypeId)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                                                    "TicketType not found: " + ticketTypeId));
                        // ()：參數列表。這裡是空的，表示「不需要參數」。
                        // ->：意思是「把左邊的參數，交給右邊這段要做的事」。

                        // 2/ 檢查庫存
                        Integer quota = tt.getQuota() == null ? 0 : tt.getQuota();
                        if (quota < quantity) {
                            throw new ResponseStatusException(HttpStatus.CONFLICT, "insufficient quota: remain=" + quota + ", need=" + quantity);
                        }

                        // 3/ 扣庫存 樂觀鎖：版本號改變會在 saveAndFlush 時丟出衝突）
                        tt.decreaseQuota(quantity);
                        ticketTypeRepo.saveAndFlush(tt); // 立刻 flush，提早偵測版本衝突

                        // 4/ 建訂單（PENDING_PAYMENT，過期時間先給 30 分鐘）

                        var trySameType = orderDetailRepo.findByOrderListIdAndTicketTypeId(orderListId, ticketTypeId);
                        if (trySameType.isPresent() ) {
                            OrderDetail existed = trySameType.get();
                            int newQty = existed.getQuantity() + quantity;
                            existed.setQuantity(newQty);
                            existed.setUnitPrice(tt.getPrice());
                            existed.recalcCost();
                            orderList.recalcTotal();
                        } else {
                            // 4-1/ 建立訂單明細
                            OrderDetail orderDetail = new OrderDetail();
//                            orderDetail.setOrderList(list);
                            orderDetail.setTicketType(tt);
                            orderDetail.setQuantity(quantity);
                            orderDetail.setUnitPrice(tt.getPrice());
                            orderDetail.recalcCost();
                            orderList.addItem(orderDetail);

                        }

                        orderList.setStatus(OrderStatus.PENDING_PAYMENT);
                        orderList.setExpiresAt(LocalDateTime.now().plusMinutes(30));

                        // 5/ 存檔（Cascade.ALL 會一起把 detail 存起來）
                        OrderList saved = orderListRepo.saveAndFlush(orderList);

                        log.info("reserve() success: user = {}, order = {}, ticketType = {}, quantity = {}, attempt = {}", user.getEmail(), saved.getId(), tt.getId(), quantity, attemptNo);

                        return saved.getId();
                    });

                } catch (ObjectOptimisticLockingFailureException ex) { // 版本衝突才會進來

                    log.warn("reserve() optimistic lock conflict on ticketType = {}, attempt = {}/{}", ticketTypeId, attempt, maxRetry);

                    if (attempt == maxRetry) {
                        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many concurrent reservations, please retry"
                        ); // 如果已經是最後一次重試，把例外丟出去（在 GlobalExceptionHandler 轉成 409 Conflict）
                    }
                    // 進下一輪重試
                }
            }
            throw new IllegalStateException("Unreachable");
            // 理論上跑不到這裡（因為成功就 return 了；失敗也在最後一次重試 throw）。這是一個保險。
            // throw new UnsupportedOperationException("reserve() not implemented yet");
        } finally {

            if (lockToken != null) {
                try {
                    rLock.unlockSeat(ticketTypeId, lockToken);
                } catch (Exception ex) {
                    log.warn("unlockSeat failed (ignored): ticketTypeId={}, token={}", ticketTypeId, lockToken, ex);
                }
            }
        }


    }

    /**
     * 把訂單從 PENDING_PAYMENT → CONFIRMED：
     * 1) 依 orderListId 找訂單
     * 2) 驗證狀態與付款條件
     * 3) 將狀態改成 CONFIRMED
    **/
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void confirmPayment(Long orderListId) {
//        if (userId == null) throw new IllegalArgumentException("userId is required");
        if (orderListId == null) throw new IllegalArgumentException("orderListId is required");

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated user");
        }
        String email = auth.getName();
        User user = userService.findByEmail(email).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + email));

        log.info("confirmPayment() called, userId = {}, orderListId = {}", user.getEmail(), orderListId);

        // 1/ 讀清單
        OrderList detail = orderListRepo.findById(orderListId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found: " + orderListId));

        // 2/ 確認這張訂單是不是本人下的
        if (detail.getUser() == null || !Objects.equals(detail.getUser().getId(), user.getId())) {
            throw new AccessDeniedException("Forbidden: not the owner of this order");
        }
                                            // Objects（複數）：用的是 java.util.Objects 這個「工具類」，提供很多靜態方法，
                                            // 像 equals(a,b)、requireNonNull(x) 等。
                                            // 不是 java.lang.Object（所有類別的祖先）。

        // 3/ 狀態檢查：只允許從 PENDING_PAYMENT 轉為 CONFIRMED
        if (detail.getStatus() == OrderStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order already confirmed");
        }
        if (detail.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order not payable in status: " + detail.getStatus());
        }

        // 4/ 是 PENDING_PAYMENT 狀態，過期檢查：過期就不能確定付款
        if (detail.isExpired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order expired at " + detail.getExpiresAt());
        }

        // 5/ 到這裡狀態是 PENDING_PAYMENT，需確認真的有一筆付款已確認
        boolean hasConfirmedPayment = paymentRepo.findAllByOrderListId(orderListId)
                .stream().anyMatch(p -> p.getStatus() == PaymentStatus.CONFIRMED);
        if (!hasConfirmedPayment) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No confirmed payment found for this order");
        }

        // 6/ 轉為 CONFIRMED and SAVE
        detail.setStatus(OrderStatus.CONFIRMED);
        OrderList saved = orderListRepo.save(detail);

        log.info("confirmPayment() success orderListId = {}, user = {}, newStatus = {}", saved.getId(), user.getEmail(), saved.getStatus());

//        return saved;

        // throw new UnsupportedOperationException("confirmPayment() not implemented yet");
    }
}
