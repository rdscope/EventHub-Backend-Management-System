package com.github.rdsc.dev.ProSync.enums;

/**
 * 使用者狀態（帳號生命週期）
 * - ACTIVE：可登入、可使用所有被授權的功能。
 * - PENDING_VERIFICATION：已註冊但尚未完成驗證（e.g. Email/手機）；通常限制登入或只給最小權限。
 * - SUSPENDED：被管理員暫停（可復原）；不可登入或不可進行關鍵操作。
 * - DISABLED：永久停用/封存；不可登入，通常只保留歷史資料。
**/
public enum UserStatus {
    ACTIVE,
    PENDING_VERIFICATION,
    SUSPENDED,
    DISABLED
}
