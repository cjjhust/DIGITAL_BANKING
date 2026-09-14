package com.jinjing.banking.modules.ledger.service;

import com.jinjing.banking.modules.ledger.entity.LedgerEntry;
import com.jinjing.banking.modules.ledger.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 复式记账服务。
 *
 * <p>记账与余额变更必须落在<b>同一个事务</b>里：否则会出现"余额变了但没有分录"
 * 或"有分录但余额没变"这两种单边记账错误，而对账正是用来发现它们的。
 * 因此这里用默认传播行为（REQUIRED），加入调用方 {@code executeTransfer} 的事务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerService {

    private final LedgerEntryRepository ledgerEntryRepository;

    /**
     * 为一笔转账记两条分录：付款方 DEBIT、收款方 CREDIT。
     *
     * @return 实际写入的分录条数（恒为 2，除非 account 相同）
     */
    @Transactional
    public int recordTransfer(String transactionId, String fromAccountNo, String toAccountNo,
                              BigDecimal amount, String currency) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Ledger amount must be positive, got: " + amount);
        }

        List<LedgerEntry> entries = new ArrayList<>(2);
        entries.add(LedgerEntry.builder()
                .transactionId(transactionId)
                .accountNo(fromAccountNo)
                .direction(LedgerEntry.Direction.DEBIT)
                .amount(amount)
                .currency(currency)
                .build());
        entries.add(LedgerEntry.builder()
                .transactionId(transactionId)
                .accountNo(toAccountNo)
                .direction(LedgerEntry.Direction.CREDIT)
                .amount(amount)
                .currency(currency)
                .build());

        ledgerEntryRepository.saveAll(entries);
        log.debug("Ledger posted: tx={} DEBIT {} {} / CREDIT {} {}", transactionId, amount, fromAccountNo, amount, toAccountNo);
        return entries.size();
    }

    /**
     * 对账：检查每个币种下「借方合计 == 贷方合计」。
     *
     * <p>这是复式记账的核心不变量。任何单边写入（只写了一条分录、金额不一致、
     * 币种不匹配）都会让某个币种不平，从而被这里抓到。
     */
    @Transactional(readOnly = true)
    public ReconciliationReport reconcile() {
        List<Map<String, Object>> currencies = new ArrayList<>();
        boolean balanced = true;

        for (Object[] row : ledgerEntryRepository.sumByCurrency()) {
            String currency = String.valueOf(row[0]);
            BigDecimal debit = (BigDecimal) row[1];
            BigDecimal credit = (BigDecimal) row[2];
            boolean currencyBalanced = debit.compareTo(credit) == 0;
            balanced = balanced && currencyBalanced;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("currency", currency);
            item.put("debitTotal", debit);
            item.put("creditTotal", credit);
            item.put("balanced", currencyBalanced);
            // 差额非 0 就是账不平，需要人工介入
            item.put("difference", debit.subtract(credit));
            currencies.add(item);
        }

        return new ReconciliationReport(balanced, ledgerEntryRepository.count(), currencies);
    }

    public record ReconciliationReport(boolean balanced, long entryCount, List<Map<String, Object>> currencies) {
    }
}
