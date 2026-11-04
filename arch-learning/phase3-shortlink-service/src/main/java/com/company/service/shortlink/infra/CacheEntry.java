package com.company.service.shortlink.infra;

import com.company.service.shortlink.domain.Shortlink;

/**
 * 缓存里存的对象：要么是一条真实短链，要么是一个"明确的空"。
 *
 * <p>【解决什么问题】缓存必须能表达"我查过了，这个短码确实不存在"。
 * 如果只用 null 表示不存在，就分不清"没缓过"和"缓过但不存在"，
 * 于是每个不存在的短码都会反复回源，缓存挡不住任何穿透流量。
 * 这个类型把两种情况在类型层面区分开，负缓存才成立。
 *
 * <p>【为什么这样设计】用 record 而不是枚举或 Optional：
 * <ul>
 *   <li>同一个对象里既能带数据、又能带状态，L1/L2 只需要存一种类型；</li>
 *   <li>负缓存的 TTL 与正常缓存不同（更短），状态必须跟着条目一起走，见 {@code ShortlinkCache}；</li>
 *   <li>record 不可变，可以安全地在多个线程/缓存间共享。</li>
 * </ul>
 *
 * <p>【怎么读这段代码】{@code negative == true} 时 {@code link} 必为 null，反之亦然。
 * 创建入口只有两个静态工厂：{@link #of(Shortlink)} 与 {@link #absent()}，
 * 不要在业务代码里直接 new，否则容易造出"negative=true 但 link 非空"的矛盾状态。
 *
 * @param link     真实短链；负缓存时为 null
 * @param negative 是否为负缓存（true 表示"确认不存在"）
 */
public record CacheEntry(Shortlink link, boolean negative) {

    /**
     * 包装一条真实短链。
     *
     * @param link 短链，不能为 null
     * @return negative=false 的缓存条目
     */
    public static CacheEntry of(Shortlink link) {
        return new CacheEntry(link, false);
    }

    /**
     * 构造负缓存条目，表示"这个短码确认不存在/已过期"。
     *
     * @return negative=true、link=null 的缓存条目
     */
    public static CacheEntry absent() {
        return new CacheEntry(null, true);
    }
}
