package com.company.starter.lock;

/**
 * 一次加锁成功后的凭证。
 *
 * <p>必须用 try-with-resources 或 finally 释放：租期只是兜底，
 * 依赖租期释放意味着锁被占用的时间远长于业务时间。
 */
public interface LockHandle extends AutoCloseable {

    String key();

    /**
     * 持锁凭证。释放时要用它校验，防止把别人的锁删掉。
     */
    String token();

    /**
     * 句柄是否仍然有效（本地视角，不代表服务端一定还持有）。
     */
    boolean isHeld();

    /**
     * 释放锁。可重入场景下，内层释放只减少计数，不真正解锁。
     */
    void unlock();

    @Override
    default void close() {
        unlock();
    }
}
