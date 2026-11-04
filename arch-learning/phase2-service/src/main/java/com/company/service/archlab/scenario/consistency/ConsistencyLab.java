package com.company.service.archlab.scenario.consistency;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 缓存与数据库一致性实验：同一个"读缓存→回源→回填"的读路径，
 * 配上四种写路径，看哪种写法会留下长期脏数据。
 *
 * <p>【解决什么问题】缓存和数据库是两个独立的存储，一次写操作无法原子地同时更新两者。
 * 只要读请求的"回源 + 回填"和写请求的"更库 + 删缓存"发生交错，缓存里就可能留下永远不消失的旧值。
 * 用户看到的是旧价格、旧库存，而且刷新多少次都没用——因为缓存里的脏数据没人会再删它。
 *
 * <p>【为什么这样设计】这里用<b>受控时序</b>复现竞态窗口，而不是靠多线程抢跑：
 * 线程调度不确定，跑一百次可能一次都复现不出来，结论就变成了"我本地没问题"。
 * 真实系统的窗口大小取决于"回源耗时 + 回填耗时"与"更库 + 删缓存耗时"的差值，
 * 所以用显式步骤把最坏时序固定下来，每一次都能稳定复现。
 * 四种策略不是"实现不同"，而是"删除缓存相对于更库和回填的位置"不同：
 * <ul>
 *   <li>先更库后删缓存：回填发生在删缓存之后，回填时数据库已是新值 → 缓存永久停留在旧值；</li>
 *   <li>先删缓存后更库：删缓存后、更库前有读请求回源，回填旧值，此后再也没人删它 → 缓存永久停留在旧值。
 *       注意它的问题机制与上一种不同：回填那一刻数据库还是旧值（不算 staleFill），
 *       真正的问题是"删除过早 + 回填丢失了这次删除"；</li>
 *   <li>延迟双删：旧值确实被回填过，但第二次删除会把它清掉 → 最终一致（代价是延迟与二次删除的可靠性）；</li>
 *   <li>读写共用 key 锁：回填与"更库+删缓存"互斥，不会出现"已提交之后又回填旧值" → 既没有脏回填，也没有脏读。</li>
 * </ul>
 *
 * <p>【怎么读这段代码】先看 {@link Strategy} 的四个枚举，它们就是四种写路径；
 * 再按 {@link #execute} 里每个 case 的步骤顺序读，每一步都会往 timeline 里追加一条记录；
 * 最后看 {@link #run} 如何统计两种指标：
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

    private static final Logger log = LoggerFactory.getLogger(ConsistencyLab.class);

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

    /** 时序记录中的一步；cache/db 是执行完这一步之后的状态快照。 */
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
     * 连续跑多轮实验并汇总结果。
     *
     * @param strategy 写路径策略，不能为空
     * @param rounds   重复轮数：一轮脏不算问题，每一轮都脏才是设计缺陷
     * @return 脏轮数、脏回填轮数以及最后一轮的完整时序
     * @throws IllegalArgumentException strategy 为空或 rounds 不在 [1, 1000] 时抛出
     */
    public Result run(Strategy strategy, int rounds) {
        if (strategy == null) {
            throw new IllegalArgumentException("strategy 不能为空");
        }
        if (rounds < 1 || rounds > 1_000) {
            throw new IllegalArgumentException("rounds 必须在 [1, 1000] 之间");
        }

        log.info("【缓存一致性实验】测什么：同一条「读缓存→回源→回填」读路径配上不同写路径，"
                        + "看哪一组会在缓存里留下长期脏数据；谁和谁对照：A组 先更库后删缓存 / B组 先删缓存后更库 / "
                        + "C组 延迟双删 / D组 读写共用 key 锁；本次跑 {}（{}），rounds={}",
                strategy, strategy.label(), rounds);
        log.info("【缓存一致性实验】本次策略（{}）的机制：{}", strategy.label(), consistencyMechanism(strategy));
        int dirtyRounds = 0;
        int staleFills = 0;
        Round last = null;
        for (int i = 0; i < rounds; i++) {
            Round round = new Round();
            execute(strategy, round);
            if (round.staleFill) {
                staleFills++;
            }
            // 用最终读到的值与数据库比较：只要不一致，用户就会看到脏数据。
            if (!round.observed().equals(round.db)) {
                dirtyRounds++;
            }
            last = round;
        }

        Result result = new Result(strategy.name(),
                strategy.label(),
                rounds,
                dirtyRounds,
                staleFills,
                last.observed(),
                last.db,
                List.copyOf(last.steps));
        log.info("【缓存一致性实验】{}｜结果：{} 轮里脏轮数 {}/{}，脏回填轮数 {}/{}，最终缓存值={}，数据库值={}",
                strategy.label(), rounds, dirtyRounds, rounds, staleFills, rounds,
                result.finalValue(), result.dbValue());
        if (result.dirty()) {
            log.warn("【缓存一致性实验】判读：{} 组 {} 轮里有 {} 轮留下脏数据（缓存={}，数据库={}），"
                            + "说明这条写路径存在无法自愈的竞态窗口；用户会一直读到旧值，刷新多少次都没用",
                    strategy.label(), rounds, dirtyRounds, result.finalValue(), result.dbValue());
        } else {
            log.info("【缓存一致性实验】判读：{} 组 {} 轮结束后缓存与数据库一致，"
                            + "说明删除动作或 key 锁覆盖了回填窗口",
                    strategy.label(), rounds);
        }
        return result;
    }

    /**
     * 给日志用的机制说明：四种策略的差别只在「删除缓存」相对「更库」和「回填」的位置。
     */
    private static String consistencyMechanism(Strategy strategy) {
        return switch (strategy) {
            case UPDATE_DB_THEN_DELETE_CACHE ->
                    "回填发生在删缓存之后，旧值被重新写回，而且此后再也没人删它";
            case DELETE_CACHE_THEN_UPDATE_DB ->
                    "回填发生在删缓存之后、更库之前，删掉的缓存又被旧值填了回来";
            case DELAYED_DOUBLE_DELETE ->
                    "第二次删除把「删缓存与更库之间」回填的旧值再清一次，缩小但不是消除窗口";
            case LOCK_PROTECTED ->
                    "读写共用 key 锁，回填与「更库+删缓存」互斥，不存在交错窗口";
        };
    }

    /**
     * 按策略执行一轮受控时序。
     *
     * <p>所有步骤都在单线程里按固定顺序执行，顺序本身就是我们要复现的"最坏交错"。
     */
    private void execute(Strategy strategy, Round round) {
        switch (strategy) {
            case UPDATE_DB_THEN_DELETE_CACHE -> {
                // reader 先读到旧值（此时还没回填），writer 完成"更库 + 删缓存"之后 reader 才回填，
                // 于是旧值绕过删除动作重新写进缓存，且之后再也没人删它。
                round.miss();
                String read = round.readDb();
                round.writeDb("writer 更新数据库");
                round.deleteCache("writer 删除缓存");
                round.fillCache(read, "reader 回填缓存（旧值）");
            }
            case DELETE_CACHE_THEN_UPDATE_DB -> {
                // 删除发生在更库之前：reader 在"删除后、更库前"回源，读到旧值并回填，
                // 这次回填发生在删除之后，所以删除没能清掉它；更库也不再碰缓存。
                round.deleteCache("writer 删除缓存");
                round.miss();
                String read = round.readDb();
                round.fillCache(read, "reader 回填缓存（旧值）");
                round.writeDb("writer 更新数据库");
            }
            case DELAYED_DOUBLE_DELETE -> {
                // 第一次删除只是为了尽早清掉旧缓存；关键是更库之后的第二次删除，
                // 它负责把"删除与更库之间回填进去的旧值"再清一次。
                round.deleteCache("writer 删除缓存（第一次）");
                round.miss();
                String read = round.readDb();
                round.fillCache(read, "reader 回填缓存（旧值）");
                round.writeDb("writer 更新数据库");
                round.deleteCache("writer 延迟删除缓存（第二次）");
            }
            case LOCK_PROTECTED -> {
                // 读写共用同一把 key 锁：reader 的回源+回填整体与 writer 的更库+删缓存互斥，
                // 不会出现"更库已经提交，reader 却拿着旧值回填"的时序。
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
            // 回填那一刻数据库已经不是这个值，说明这次回填写进去的就是过期数据。
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

        /** 下一次读请求实际会拿到的值：缓存有就用缓存，否则回源数据库。 */
        private String observed() {
            return cache != null ? cache : db;
        }

        private void record(String actor, String action) {
            steps.add(new Step(steps.size() + 1, actor, action, cache, db));
        }
    }
}
