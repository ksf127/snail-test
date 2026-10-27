package com.company.service.mall.service;

import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;
import com.company.service.mall.domain.Product;
import com.company.service.mall.domain.Stock;
import com.company.service.mall.infra.ProductRepository;
import com.company.service.mall.infra.StockRepository;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 商品与库存的建档。
 *
 * <p>商品与库存必须在同一事务里创建：只建商品不建库存，下单时会走到
 * "商品存在但库存记录缺失"的分支，这是很多线上事故的起点。
 * 内存实现里用同一个方法顺序写入来模拟这一点，换成数据库时请务必加 {@code @Transactional}。
 */
@Service
public class ProductService {

    private final AtomicLong sequence = new AtomicLong();

    private final ProductRepository products;

    private final StockRepository stocks;

    public ProductService(ProductRepository products, StockRepository stocks) {
        this.products = products;
        this.stocks = stocks;
    }

    public Product create(String name, long priceCents, int stock) {
        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "商品名不能为空");
        }
        if (priceCents < 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "价格不能为负: " + priceCents);
        }
        if (stock <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "库存必须为正数: " + stock);
        }
        long productId = sequence.incrementAndGet();
        Product product = new Product(productId, name.trim(), priceCents, Instant.now());
        products.save(product);
        stocks.save(new Stock(productId, stock));
        return product;
    }

    public Product get(long productId) {
        return products.findById(productId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "商品不存在: " + productId));
    }

    public List<Product> list() {
        return products.findAll();
    }

    /**
     * 库存快照：给接口层展示用，避免把可变的 {@link Stock} 直接暴露出去。
     */
    public StockSnapshot stockOf(long productId) {
        Stock stock = stocks.findById(productId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "库存记录不存在: " + productId));
        return new StockSnapshot(stock.total(), stock.available(), stock.reserved());
    }

    public record StockSnapshot(int total, int available, int reserved) {
    }
}
