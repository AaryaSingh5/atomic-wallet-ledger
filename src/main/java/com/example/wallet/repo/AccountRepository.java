package com.example.wallet.repo;

import com.example.wallet.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByUserId(UUID userId);

    /**
     * Returns only the id, NOT the entity. This matters: if an Account were loaded without a lock
     * first, the later FOR UPDATE query would hand back that already-managed (stale) instance
     * and we would silently lose updates.
     */
    @Query("select a.id from Account a where a.userId = :userId")
    Optional<UUID> findIdByUserId(@Param("userId") UUID userId);

    /** SELECT ... FOR UPDATE. Blocks until any other transaction holding the row commits. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);
}
