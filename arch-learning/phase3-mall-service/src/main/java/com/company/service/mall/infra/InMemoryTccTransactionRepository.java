package com.company.service.mall.infra;

import com.company.service.mall.domain.TccTransaction;

import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class InMemoryTccTransactionRepository implements TccTransactionRepository {

    private final ConcurrentMap<String, TccTransaction> transactions = new ConcurrentHashMap<>();

    @Override
    public void save(TccTransaction transaction) {
        transactions.put(transaction.txId(), transaction);
    }

    @Override
    public Optional<TccTransaction> findByTxId(String txId) {
        return Optional.ofNullable(transactions.get(txId));
    }

    @Override
    public long countByStatus(TccTransaction.Status status) {
        return transactions.values().stream().filter(tx -> tx.status() == status).count();
    }

    @Override
    public long count() {
        return transactions.size();
    }
}
