package com.company.service.mall.infra;

import com.company.service.mall.domain.Product;

import java.util.List;
import java.util.Optional;

public interface ProductRepository {

    void save(Product product);

    Optional<Product> findById(long productId);

    List<Product> findAll();

    long count();
}
