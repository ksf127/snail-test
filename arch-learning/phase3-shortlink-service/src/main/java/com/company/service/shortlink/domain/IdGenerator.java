package com.company.service.shortlink.domain;

/**
 * 发号器。
 *
 * <p>短链场景只有创建时才需要发号，QPS 远低于跳转，
 * 但一旦重复发号就是资损级事故，所以这里保证的唯一性优先级高于吞吐。
 */
public interface IdGenerator {

    /**
     * 取下一个全局唯一 ID。
     */
    long nextId();
}
