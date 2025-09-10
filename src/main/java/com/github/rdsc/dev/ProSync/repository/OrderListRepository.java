package com.github.rdsc.dev.ProSync.repository;

import com.github.rdsc.dev.ProSync.enums.OrderStatus;
import com.github.rdsc.dev.ProSync.model.OrderList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderListRepository extends JpaRepository<OrderList, Long> {

    // 1/ 依 id + 狀態查（例：只想找「等待付款」的某張訂單）
    Optional<OrderList> findByIdAndStatus (Long id, OrderStatus status);

    // 2/ 查使用者的歷史訂單（新到舊）
    List<OrderList> findAllByUserIdOrderByCreateAtDesc (Long userId);
    // Spring Data JPA 看得懂的語法：
    // Desc = Descending ＝ 由大到小、新到舊
    // Asc = Ascending ＝ 由小到大、舊到新

    // 3/ 依狀態查（例：排程找全部 PENDING_PAYMENT）
    List<OrderList> findAllByStatus (OrderStatus status);

    // 4/ 找「已逾時未付」候選：status + expires_at < now
    List<OrderList> findAllByStatusAndExpiresAtBefore (OrderStatus status, LocalDateTime cutoff);
    // Spring Data JPA 看得懂的語法：
    // Before 是早於某個時間的部分取消，取某個時間之後的部分

    // 5/ 讀單筆時，順便把 detail 一次抓回（避免 Lazy 問題）
    @Query("select distinct o from OrderList o left join fetch o.detail where o.id = :orderListId")
    // distinct：去重
    // left join fetch：就算沒有 detail 也要把訂單帶回，而且這次就把 detail 一起載入，不要之後再查
    Optional<OrderList> findByIdWithDetail(@Param("orderListId") Long id);
}
