package com.company.starter.threadpool;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 拒绝策略白名单：只暴露可解释的几种，避免业务方随手写匿名类导致线上行为不可预期。
 */
public enum RejectedPolicy {

    /** 直接抛 RejectedExecutionException，交给上层快速失败。 */
    ABORT {
        @Override
        public RejectedExecutionHandler handler() {
            return new ThreadPoolExecutor.AbortPolicy();
        }
    },

    /** 由提交线程自己执行，用调用方线程形成天然背压。 */
    CALLER_RUNS {
        @Override
        public RejectedExecutionHandler handler() {
            return new ThreadPoolExecutor.CallerRunsPolicy();
        }
    },

    /** 静默丢弃当前任务（只适合可丢的埋点类任务）。 */
    DISCARD {
        @Override
        public RejectedExecutionHandler handler() {
            return new ThreadPoolExecutor.DiscardPolicy();
        }
    },

    /** 丢弃队列中最老的任务，给新任务腾位置。 */
    DISCARD_OLDEST {
        @Override
        public RejectedExecutionHandler handler() {
            return new ThreadPoolExecutor.DiscardOldestPolicy();
        }
    };

    public abstract RejectedExecutionHandler handler();
}
