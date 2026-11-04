package com.company.service.archlab.scenario.rateLimit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 限流三件套对照实验，用"虚拟时钟"驱动，不真的 sleep：
 *
 * <p>【解决什么问题】限流用来保护下游：没有它，突发流量、爬虫、恶意刷接口会把数据库或第三方服务打垮。
 * 但不同算法在"窗口边界"上的表现差别很大：固定窗口实现最便宜，却可能在两个窗口交界处
 * 放进接近 2 倍阈值的请求；滑动窗口更严格；令牌桶允许受控突发。
 * 如果只背概念，很容易在真实流量下才发现"限流怎么没生效"。
 *
 * <p>【为什么这样设计】请求用"到达时间列表"表示，算法只按时间推进内部状态，不调用 {@code sleep}。
 * 这样几毫秒就能跑完一轮实验，而且结果完全可重复——真实 sleep 会受调度和机器负载影响，
 * 同样的参数每次跑出的数字都不一样，没法对照。
 * 默认请求序列是"窗口边界两侧各来一批"：10 个请求落在 900ms，10 个落在 1100ms，
 * 窗口 1000ms、阈值 10。三种算法的差异会直接体现在 {@code allowed} 上：
 * <ul>
 *   <li>固定窗口：两个窗口各放 10 个 → 20 个全过（边界处瞬时翻倍，这就是它的漏洞）；</li>
 *   <li>滑动窗口：1100ms 时最近 1000ms 内已有 10 个 → 之后全部拒绝 → 只过 10 个；</li>
 *   <li>令牌桶：容量 10、速率 10/s，900ms 用光后到 1100ms 补 2 个令牌 → 过 12 个。
 *       它允许突发，但突发上限是桶容量，不会被窗口边界放大。</li>
 * </ul>
 * 三种算法没有绝对优劣：固定窗口实现最便宜（Redis INCR + EXPIRE），
 * 滑动窗口最严格（ZSET / Lua），令牌桶最平滑（允许受控突发）。
 *
 * <p>【怎么读这段代码】先看 {@link #burstAtBoundary} 造出的请求序列，理解实验想暴露什么；
 * 再依次读 {@link #fixedWindow}、{@link #slidingWindow}、{@link #tokenBucket}，
 * 重点看它们如何处理"时间跨过窗口边界"这件事；最后比较返回结果里的 {@code allowed} 与
 * {@code rejected}。
 */
@Service
public class RateLimitLab {

    private static final Logger log = LoggerFactory.getLogger(RateLimitLab.class);

    public enum Mode {
        FIXED_WINDOW("固定窗口"),
        SLIDING_WINDOW("滑动窗口"),
        TOKEN_BUCKET("令牌桶");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 限流实验结果。
     *
     * @param allowed      放行的请求数
     * @param rejected     被拒绝的请求数
     * @param allowedTimes 放行请求的到达时刻，便于核对边界行为
     */
    public record Result(String mode,
                         String label,
                         int limit,
                         long windowMillis,
                         int requests,
                         int allowed,
                         int rejected,
                         List<Long> allowedTimes) {
    }

    /**
     * 用给定请求序列模拟一次限流。
     *
     * @param mode         限流算法，不能为空
     * @param limit        窗口内或桶内允许的请求数，范围 [1, 1000000]
     * @param windowMillis 窗口长度/令牌补充周期（毫秒），范围 [1, 60000]
     * @param requestTimes 请求到达时间（毫秒，必须非递减）；传 {@code null} 使用默认的边界突发序列
     * @return 放行/拒绝数量与放行时刻
     * @throws IllegalArgumentException 参数越界、请求数超过 10 万或时间序列非递减时抛出
     */
    public Result simulate(Mode mode, int limit, long windowMillis, List<Long> requestTimes) {
        if (mode == null) {
            throw new IllegalArgumentException("mode 不能为空");
        }
        if (limit < 1 || limit > 1_000_000) {
            throw new IllegalArgumentException("limit 必须在 [1, 1000000] 之间");
        }
        if (windowMillis < 1 || windowMillis > 60_000) {
            throw new IllegalArgumentException("windowMillis 必须在 [1, 60000] 之间");
        }

        List<Long> times = requestTimes == null || requestTimes.isEmpty()
                ? burstAtBoundary(limit, windowMillis)
                : List.copyOf(requestTimes);
        if (times.size() > 100_000) {
            throw new IllegalArgumentException("请求数不能超过 100000");
        }
        // 三种算法都依赖"时间只向前走"，时间倒流会让窗口/令牌状态算错，所以提前拒绝。
        for (int i = 1; i < times.size(); i++) {
            if (times.get(i) < times.get(i - 1)) {
                throw new IllegalArgumentException("requestTimes 必须非递减");
            }
        }

        log.info("【限流实验】测什么：用同一串「窗口边界两侧各来一批」的请求，看不同算法实际放行多少；"
                        + "谁和谁对照：A组 FIXED_WINDOW / B组 SLIDING_WINDOW / C组 TOKEN_BUCKET；"
                        + "本次跑 {}（{}），limit={}，windowMillis={}，请求数={}",
                mode, mode.label(), limit, windowMillis, times.size());
        List<Long> allowed = switch (mode) {
            case FIXED_WINDOW -> fixedWindow(times, limit, windowMillis);
            case SLIDING_WINDOW -> slidingWindow(times, limit, windowMillis);
            case TOKEN_BUCKET -> tokenBucket(times, limit, windowMillis);
        };

        Result result = new Result(mode.name(),
                mode.label(),
                limit,
                windowMillis,
                times.size(),
                allowed.size(),
                times.size() - allowed.size(),
                allowed);
        log.info("【限流实验】{}｜结果：请求 {}，放行 {}，拒绝 {}，放行时刻={}",
                mode, times.size(), result.allowed(), result.rejected(), result.allowedTimes());
        if (mode == Mode.FIXED_WINDOW) {
            log.info("【限流实验】判读：A组放行 {} 个（limit={}），可能接近 2 倍阈值 —— 计数在窗口切换时清零，"
                            + "边界两侧的请求分属两个窗口、各自都没超限，但真实一个窗口内放进了两倍流量，"
                            + "这就是固定窗口的边界漏洞",
                    result.allowed(), limit);
        } else if (mode == Mode.SLIDING_WINDOW) {
            log.info("【限流实验】判读：B组只放行 {} 个（≈limit={}）—— 每个请求都回看最近一个窗口的已放行记录，"
                            + "边界前的请求仍被计入，所以不会被窗口边界放大",
                    result.allowed(), limit);
        } else {
            log.info("【限流实验】判读：C组放行 {} 个（阈值 limit={}）—— 令牌桶允许桶容量内的突发，"
                            + "但不会被窗口边界放大，长期速率由补充速度决定",
                    result.allowed(), limit);
        }
        return result;
    }

    /**
     * 固定窗口：把时间轴切成等长区间，每个区间独立计数。
     *
     * <p>漏洞就在边界：900ms 的 10 个和 1100ms 的 10 个分属两个窗口，
     * 各自都没超限，但真实 1000ms 内实际进来了 20 个。
     */
    private static List<Long> fixedWindow(List<Long> times, int limit, long windowMillis) {
        Map<Long, Integer> counts = new HashMap<>();
        List<Long> allowed = new ArrayList<>();
        for (long time : times) {
            // 整除得到窗口编号：0~999ms 是窗口 0，1000~1999ms 是窗口 1。
            long window = time / windowMillis;
            int used = counts.getOrDefault(window, 0);
            if (used < limit) {
                counts.put(window, used + 1);
                allowed.add(time);
            }
        }
        return allowed;
    }

    /**
     * 滑动窗口：只看"当前时刻往前一个窗口"内的已放行请求，超出窗口的从队头移除。
     *
     * <p>没有固定边界，所以不会出现固定窗口那种"边界两侧各放一批"的翻倍问题；
     * 代价是要为每个用户维护一个时间队列（生产上用 Redis ZSET 或 Lua 实现）。
     */
    private static List<Long> slidingWindow(List<Long> times, int limit, long windowMillis) {
        Deque<Long> accepted = new ArrayDeque<>();
        List<Long> allowed = new ArrayList<>();
        for (long time : times) {
            long threshold = time - windowMillis;
            // 到达时间非递减，所以队头一定是最早的请求：只要它已经滑出窗口，就可以安全移除。
            while (!accepted.isEmpty() && accepted.peekFirst() <= threshold) {
                accepted.pollFirst();
            }
            if (accepted.size() < limit) {
                accepted.addLast(time);
                allowed.add(time);
            }
        }
        return allowed;
    }

    /**
     * 令牌桶：桶里最多存 {@code limit} 个令牌，按固定速率补充，取到令牌才放行。
     *
     * <p>允许突发（桶里攒着的令牌可以一次用掉），但突发上限就是桶容量，
     * 不会被窗口边界放大；长期平均速率则由补充速率决定。
     */
    private static List<Long> tokenBucket(List<Long> times, int limit, long windowMillis) {
        double tokens = limit;
        long last = times.get(0);
        // 每个毫秒补充多少令牌：limit 个 / 一个窗口长度。
        double refillPerMilli = (double) limit / windowMillis;
        List<Long> allowed = new ArrayList<>();
        for (long time : times) {
            // 先按时间差补令牌，再取；用 min 封顶，攒下的令牌不会超过桶容量。
            tokens = Math.min(limit, tokens + Math.max(0, time - last) * refillPerMilli);
            last = time;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                allowed.add(time);
            }
        }
        return allowed;
    }

    /**
     * 默认序列：窗口边界前 90% 处来满一批，边界后 10% 处再来一批。
     */
    private static List<Long> burstAtBoundary(int limit, long windowMillis) {
        long before = Math.max(1, Math.round(windowMillis * 0.9));
        long after = windowMillis + Math.max(1, Math.round(windowMillis * 0.1));
        List<Long> times = new ArrayList<>(limit * 2);
        for (int i = 0; i < limit; i++) {
            times.add(before);
        }
        for (int i = 0; i < limit; i++) {
            times.add(after);
        }
        return times;
    }
}
