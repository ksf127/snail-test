package com.company.service.mall.infra;

import com.company.service.mall.domain.TccTransaction;

import java.util.Optional;

public interface TccTransactionRepository {

    void save(TccTransaction transaction);

    Optional<TccTransaction> findByTxId(String txId);

    long countByStatus(TccTransaction.Status status);

    long count();
}
