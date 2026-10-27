package com.company.starter.lock;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 线程级重入登记表：记录"当前线程在这个 key 上持有哪个 token、重入了几次"。
 *
 * <p>为什么需要它：Redis 里的重入计数是服务端状态，本地必须有一份镜像才能判断
 * "这次 unlock 是该真的删 key，还是只减一层"。少这份镜像，内层 unlock 会把
 * 外层还需要的锁删掉，表现为偶发的并发穿透。
 */
final class ReentrantTokenRegistry {

    private final ThreadLocal<Map<String, Hold>> heldByThread = new ThreadLocal<>();

    /**
     * 当前线程在该 key 上的持有信息，没有则返回 null。
     */
    Hold peek(String key) {
        Map<String, Hold> map = heldByThread.get();
        return map == null ? null : map.get(key);
    }

    /**
     * 首次加锁成功：登记 token，重入计数为 1。
     */
    void register(String key, String token) {
        Map<String, Hold> map = heldByThread.get();
        if (map == null) {
            map = new HashMap<>();
            heldByThread.set(map);
        }
        map.put(key, new Hold(token, 1));
    }

    /**
     * 重入加锁成功：本地计数 +1，与服务端 HINCRBY 保持一致。
     */
    void increment(String key) {
        Hold hold = peek(key);
        if (hold == null) {
            throw new IllegalStateException("key 未登记，无法重入：" + key);
        }
        hold.count++;
    }

    /**
     * 释放一层。
     *
     * @return true 表示这是最外层，需要真正删除 Redis 中的锁
     */
    boolean release(String key) {
        Map<String, Hold> map = heldByThread.get();
        if (map == null) {
            return false;
        }
        Hold hold = map.get(key);
        if (hold == null) {
            return false;
        }
        hold.count--;
        if (hold.count > 0) {
            return false;
        }
        map.remove(key);
        if (map.isEmpty()) {
            heldByThread.remove();
        }
        return true;
    }

    static final class Hold {

        private final String token;

        private int count;

        private Hold(String token, int count) {
            this.token = token;
            this.count = count;
        }

        String token() {
            return token;
        }

        int count() {
            return count;
        }
    }

    static String randomToken() {
        return UUID.randomUUID().toString();
    }
}
