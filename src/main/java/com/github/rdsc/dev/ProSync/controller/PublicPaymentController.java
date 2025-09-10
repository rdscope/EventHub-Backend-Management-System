package com.github.rdsc.dev.ProSync.controller;

import com.github.rdsc.dev.ProSync.crypto.PriceFeed;
import com.github.rdsc.dev.ProSync.dto.PaymentDto;
import com.github.rdsc.dev.ProSync.model.Payment;
import com.github.rdsc.dev.ProSync.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController // 做 REST API 的控制器
@RequestMapping("/api/public/payment")
@RequiredArgsConstructor // 自動幫這個 class 產生一個帶有 final 欄位的建構子（eg 下面的 EntityManager em）
@Validated // 開啟 Spring 的參數驗證支持
@Slf4j
public class PublicPaymentController {

    private final PaymentService paymentService;
    private final PriceFeed priceFeed;

    /**
     * 建立加密付款報價（回一筆 PENDING Payment）
     * 需 USER 角色
    **/
    @PostMapping("/create-quote")
    @PreAuthorize("hasRole('USER')") // 方法級授權
    public ResponseEntity<PaymentDto.CryptoQuoteResponse> createQuote(@RequestBody @Valid PaymentDto.CryptoQuoteRequest req) {
        Payment p = paymentService.createCryptoQuote(req.getOrderListId(), req.getAsset());

        var body = new PaymentDto.CryptoQuoteResponse(
                p.getId(),
                p.getOrderList().getId(),
                p.getAsset(),
                p.getQuoteRate(),
                p.getAmountCrypto(),
                p.getStatus(),
                p.getExpiresAt()
        );

        return ResponseEntity.status(201).body(body);
//        return ResponseEntity.ok(PaymentDto.PaymentInfo.toDto(p));
    }

    /**
     * 確認鏈上交易（把 payment 標成 CONFIRMED 並觸發訂單確認）
     * 需 USER 角色
    **/
    @PostMapping("/confirm-payment/{id}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<PaymentDto.ConfirmPaymentResponse> confirmTx(@PathVariable("id") Long paymentId,
                                                          @RequestBody @Valid PaymentDto.ConfirmPaymentRequest req) {

        if (req == null || req.getTxHash() == null || req.getTxHash().isBlank()) {
            throw new IllegalArgumentException("txHash is required");
        }

        try {
            // 2/ 交給 Service，讓它決定 409/429 等業務錯
            Payment p = paymentService.confirmCryptoTx(paymentId, req.getTxHash());

            var body = new PaymentDto.ConfirmPaymentResponse(
                    p.getId(),
                    p.getOrderList().getId(),
                    p.getStatus()
            );

            return ResponseEntity.ok(body);

        } catch (ResponseStatusException rse) {
            // Service 已經決定好狀態碼與訊息，直接丟回
            throw rse;

        } catch (IllegalStateException | IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    // 查單一資產的即時報價與基礎幣別（TWD）
    @PreAuthorize("hasRole('USER')")
    @GetMapping("/quote/{asset}")
    public ResponseEntity<Map<String, Object>> previewQuote(@PathVariable("asset") String asset) {
        if (asset == null || asset.isBlank()) {
            throw new IllegalArgumentException("asset is required");
        }
        var rate = priceFeed.getQuote(asset);            // 可能來自 ExternalProvider，也可能退回 FakePrice
        var base = priceFeed.getBaseCurrency();          // 一律 TWD（設計固定）
        log.info("Preview quote: {} = {} {}", asset, rate, base);
        return ResponseEntity.ok(Map.of(
                "asset", asset.trim().toUpperCase(),
                "quoteRate", rate,
                "base", base
        ));
    }


    // === 本地測試用：產生一個假的 txHash（32 bytes 隨機，十六進位，前綴 0x） ===
    @GetMapping("/generate-txhash")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<String> generateTxHash() {
        return ResponseEntity.ok(PaymentDto.TxHash.fakeTxHash());
    }

}
