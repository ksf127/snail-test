package com.company.tradejournal.config;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 自动下载配置。
 *
 * <p>【解决什么问题】只扫下载目录仍然要人「先去券商点一次导出」。这个配置让系统到点自己
 * 用本机 Chrome 打开券商导出页、点导出，把文件落到下载目录，再交给自动同步入库。
 *
 * <p>【为什么做成可配置】每家券商的导出页地址、按钮位置都不一样，页面还会改版；
 * 把「打开哪个地址、点哪些按钮」写死在代码里，改版就整个功能不可用。
 * 所以这里用一串步骤描述操作，改版时只改 yml，不动代码。
 *
 * <p>【怎么配】见 application.yml 里的 {@code trade-journal.download}，
 * 首次使用先调 {@code POST /downloads/login} 用有头浏览器登录一次，
 * 登录态保存在 {@code user-data-dir}，之后无人值守也能导出。
 */
@Data
@Component
@ConfigurationProperties(prefix = "trade-journal.download")
public class DownloadProperties {

    /** 是否启用定时下载；关掉后只能手动触发。 */
    private boolean enabled = true;

    /** 默认是否有头运行。定时任务建议 true（无头），首次登录用登录接口会强制有头。 */
    private boolean headless = true;

    /** 浏览器内核：chrome / msedge 用本机已安装的浏览器；留空则用 Playwright 自带的 Chromium。 */
    private String channel = "chrome";

    /** 登录态目录，留空则用 ~/.trade-journal/browser。每个券商会再分一个子目录。 */
    private String userDataDir = "";

    /** 单步操作默认超时秒数。 */
    private int timeoutSeconds = 45;

    /** 下载落地目录，留空则复用同步配置里的 directories（默认系统下载目录）。 */
    private List<String> directories = new ArrayList<>();

    /** 券商任务列表。 */
    private List<Broker> brokers = new ArrayList<>();

    /** 一个券商的导出任务。 */
    @Data
    public static class Broker {

        /** 展示名，同时作为登录态子目录名。 */
        private String name;

        /** 是否启用这个券商。 */
        private boolean enabled = true;

        /** 导出页地址。 */
        private String url;

        /** 单独覆盖全局 headless，不填则跟随全局。 */
        private Boolean headless;

        /** 单独覆盖全局 channel，不填则跟随全局。 */
        private String channel;

        /** 单独覆盖登录态目录。 */
        private String userDataDir;

        /** 按顺序执行的操作。 */
        private List<Step> steps = new ArrayList<>();
    }

    /**
     * 一步操作。
     *
     * <p>可用 action：
     * <ul>
     *   <li>click：点一下 selector</li>
     *   <li>fill：往 selector 填 value</li>
     *   <li>press：在 selector 上按 value 键（如 Enter）</li>
     *   <li>waitForSelector：等 selector 出现</li>
     *   <li>wait：等 value 毫秒</li>
     *   <li>download：点 selector 并等待下载完成，文件存入下载目录</li>
     * </ul>
     */
    @Data
    public static class Step {

        /** click / fill / press / waitForSelector / wait / download。 */
        private String action;

        /** 页面元素选择器，wait 动作不需要。 */
        private String selector;

        /** fill 的文本、press 的按键、wait 的毫秒数。 */
        private String value;

        /** 单独覆盖超时秒数。 */
        private Integer timeoutSeconds;
    }
}
