package com.company.service.mall.infra;

import com.company.service.mall.domain.Stock;

import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class InMemoryStockRepository implements StockRepository {

    private final ConcurrentMap<Long, Stock> stocks = new ConcurrentHashMap<>();

    @Override
    public void save(Stock stock) {
        stocks.put(stock.productId(), stock);
    }

    @Override
    public Optional<Stock> findById(long productId) {
        return Optional.ofNullable(stocks.get(productId));
    }

    @Override
    public long count() {
        return stocks.size();
    }
}
