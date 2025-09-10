package com.github.rdsc.dev.ProSync.repository;

import com.github.rdsc.dev.ProSync.model.TicketType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TicketTypeRepository extends JpaRepository<TicketType, Long> {

    // 依活動查票種
    List<TicketType> findAllByEventId(Long eventId);

    // 為了不超賣、不被「最後一個寫入」覆蓋掉前面的人，所以要用鎖
    @Lock(LockModeType.OPTIMISTIC)
    // 帶樂觀鎖讀取：在同一交易內，根據 @Version 檢查版本
    // 每筆資料都有一個 version。讀資料時，拿到當下的版本號。
    // 提交更新時，JPA 會檢查：資料庫裡的 version 是否還跟自己讀到的一樣。
    //      一樣 → 成功更新，version+1。
    //      不一樣（有人先改過）→ 直接丟「版本衝突」錯（會回 409），這單不成立、不會超賣。
    // 好處：不會卡住別人，只有真的撞到才付出重試/失敗的代價，吞吐量高。
    // 後續再加一個 短暫的 Redis 鎖（5 秒 SETNX）在應用層「減少撞車次數」。這樣既正確又能扛併發。
    @Query("select t from TicketType t where t.id = :ticketTypeId") // JPQL（查「實體」不是查表）
    // 命名參數前的冒號 : 和參數名之間不能有空白
    Optional<TicketType> findByIdWithOptimisticLock(@Param("ticketTypeId") Long id);
    // 命名參數 與 參數變數 綁定 進行 票種資料查找

    // （可選，先註解）需要時可啟用悲觀鎖來「硬性」阻擋並發寫入
    // 悲觀鎖會把那一列「鎖住、讓別人排隊等」：在搶票這種熱門同一筆的情境，大家都卡在那一列上，吞吐量掉很快，還容易遇到鎖等待逾時或死鎖。
    // @Lock(LockModeType.PESSIMISTIC_WRITE)
    // @Query("select t from TicketType t where t.id = :id")
    // Optional<TicketType> findByIdForUpdate(@Param("id") Long id);

//    Page<TicketType> findAllByEvent_OrganizerId(Long organizerId, Pageable pageable);

    @Query("select distinct t from TicketType t join t.event e left join e.coOrganizers co where e.organizer.id = :userId or co.id = :userId")
    Page<TicketType> findAllOwnedByUser(@Param("userId") Long userId, Pageable pageable);
}
