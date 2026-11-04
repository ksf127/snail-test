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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.company.starter.lock.DistributedLock;
import com.company.starter.lock.LockHandle;

/**
 * 库存超卖实验：同一件商品 100 件库存，200 个买家并发各买 1 件，比较五种扣减方式。
 *
 * <p>【解决什么问题】扣库存的本质是"判断还有没有 + 减一"，这两个动作不是原子的。
 * 两个线程同时读到库存 1，都判断"还有"，然后都减成 0，于是 1 件商品卖给了 2 个人。
 * 单机还能用 {@code synchronized}，但服务一旦部署多实例，每个 JVM 各有一把锁，
 * 本地锁就完全失效了——这正是超卖事故最常见的来源。
 *
 * <p>【为什么这样设计】五种模式覆盖了从"没有防护"到"跨进程互斥"的完整谱系：
 * <ul>
 *   <li>{@code NONE}/{@code LOCAL_LOCK}：用<b>受控并发</b>复现（所有线程读完再一起写回），
 *       因为靠线程抢跑复现竞态并不稳定，跑十次可能只超卖一次；</li>
 *   <li>{@code OPTIMISTIC}：版本号 CAS，冲突时重试，适合冲突不高的场景；</li>
 *   <li>{@code ROW_LOCK}：模拟数据库 {@code select ... for update}，用可重入锁代替；</li>
 *   <li>{@code REDIS_LOCK}：真正的跨进程互斥，代价是每次扣减两次网络往返。</li>
 * </ul>
 * 三种"正确"的模式则用真实并发跑，验证的是"无论怎么调度，恒等式都成立"。
 *
 * <p>【怎么读这段代码】先看 {@link Inventory}：普通字段 {@code stock/sold} 是被竞态修改的对象，
 * {@code instanceLocks} 模拟多实例各自的本地锁，{@code optimistic} 是带版本号的 CAS 状态；
 * 再看 {@link #run} 如何按模式分派到"受控竞态"或"真实并发"；
 * 最后看 {@link #buyOnce} 的五种扣减写法和返回结果里的 {@code consistent}。
 * 关键指标不是"卖得快不快"，而是下面这条恒等式：
 * {@code 剩余库存 + 已售数量 == 初始库存}，一旦不成立就说明有更新被覆盖或重复扣减。
 *
 * <p>特别注意 {@link Mode#LOCAL_LOCK}：单机 {@code synchronized} 在集群里等于没有锁。
 * 两台机器各自加自己的锁，照样能把 1 件库存卖成 2 件——这就是必须上分布式锁的原因。
 */
@Service
public class StockLab {

    private static final Logger log = LoggerFactory.getLogger(StockLab.class);

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

    /**
     * 库存实验结果。
     *
     * @param sold       已售数量
     * @param remaining  剩余库存；负数说明超卖
     * @param oversold   超卖数量，0 表示没超卖
     * @param conflicts  乐观锁重试次数
     * @param consistent {@code remaining + sold == initialStock} 是否成立
     */
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
     * 运行一次库存扣减实验。
     *
     * @param mode         扣减方式，不能为空
     * @param initialStock 初始库存，范围 [1, 10000]
     * @param buyers       并发买家数，范围 [1, 200]
     * @param instances    模拟的实例数：本地锁模式下每个实例各有一把锁
     * @return 已售、剩余、超卖数与一致性判断
     * @throws IllegalArgumentException 参数越界或 mode 为空时抛出
     * @throws IllegalStateException    并发执行失败或获取锁超时时抛出
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

        log.info("【库存超卖实验】开始，mode={}（{}），初始库存={}，买家数={}，实例数={}",
                mode, mode.label(), initialStock, buyers, instances);
        Inventory inventory = new Inventory(initialStock, instances);
        long start = System.nanoTime();

        if (mode == Mode.NONE) {
            // 无锁组：让所有买家先读到同一个库存快照，再一起写回，最坏交错必然丢更新。
            runControlledRace(inventory, buyers, false, buyers);
        } else if (mode == Mode.LOCAL_LOCK) {
            // 本地锁组：每个"实例"一个线程、各自持有自己的锁，模拟集群里锁不共享。
            runControlledRace(inventory, buyers, true, instances);
        } else {
            runRealConcurrency(mode, inventory, buyers);
        }

        // 乐观锁模式的状态在 AtomicReference 里，其余模式直接读普通字段。
        int sold = mode == Mode.OPTIMISTIC ? inventory.optimistic.get().sold() : inventory.sold;
        int remaining = mode == Mode.OPTIMISTIC ? inventory.optimistic.get().stock() : inventory.stock;
        Result result = new Result(mode.name(),
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
        log.info("【库存超卖实验】结束，mode={}，已售={}，剩余={}，超卖={}，乐观锁冲突={}，" +
                        "恒等式成立={}，耗时={}ms",
                mode, result.sold(), result.remaining(), result.oversold(), result.conflicts(),
                result.consistent(), result.elapsedMillis());
        if (!result.consistent() || result.oversold() > 0) {
            log.warn("【库存超卖实验】{} 模式出现超卖/丢更新：库存+已售 != 初始库存", mode);
        }
        return result;
    }

    /**
     * 受控竞态：每个线程先读库存，等所有线程都读完再一起写回。
     * 这不是"模拟"，而是把真实竞态里最坏的交错固定下来，保证每次都能看到问题。
     */
    private void runControlledRace(Inventory inventory, int buyers, boolean useInstanceLock, int instances) {
        // 本地锁模式下线程数等于实例数，每个线程用自己的锁处理一部分买家；
        // 无锁模式下每个买家一个线程。
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
                                // 只在第一轮同步：确保所有线程都拿到了旧快照，再一起写回，
                                // 这样"读后写覆盖"一定会发生，而不是靠运气。
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

    /** 真实并发：每个买家一个线程，不对调度做任何控制，验证正确模式在任意交错下都成立。 */
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

    /** 单个买家尝试购买一次；不同模式对应不同的互斥/重试策略。 */
    private void buyOnce(Mode mode, Inventory inventory) {
        switch (mode) {
            case OPTIMISTIC -> {
                // CAS 失败说明别人先改了库存，重新读最新快照再试，直到成功或卖完。
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
                    // 让出 CPU，避免高冲突时所有线程空转把机器打满。
                    Thread.onSpinWait();
                }
            }
            case ROW_LOCK -> {
                // 模拟数据库 select ... for update：同一行在事务提交前不能被其他事务修改。
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
                        log.warn("【库存超卖实验】5s 内没有拿到库存锁 sku={}", SKU);
                        throw new IllegalStateException("获取库存锁超时");
                    }
                    deduct(inventory);
                }
            }
            default -> throw new IllegalStateException("不支持的模式: " + mode);
        }
    }

    /** 库存判断与扣减必须作为一个整体执行，否则"判断还有"和"减一"之间会被别的线程插入。 */
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

        /** 模拟数据库行锁：同一行只能被一个事务持有。 */
        private final ReentrantLock rowLock = new ReentrantLock();

        /** 模拟多实例：每个实例各有一把本地锁，彼此不可见。 */
        private final ReentrantLock[] instanceLocks;

        /** 乐观锁状态：库存、已售和版本号打包在一起，CAS 时一起替换。 */
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

        /** 乐观锁的一次快照：版本号让 CAS 能发现"这期间有人改过"。 */
        private record Snapshot(int stock, int sold, long version) {
        }
    }
}
