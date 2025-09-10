package com.github.rdsc.dev.ProSync.repository;

import com.github.rdsc.dev.ProSync.enums.PaymentStatus;
import com.github.rdsc.dev.ProSync.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    // 依鏈上交易哈希找（避免重複認領）
    Optional<Payment> findByTxHash(String txHash);

    // 一張訂單可能有多次報價/重試
    List<Payment> findAllByOrderListId(Long orderListId);

    // 取該訂單最新一筆特定狀態（例：最新 PENDING 報價）
    Optional<Payment> findFirstByOrderListIdAndStatusOrderByCreateAtDesc(Long orderListId, PaymentStatus status);

    // 給排程/清理用：找已過期的 PENDING
    List<Payment> findAllByStatusAndExpiresAtBefore(PaymentStatus status, LocalDateTime before);
}
