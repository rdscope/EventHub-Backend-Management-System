package com.github.rdsc.dev.ProSync.repository;

import com.github.rdsc.dev.ProSync.model.OrderDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface OrderDetailRepository extends JpaRepository<OrderDetail, Long> {

    // 查某張訂單的所有明細
    List<OrderDetail> findAllByOrderListId (Long orderListId);

    Optional<OrderDetail> findByOrderListId (Long orderListId);

    // 查「同一訂單 + 指定票種」那一筆（在表上有唯一鍵）
    Optional<OrderDetail> findByOrderListIdAndTicketTypeId (Long orderListId, Long ticketTypeId);

    // 刪某張訂單的所有明細（給維護或重算時用）
    void deleteByOrderListId (Long orderListId);

    @Query("select coalesce(sum(od.cost), 0) from OrderDetail od where od.orderList.id = :orderListId")
    // 把這張訂單的所有明細金額加起來，但如果一筆明細都沒有，sum(...) 會是 NULL，就回 0
    BigDecimal totalCostByOrderListId (@Param("orderListId") Long orderListId);
    // recalcTotal() 是在 Java 記憶體裡，把「已載入的 detail」逐筆相加。
    // coalesce(sum(…)) 是在資料庫裡，請 DB 直接算總和，不用把每一筆載回來。
}
