package com.company.service.shortlink.web;

import com.company.service.shortlink.domain.Shortlink;
import com.company.service.shortlink.service.ShortlinkService;

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
 * <p>为什么返回 302 而不是 301：
 * <ul>
 *   <li>301 会被浏览器长期缓存，之后的点击不再到达服务端，点击统计直接失真</li>
 *   <li>短链可能被删除、改指向，301 一旦落到客户端就收不回来</li>
 * </ul>
 *
 * <p>这里额外加了 {@code Cache-Control: no-store}：牺牲一点点重复解析，
 * 换取点击统计与"删链立刻生效"两个性质。这是短链业务明确接受的权衡。
 */
@RestController
public class RedirectController {

    private final ShortlinkService shortlinkService;

    public RedirectController(ShortlinkService shortlinkService) {
        this.shortlinkService = shortlinkService;
    }

    @GetMapping("/{code:[0-9A-Za-z]{7}}")
    public ResponseEntity<Void> redirect(@PathVariable("code") String code) {
        Optional<Shortlink> link = shortlinkService.resolveForRedirect(code);
        if (link.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .cacheControl(CacheControl.noStore())
                    .build();
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(link.get().originalUrl()))
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
