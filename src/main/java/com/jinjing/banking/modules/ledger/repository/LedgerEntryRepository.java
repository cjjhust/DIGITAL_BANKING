package com.jinjing.banking.modules.ledger.repository;

import com.jinjing.banking.modules.ledger.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByTransactionId(String transactionId);

    /**
     * 全账借贷合计：返回 [currency, 借方合计, 贷方合计]。
     * 复式记账的不变量是每个币种下借方合计 == 贷方合计。
     */
    @Query(value = """
            SELECT currency,
                   COALESCE(SUM(CASE WHEN direction = 'DEBIT'  THEN amount ELSE 0 END), 0) AS debit_total,
                   COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE 0 END), 0) AS credit_total
            FROM ledger_entries
            GROUP BY currency
            ORDER BY currency
            """, nativeQuery = true)
    List<Object[]> sumByCurrency();

    /** 某个账户的净变动 = 贷方合计 - 借方合计。 */
    @Query(value = """
            SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END), 0)
            FROM ledger_entries
            WHERE account_no = :accountNo
            """, nativeQuery = true)
    BigDecimal netMovementOf(@Param("accountNo") String accountNo);

    long countByTransactionId(String transactionId);
}
