package com.company.service.archlab.scenario.rateLimit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * 限流三件套对照实验，用"虚拟时钟"驱动，不真的 sleep：
 *
 * <p>请求序列默认是"窗口边界两侧各来一批"——10 个请求落在 900ms，10 个落在 1100ms，
 * 窗口 1000ms、阈值 10。三种算法的差异会直接体现在 {@code allowed} 上：
 * <ul>
 *   <li>固定窗口：两个窗口各放 10 个 → 20 个全过（边界处瞬时翻倍，这就是它的漏洞）；</li>
 *   <li>滑动窗口：1100ms 时最近 1000ms 内已有 10 个 → 之后全部拒绝 → 只过 10 个；</li>
 *   <li>令牌桶：容量 10、速率 10/s，900ms 用光后到 1100ms 补 2 个令牌 → 过 12 个。
 *       它允许突发，但突发上限是桶容量，不会被窗口边界放大。</li>
 * </ul>
 *
 * <p>三种算法没有绝对优劣：固定窗口实现最便宜（Redis INCR + EXPIRE），
 * 滑动窗口最严格（ZSET / Lua），令牌桶最平滑（允许受控突发）。
 */
@Service
public class RateLimitLab {

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
     * @param requestTimes 请求到达时间（毫秒，必须非递减）；传 {@code null} 使用默认的边界突发序列
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
        for (int i = 1; i < times.size(); i++) {
            if (times.get(i) < times.get(i - 1)) {
                throw new IllegalArgumentException("requestTimes 必须非递减");
            }
        }

        List<Long> allowed = switch (mode) {
            case FIXED_WINDOW -> fixedWindow(times, limit, windowMillis);
            case SLIDING_WINDOW -> slidingWindow(times, limit, windowMillis);
            case TOKEN_BUCKET -> tokenBucket(times, limit, windowMillis);
        };

        return new Result(mode.name(),
                mode.label(),
                limit,
                windowMillis,
                times.size(),
                allowed.size(),
                times.size() - allowed.size(),
                allowed);
    }

    private static List<Long> fixedWindow(List<Long> times, int limit, long windowMillis) {
        Map<Long, Integer> counts = new HashMap<>();
        List<Long> allowed = new ArrayList<>();
        for (long time : times) {
            long window = time / windowMillis;
            int used = counts.getOrDefault(window, 0);
            if (used < limit) {
                counts.put(window, used + 1);
                allowed.add(time);
            }
        }
        return allowed;
    }

    private static List<Long> slidingWindow(List<Long> times, int limit, long windowMillis) {
        Deque<Long> accepted = new ArrayDeque<>();
        List<Long> allowed = new ArrayList<>();
        for (long time : times) {
            long threshold = time - windowMillis;
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

    private static List<Long> tokenBucket(List<Long> times, int limit, long windowMillis) {
        double tokens = limit;
        long last = times.get(0);
        double refillPerMilli = (double) limit / windowMillis;
        List<Long> allowed = new ArrayList<>();
        for (long time : times) {
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
