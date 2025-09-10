package com.github.rdsc.dev.ProSync.enums;

public enum OrderStatus {
    PENDING_PAYMENT,   // 等待付款
    CONFIRMED,         // 已付款
    CANCELLED,         // 取消
    EXPIRED            // 逾時未付
}
