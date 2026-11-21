package com.example.wallet.service;

import com.example.wallet.domain.Account;
import com.example.wallet.domain.EntryDirection;
import com.example.wallet.repo.AccountRepository;
import com.example.wallet.repo.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Proves the books are consistent. Checks three invariants:
 *  1. For every transaction, total debits == total credits.
 *  2. For every account, cached balance == sum(credits) - sum(debits) from the ledger.
 *  3. The sum of ALL account balances is 0 (money is only ever moved, never created or lost).
 *
 * Runs at REPEATABLE_READ so the whole report is computed from one consistent snapshot even
 * while transfers are happening.
 */
@Service
public class ReconciliationService {

    public record ReconciliationReport(boolean balanced, long totalDebits, long totalCredits,
                                       long sumOfAllBalances, List<String> accountMismatches,
                                       List<UUID> unbalancedTransactions) {
    }

    private final AccountRepository accounts;
    private final LedgerEntryRepository entries;

    public ReconciliationService(AccountRepository accounts, LedgerEntryRepository entries) {
        this.accounts = accounts;
        this.entries = entries;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReport run() {
        Map<UUID, Long> ledgerNet = new HashMap<>();
        long totalDebits = 0;
        long totalCredits = 0;

        for (Object[] row : entries.totalsByAccountAndDirection()) {
            UUID accountId = (UUID) row[0];
            EntryDirection direction = (EntryDirection) row[1];
            long sum = ((Number) row[2]).longValue();
            if (direction == EntryDirection.CREDIT) {
                totalCredits += sum;
                ledgerNet.merge(accountId, sum, Long::sum);
            } else {
                totalDebits += sum;
                ledgerNet.merge(accountId, -sum, Long::sum);
            }
        }

        long sumOfBalances = 0;
        List<String> mismatches = new ArrayList<>();
        for (Account account : accounts.findAll()) {
            sumOfBalances += account.getBalance();
            long expected = ledgerNet.getOrDefault(account.getId(), 0L);
            if (expected != account.getBalance()) {
                mismatches.add("account " + account.getId() + ": balance=" + account.getBalance()
                        + " but ledger says " + expected);
            }
        }

        List<UUID> unbalanced = entries.findUnbalancedTransactionIds(EntryDirection.DEBIT, EntryDirection.CREDIT);

        boolean balanced = totalDebits == totalCredits
                && sumOfBalances == 0
                && mismatches.isEmpty()
                && unbalanced.isEmpty();
        return new ReconciliationReport(balanced, totalDebits, totalCredits, sumOfBalances,
                mismatches, unbalanced);
    }
}
