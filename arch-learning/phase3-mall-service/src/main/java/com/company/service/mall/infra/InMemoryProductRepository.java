package com.company.service.mall.infra;

import com.company.service.mall.domain.Product;

import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class InMemoryProductRepository implements ProductRepository {

    private final ConcurrentMap<Long, Product> products = new ConcurrentHashMap<>();

    @Override
    public void save(Product product) {
        products.put(product.productId(), product);
    }

    @Override
    public Optional<Product> findById(long productId) {
        return Optional.ofNullable(products.get(productId));
    }

    @Override
    public List<Product> findAll() {
        return products.values().stream()
                .sorted(Comparator.comparingLong(Product::productId))
                .toList();
    }

    @Override
    public long count() {
        return products.size();
    }
}
