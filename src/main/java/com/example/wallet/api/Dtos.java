package com.example.wallet.api;

import com.example.wallet.domain.EntryDirection;
import com.example.wallet.domain.LedgerEntry;
import com.example.wallet.domain.TransactionType;
import com.example.wallet.domain.WalletTransaction;
import com.example.wallet.service.WalletService.TransactionResult;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** Request/response shapes. All money amounts are in paise (1 INR = 100 paise). */
public final class Dtos {

    /** Per-request cap: 1,00,00,000 paise = INR 10 lakh. */
    public static final long MAX_AMOUNT_PAISE = 100_000_000L;

    private Dtos() {
    }

    public record RegisterRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 72) String password) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record RegisterResponse(UUID userId, String email, UUID accountId) {
    }

    public record TokenResponse(String token) {
    }

    public record TopUpRequest(
            @Min(1) @Max(MAX_AMOUNT_PAISE) long amount,
            @Size(max = 255) String note) {
    }

    public record TransferRequest(
            @NotBlank @Email String receiverEmail,
            @Min(1) @Max(MAX_AMOUNT_PAISE) long amount,
            @Size(max = 255) String note) {
    }

    public record WalletResponse(UUID accountId, long balance, String currency) {
    }

    public record TransactionResponse(UUID transactionId, TransactionType type, long amount,
                                      UUID sourceAccountId, UUID destinationAccountId,
                                      Instant createdAt, boolean replayed) {
        public static TransactionResponse from(TransactionResult result) {
            WalletTransaction t = result.transaction();
            return new TransactionResponse(t.getId(), t.getType(), t.getAmount(),
                    t.getSourceAccountId(), t.getDestinationAccountId(), t.getCreatedAt(),
                    result.replayed());
        }
    }

    public record LedgerEntryResponse(UUID entryId, UUID transactionId, EntryDirection direction,
                                      long amount, long balanceAfter, Instant createdAt) {
        public static LedgerEntryResponse from(LedgerEntry e) {
            return new LedgerEntryResponse(e.getId(), e.getTransactionId(), e.getDirection(),
                    e.getAmount(), e.getBalanceAfter(), e.getCreatedAt());
        }
    }

    public record ErrorResponse(String code, String message, Instant timestamp) {
    }
}
