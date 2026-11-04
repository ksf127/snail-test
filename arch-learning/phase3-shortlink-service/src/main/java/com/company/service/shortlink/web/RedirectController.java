package com.company.service.shortlink.web;

import com.company.service.shortlink.domain.Shortlink;
import com.company.service.shortlink.service.ShortlinkService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Optional;

/**
 * 跳转入口：{@code GET /{code}} → 302。
 *
 * <p>【解决什么问题】这是整条 100 万 QPS 链路的入口，只做三件事：取短码、让 Service 解析、
 * 返回 302。它刻意不承担鉴权、限流、统计等职责，因为跳转与管理接口的流量特征完全不同 ——
 * 混在一个 Controller 里会让「只读热路径」被管理逻辑拖慢。
 *
 * <p>【为什么这样设计】为什么返回 302 而不是 301：
 * <ul>
 *   <li>301 会被浏览器长期缓存，之后的点击不再到达服务端，点击统计直接失真；</li>
 *   <li>短链可能被删除、改指向，301 一旦落到客户端就收不回来。</li>
 * </ul>
 * 这里额外加了 {@code Cache-Control: no-store}：牺牲一点点重复解析，
 * 换取点击统计与「删链立刻生效」两个性质。这是短链业务明确接受的权衡。
 *
 * <p>【怎么读这段代码】路由正则 {@code [0-9A-Za-z]{7}} 先完成一次前置过滤，
 * 长度或字符不对的请求根本不会进方法；进方法后只判断「命中/不存在」两条分支。
 * <b>日志注意</b>：本方法处于百万 QPS 热路径，只能打 debug，
 * 生产环境需要改为采样或完全关闭（详见 {@code ShortlinkService#resolve} 的说明）。
 */
@RestController
public class RedirectController {

    private static final Logger log = LoggerFactory.getLogger(RedirectController.class);

    private final ShortlinkService shortlinkService;

    public RedirectController(ShortlinkService shortlinkService) {
        this.shortlinkService = shortlinkService;
    }

    /**
     * 解析短码并 302 跳转。
     *
     * <p>线程安全：本类无可变状态，依赖的 Service 是线程安全单例。
     *
     * @param code 7 位 Base62 短码，由路由正则保证格式
     * @return 命中时 302 + Location；不存在或已过期时 404
     */
    @GetMapping("/{code:[0-9A-Za-z]{7}}")
    public ResponseEntity<Void> redirect(@PathVariable("code") String code) {
        // 百万 QPS 热路径：只允许 debug。info 级日志在这里会带来字符串拼接与 IO 竞争，直接成为瓶颈
        log.debug("【短链-跳转】跳转请求：code={}", code);
        Optional<Shortlink> link = shortlinkService.resolveForRedirect(code);
        if (link.isEmpty()) {
            // no-store 让浏览器不缓存 404，删链/重建后的行为更容易预测
            log.debug("【短链-跳转】短码未命中，返回 404：code={}", code);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .cacheControl(CacheControl.noStore())
                    .build();
        }
        // 302 + no-store：每次点击都回到服务端，点击统计才准确（301 会被浏览器缓存掉）
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(link.get().originalUrl()))
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
