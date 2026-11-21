package com.example.wallet.api;

import com.example.wallet.api.Dtos.LedgerEntryResponse;
import com.example.wallet.api.Dtos.TopUpRequest;
import com.example.wallet.api.Dtos.TransactionResponse;
import com.example.wallet.api.Dtos.TransferRequest;
import com.example.wallet.api.Dtos.WalletResponse;
import com.example.wallet.domain.Account;
import com.example.wallet.service.WalletService;
import com.example.wallet.service.WalletService.TransactionResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @GetMapping
    public WalletResponse wallet(Authentication auth) {
        Account account = walletService.getWallet(userId(auth));
        return new WalletResponse(account.getId(), account.getBalance(), account.getCurrency());
    }

    @PostMapping("/topup")
    public ResponseEntity<TransactionResponse> topUp(
            Authentication auth,
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @Valid @RequestBody TopUpRequest request) {
        TransactionResult result = walletService.topUp(userId(auth), idempotencyKey,
                request.amount(), request.note());
        return respond(result);
    }

    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> transfer(
            Authentication auth,
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        TransactionResult result = walletService.transfer(userId(auth), idempotencyKey,
                request.receiverEmail(), request.amount(), request.note());
        return respond(result);
    }

    @GetMapping("/transactions")
    public List<LedgerEntryResponse> history(
            Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return walletService.history(userId(auth), page, size).stream()
                .map(LedgerEntryResponse::from)
                .toList();
    }

    /** 201 for a newly applied transaction, 200 (+ header) when an earlier result is replayed. */
    private ResponseEntity<TransactionResponse> respond(TransactionResult result) {
        TransactionResponse body = TransactionResponse.from(result);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(body);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    private static UUID userId(Authentication auth) {
        return UUID.fromString(auth.getName());
    }
}
