package com.github.rdsc.dev.ProSync.enums;

public enum PaymentStatus {
    PENDING,     // 已建立報價，等待用戶付款或確認
    CONFIRMED,   // 已確認（有 txHash），會觸發訂單確認
    EXPIRED,     // 報價/付款逾期
    FAILED       // 失敗（驗證失敗、金額不符等）
}