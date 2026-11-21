package com.example.wallet.repo;

import com.example.wallet.domain.EntryDirection;
import com.example.wallet.domain.LedgerEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable);

    /** Rows of [accountId, direction, sum(amount)] used by reconciliation. */
    @Query("select e.accountId, e.direction, sum(e.amount) from LedgerEntry e "
            + "group by e.accountId, e.direction")
    List<Object[]> totalsByAccountAndDirection();

    /** Ids of transactions whose debits and credits do not match. Must always be empty. */
    @Query("select e.transactionId from LedgerEntry e group by e.transactionId "
            + "having sum(case when e.direction = :debit then e.amount else 0L end) "
            + "<> sum(case when e.direction = :credit then e.amount else 0L end)")
    List<UUID> findUnbalancedTransactionIds(@Param("debit") EntryDirection debit,
                                            @Param("credit") EntryDirection credit);
}
