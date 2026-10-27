package com.company.service.archlab.scenario.stock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import com.company.starter.lock.DistributedLock;
import com.company.starter.lock.LockHandle;

import org.springframework.stereotype.Service;

/**
 * 库存超卖实验：同一件商品 100 件库存，200 个买家并发各买 1 件，比较五种扣减方式。
 *
 * <p>关键指标不是"卖得快不快"，而是下面这条恒等式：
 * {@code 剩余库存 + 已售数量 == 初始库存}，一旦不成立就说明有更新被覆盖或重复扣减。
 *
 * <p>两种"出问题"的模式用<b>受控并发</b>复现（读完之后一起放行，再各自写回），
 * 因为靠线程抢跑复现竞态并不稳定；三种"正确"的模式则用真实并发跑，
 * 验证的是"无论怎么调度，恒等式都成立"。
 *
 * <p>特别注意 {@link Mode#LOCAL_LOCK}：单机 {@code synchronized} 在集群里等于没有锁。
 * 两台机器各自加自己的锁，照样能把 1 件库存卖成 2 件——这就是必须上分布式锁的原因。
 */
@Service
public class StockLab {

    private static final String SKU = "SKU-1";

    public enum Mode {

        /** 读-改-写没有任何保护 */
        NONE("无锁"),

        /** JVM 内本地锁：单机有效，多实例无效 */
        LOCAL_LOCK("本地锁（单机）"),

        /** 版本号 CAS：失败重试，适合冲突不高的场景 */
        OPTIMISTIC("乐观锁（版本号 CAS）"),

        /** 数据库 select ... for update：并发高时会排队 */
        ROW_LOCK("数据库行锁"),

        /** Redis 分布式锁：跨进程互斥，代价是一次网络往返 */
        REDIS_LOCK("分布式锁（company-lock-starter）");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Result(String mode,
                         String label,
                         int instances,
                         int buyers,
                         int initialStock,
                         int sold,
                         int remaining,
                         int oversold,
                         long conflicts,
                         boolean consistent,
                         long elapsedMillis) {
    }

    private final DistributedLock distributedLock;

    public StockLab(DistributedLock distributedLock) {
        this.distributedLock = distributedLock;
    }

    /**
     * @param instances 模拟的实例数：本地锁模式下每个实例各有一把锁
     */
    public Result run(Mode mode, int initialStock, int buyers, int instances) {
        if (mode == null) {
            throw new IllegalArgumentException("mode 不能为空");
        }
        if (initialStock < 1 || initialStock > 10_000) {
            throw new IllegalArgumentException("stock 必须在 [1, 10000] 之间");
        }
        if (buyers < 1 || buyers > 200) {
            throw new IllegalArgumentException("buyers 必须在 [1, 200] 之间");
        }
        if (instances < 1 || instances > 8) {
            throw new IllegalArgumentException("instances 必须在 [1, 8] 之间");
        }

        Inventory inventory = new Inventory(initialStock, instances);
        long start = System.nanoTime();

        if (mode == Mode.NONE) {
            runControlledRace(inventory, buyers, false, buyers);
        } else if (mode == Mode.LOCAL_LOCK) {
            runControlledRace(inventory, buyers, true, instances);
        } else {
            runRealConcurrency(mode, inventory, buyers);
        }

        int sold = mode == Mode.OPTIMISTIC ? inventory.optimistic.get().sold() : inventory.sold;
        int remaining = mode == Mode.OPTIMISTIC ? inventory.optimistic.get().stock() : inventory.stock;
        return new Result(mode.name(),
                mode.label(),
                instances,
                buyers,
                initialStock,
                sold,
                remaining,
                Math.max(0, sold - initialStock),
                inventory.conflicts.get(),
                remaining >= 0 && remaining + sold == initialStock,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }

    /**
     * 受控竞态：每个线程先读库存，等所有线程都读完再一起写回。
     * 这不是"模拟"，而是把真实竞态里最坏的交错固定下来，保证每次都能看到问题。
     */
    private void runControlledRace(Inventory inventory, int buyers, boolean useInstanceLock, int instances) {
        int threadCount = useInstanceLock ? instances : buyers;
        int perThread = useInstanceLock ? Math.max(1, buyers / instances) : 1;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>(threadCount);
        try {
            for (int i = 0; i < threadCount; i++) {
                int instance = i;
                futures.add(pool.submit(() -> {
                    ReentrantLock lock = useInstanceLock ? inventory.instanceLocks[instance] : null;
                    for (int round = 0; round < perThread; round++) {
                        if (lock != null) {
                            lock.lock();
                        }
                        try {
                            int snapshot = inventory.stock;
                            if (round == 0) {
                                await(barrier);
                            }
                            inventory.stock = snapshot - 1;
                            inventory.sold++;
                        } finally {
                            if (lock != null) {
                                lock.unlock();
                            }
                        }
                    }
                }));
            }
            awaitAll(futures);
        } finally {
            pool.shutdownNow();
        }
    }

    private void runRealConcurrency(Mode mode, Inventory inventory, int buyers) {
        ExecutorService pool = Executors.newFixedThreadPool(buyers);
        List<Future<?>> futures = new ArrayList<>(buyers);
        try {
            for (int i = 0; i < buyers; i++) {
                futures.add(pool.submit(() -> buyOnce(mode, inventory)));
            }
            awaitAll(futures);
        } finally {
            pool.shutdownNow();
        }
    }

    private void buyOnce(Mode mode, Inventory inventory) {
        switch (mode) {
            case OPTIMISTIC -> {
                while (true) {
                    Inventory.Snapshot current = inventory.optimistic.get();
                    if (current.stock() <= 0) {
                        return;
                    }
                    Inventory.Snapshot next = new Inventory.Snapshot(current.stock() - 1,
                            current.sold() + 1,
                            current.version() + 1);
                    if (inventory.optimistic.compareAndSet(current, next)) {
                        return;
                    }
                    inventory.conflicts.incrementAndGet();
                    Thread.onSpinWait();
                }
            }
            case ROW_LOCK -> {
                inventory.rowLock.lock();
                try {
                    deduct(inventory);
                } finally {
                    inventory.rowLock.unlock();
                }
            }
            case REDIS_LOCK -> {
                // 用带等待的 lock()：抢不到锁时必须排队，而不是直接失败——
                // 秒杀场景下"没抢到锁就失败"等于把正常用户也拒了
                try (LockHandle handle = distributedLock.lock("stock:" + SKU,
                        Duration.ofSeconds(5), Duration.ofSeconds(5))) {
                    if (handle == null) {
                        throw new IllegalStateException("获取库存锁超时");
                    }
                    deduct(inventory);
                }
            }
            default -> throw new IllegalStateException("不支持的模式: " + mode);
        }
    }

    private void deduct(Inventory inventory) {
        if (inventory.stock <= 0) {
            return;
        }
        inventory.stock--;
        inventory.sold++;
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("受控竞态同步失败", e);
        }
    }

    private static void awaitAll(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("库存实验被中断", e);
            } catch (Exception e) {
                throw new IllegalStateException("库存实验执行失败", e);
            }
        }
    }

    private static final class Inventory {

        private int stock;

        private int sold;

        private final ReentrantLock rowLock = new ReentrantLock();

        private final ReentrantLock[] instanceLocks;

        private final AtomicReference<Snapshot> optimistic;

        private final AtomicLong conflicts = new AtomicLong();

        private Inventory(int stock, int instances) {
            this.stock = stock;
            this.optimistic = new AtomicReference<>(new Snapshot(stock, 0, 0));
            this.instanceLocks = new ReentrantLock[instances];
            for (int i = 0; i < instances; i++) {
                instanceLocks[i] = new ReentrantLock();
            }
        }

        private record Snapshot(int stock, int sold, long version) {
        }
    }
}
