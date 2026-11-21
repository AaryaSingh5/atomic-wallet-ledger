package com.example.wallet.repo;

import com.example.wallet.domain.WalletTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, UUID> {
    Optional<WalletTransaction> findByInitiatorUserIdAndIdempotencyKey(UUID initiatorUserId, String idempotencyKey);
}
