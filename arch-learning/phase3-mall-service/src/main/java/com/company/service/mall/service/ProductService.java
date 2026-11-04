package com.company.service.mall.service;

import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;
import com.company.service.mall.domain.Product;
import com.company.service.mall.domain.Stock;
import com.company.service.mall.infra.ProductRepository;
import com.company.service.mall.infra.StockRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 商品与库存的建档。
 *
 * <p>【解决什么问题】下单必须先有"商品 + 库存"这对数据。只建商品不建库存，
 * 下单时会走到"商品存在但库存记录缺失"的分支，这是很多线上事故的起点；
 * 反过来先建库存再建商品，也会留下孤儿库存。
 *
 * <p>【为什么这样设计】商品与库存必须一起成功或一起失败，所以放在同一个方法里顺序写入，
 * 真实落库时这个方法必须是 {@code @Transactional}。这里用内存实现演示，
 * 无法跨进程回滚，只能靠"先商品后库存、异常即中断"把窗口压到最小。
 *
 * <p>【怎么读这段代码】{@link #create} 校验参数 → 生成 ID → 写商品 → 写库存；
 * 查询类方法只读，直接委托仓储。库存通过 {@link #stockOf} 转成不可变快照对外暴露，
 * 避免调用方持有可变对象后绕过 TCC 直接改库存。
 */
@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final AtomicLong sequence = new AtomicLong();

    private final ProductRepository products;

    private final StockRepository stocks;

    public ProductService(ProductRepository products, StockRepository stocks) {
        this.products = products;
        this.stocks = stocks;
    }

    /**
     * 建档商品并初始化库存。
     *
     * <p>幂等性：不幂等。每次调用都会生成新的 productId，重复调用会产生两件商品。
     *
     * @param name       商品名，不能为空
     * @param priceCents 售价（分），不能为负
     * @param stock      初始库存，必须为正
     * @return 新建商品
     * @throws BizException 参数非法
     */
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
        // 紧接着建库存：真实实现里这两步必须同事务，否则会留下"有商品无库存"的脏数据
        stocks.save(new Stock(productId, stock));
        log.info("【商品】建档完成: productId={}, name={}, price={}, stock={}",
                productId, product.name(), priceCents, stock);
        return product;
    }

    /**
     * 按 ID 查商品。
     *
     * @throws BizException 商品不存在（NOT_FOUND）
     */
    public Product get(long productId) {
        return products.findById(productId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "商品不存在: " + productId));
    }

    /**
     * 商品列表。幂等：只读。
     */
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
