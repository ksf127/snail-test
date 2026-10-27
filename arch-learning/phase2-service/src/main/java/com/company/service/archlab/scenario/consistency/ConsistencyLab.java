package com.company.service.archlab.scenario.consistency;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

/**
 * 缓存与数据库一致性实验：同一个"读缓存→回源→回填"的读路径，
 * 配上四种写路径，看哪种写法会留下长期脏数据。
 *
 * <p>这里用<b>受控时序</b>复现竞态窗口，而不是靠多线程抢跑：
 * 线程调度不确定，跑一百次可能一次都复现不出来，结论就变成了"我本地没问题"。
 * 真实系统的窗口大小取决于"回源耗时 + 回填耗时"与"更库 + 删缓存耗时"的差值，
 * 所以用显式步骤把最坏时序固定下来，每一次都能稳定复现。
 *
 * <p>四个策略的结论（{@link #run} 的返回值可验证）：
 * <ul>
 *   <li>先更库后删缓存：回填发生在删缓存之后，回填时数据库已是新值 → 缓存永久停留在旧值；</li>
 *   <li>先删缓存后更库：删缓存后、更库前有读请求回源，回填旧值，此后再也没人删它 → 缓存永久停留在旧值。
 *       注意它的问题机制与上一种不同：回填那一刻数据库还是旧值（不算 staleFill），
 *       真正的问题是"删除过早 + 回填丢失了这次删除"；</li>
 *   <li>延迟双删：旧值确实被回填过，但第二次删除会把它清掉 → 最终一致（代价是延迟与二次删除的可靠性）；</li>
 *   <li>读写共用 key 锁：回填与"更库+删缓存"互斥，不会出现"已提交之后又回填旧值" → 既没有脏回填，也没有脏读。</li>
 * </ul>
 *
 * <p>两个指标要分开看：
 * <ul>
 *   <li>{@code staleFills}：回填时数据库已经是新值（回填即过期）的轮数——先更库后删缓存才会命中；</li>
 *   <li>{@code dirtyRounds}：一轮结束后缓存与数据库仍不一致的轮数——这是用户真正能感知到的脏数据。</li>
 * </ul>
 *
 * <p>注意：延迟双删只是缩小窗口，不是消除窗口——如果回填发生在第二次删除之后，
 * 脏数据依然会留下，所以生产上仍然需要"版本号/订阅 binlog 兜底"这类最终防线。
 */
@Service
public class ConsistencyLab {

    private static final String OLD_VALUE = "v1";

    private static final String NEW_VALUE = "v2";

    public enum Strategy {

        /** Cache Aside 最常见的写法：先写库，再删缓存 */
        UPDATE_DB_THEN_DELETE_CACHE("先更库后删缓存"),

        /** 先删缓存再写库：看起来能减少脏读，实际窗口更大 */
        DELETE_CACHE_THEN_UPDATE_DB("先删缓存后更库"),

        /** 删一次、更库、延迟再删一次 */
        DELAYED_DOUBLE_DELETE("先删缓存→更库→延迟再删"),

        /** 读回填与写路径共用同一把 key 锁 */
        LOCK_PROTECTED("读写共用 key 锁");

        private final String label;

        Strategy(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Step(int seq, String actor, String action, String cache, String db) {
    }

    /**
     * @param staleFills  回填时数据库已经变成新值的轮数（回填即过期）
     * @param dirtyRounds 本轮结束时缓存与数据库仍不一致的轮数
     * @param timeline    最后一轮的完整步骤，用于核对脏值出现的具体位置
     */
    public record Result(String strategy,
                         String label,
                         int rounds,
                         int dirtyRounds,
                         int staleFills,
                         String finalValue,
                         String dbValue,
                         List<Step> timeline) {

        /** 最终读到的值与数据库不一致 */
        public boolean dirty() {
            return dirtyRounds > 0;
        }
    }

    /**
     * @param rounds 重复轮数：一轮脏不算问题，每一轮都脏才是设计缺陷
     */
    public Result run(Strategy strategy, int rounds) {
        if (strategy == null) {
            throw new IllegalArgumentException("strategy 不能为空");
        }
        if (rounds < 1 || rounds > 1_000) {
            throw new IllegalArgumentException("rounds 必须在 [1, 1000] 之间");
        }

        int dirtyRounds = 0;
        int staleFills = 0;
        Round last = null;
        for (int i = 0; i < rounds; i++) {
            Round round = new Round();
            execute(strategy, round);
            if (round.staleFill) {
                staleFills++;
            }
            if (!round.observed().equals(round.db)) {
                dirtyRounds++;
            }
            last = round;
        }

        return new Result(strategy.name(),
                strategy.label(),
                rounds,
                dirtyRounds,
                staleFills,
                last.observed(),
                last.db,
                List.copyOf(last.steps));
    }

    private void execute(Strategy strategy, Round round) {
        switch (strategy) {
            case UPDATE_DB_THEN_DELETE_CACHE -> {
                round.miss();
                String read = round.readDb();
                round.writeDb("writer 更新数据库");
                round.deleteCache("writer 删除缓存");
                round.fillCache(read, "reader 回填缓存（旧值）");
            }
            case DELETE_CACHE_THEN_UPDATE_DB -> {
                round.deleteCache("writer 删除缓存");
                round.miss();
                String read = round.readDb();
                round.fillCache(read, "reader 回填缓存（旧值）");
                round.writeDb("writer 更新数据库");
            }
            case DELAYED_DOUBLE_DELETE -> {
                round.deleteCache("writer 删除缓存（第一次）");
                round.miss();
                String read = round.readDb();
                round.fillCache(read, "reader 回填缓存（旧值）");
                round.writeDb("writer 更新数据库");
                round.deleteCache("writer 延迟删除缓存（第二次）");
            }
            case LOCK_PROTECTED -> {
                round.lock("reader 获取 key 锁");
                round.miss();
                String read = round.readDb();
                round.fillCache(read, "reader 回填缓存");
                round.unlock("reader 释放 key 锁");
                round.lock("writer 获取 key 锁");
                round.writeDb("writer 更新数据库");
                round.deleteCache("writer 删除缓存");
                round.unlock("writer 释放 key 锁");
            }
        }
    }

    /**
     * 一轮实验的共享状态：一个缓存条目 + 一个数据库行。
     * 单线程执行，步骤顺序就是我们要复现的"最坏时序"。
     */
    private static final class Round {

        private final List<Step> steps = new ArrayList<>();

        private String db = OLD_VALUE;

        private String cache;

        private boolean staleFill;

        private void miss() {
            record("reader", "读缓存未命中");
        }

        private String readDb() {
            record("reader", "回源读数据库 → " + db);
            return db;
        }

        private void fillCache(String value, String action) {
            if (!value.equals(db)) {
                staleFill = true;
            }
            cache = value;
            record("reader", action + " → " + value);
        }

        private void writeDb(String action) {
            db = NEW_VALUE;
            record("writer", action + " → " + db);
        }

        private void deleteCache(String action) {
            cache = null;
            record("writer", action);
        }

        private void lock(String action) {
            record("lock", action);
        }

        private void unlock(String action) {
            record("lock", action);
        }

        private String observed() {
            return cache != null ? cache : db;
        }

        private void record(String actor, String action) {
            steps.add(new Step(steps.size() + 1, actor, action, cache, db));
        }
    }
}
