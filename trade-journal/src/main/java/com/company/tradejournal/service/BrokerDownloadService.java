package com.company.tradejournal.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.company.tradejournal.config.DownloadProperties;
import com.company.tradejournal.config.SyncProperties;
import com.company.tradejournal.dto.downloads.BrokerConfigView;
import com.company.tradejournal.dto.downloads.DownloadBrokerResult;
import com.company.tradejournal.dto.downloads.DownloadRunResult;
import com.company.tradejournal.dto.downloads.DownloadStatus;
import com.company.tradejournal.dto.imports.SyncRunResult;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 到点自动打开券商导出页、点导出，把文件落到下载目录，再交给自动同步入库。
 *
 * <p>【解决什么问题】自动同步只是「不用手工上传」，前提仍是有人先在券商点一次导出。
 * 这里补上前半步：交易日 09:33、11:33、13:03、15:03（比扫描早 2 分钟）用本机浏览器
 * 打开配置好的导出页，按步骤点导出，下载完立刻触发一次扫描导入。
 *
 * <p>【为什么用浏览器而不是接口】券商没有对外开放的查询接口；模拟登录接口既不稳定也涉及账号密码。
 * 这里用 Playwright 启动本机 Chrome，复用你手动登录后的 Cookie，操作路径和人工点导出完全一致。
 *
 * <p>【凭什么能跟上页面改版】页面地址和点击步骤全部写在 yml 里，改版时改配置即可，不用改代码。
 * 步骤里认不出元素就返回 FAILED 并写明是哪一步，不会静默失败。
 *
 * <p>【边界】只负责「点导出→落文件→触发入库」，不猜文件名、不改账单内容；
 * 下载后能不能入库仍由自动同步的类型识别决定。
 */
@Service
public class BrokerDownloadService {

    private static final Logger log = LoggerFactory.getLogger(BrokerDownloadService.class);

    private static final List<String> SCHEDULE_POINTS = List.of("09:33", "11:33", "13:03", "15:03");

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("xlsx", "csv", "txt");

    private final DownloadProperties properties;

    private final SyncProperties syncProperties;

    private final FileSyncService fileSyncService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 最近一次下载结果，进程内保留。 */
    private volatile DownloadRunResult lastRun;

    public BrokerDownloadService(DownloadProperties properties, SyncProperties syncProperties,
                                 FileSyncService fileSyncService) {
        this.properties = properties;
        this.syncProperties = syncProperties;
        this.fileSyncService = fileSyncService;
    }

    /** 开盘与午间各下载一次。 */
    @Scheduled(cron = "0 33 9,11 * * MON-FRI", zone = "Asia/Shanghai")
    public void morningDownload() {
        if (properties.isEnabled()) {
            run("定时·09:33/11:33", null);
        }
    }

    /** 下午开盘与收盘各下载一次。 */
    @Scheduled(cron = "0 3 13,15 * * MON-FRI", zone = "Asia/Shanghai")
    public void afternoonDownload() {
        if (properties.isEnabled()) {
            run("定时·13:03/15:03", null);
        }
    }

    /**
     * 立即执行下载，供页面按钮手动触发。
     *
     * @param brokerName 只跑某个券商，为空跑全部启用的
     * @return 本次结果
     */
    public DownloadRunResult runNow(String brokerName) {
        return run("手动触发", brokerName);
    }

    /**
     * 打开有头浏览器让用户登录一次，登录态写入 user-data-dir，之后定时任务可直接复用。
     *
     * <p>立即返回，不等用户；用户登录完关闭浏览器窗口即可。
     *
     * @param brokerName 券商名
     */
    public void openLogin(String brokerName) {
        DownloadProperties.Broker broker = findBroker(brokerName);
        if (broker == null) {
            throw new IllegalArgumentException("没有找到券商任务：" + brokerName);
        }
        if (broker.getUrl() == null || broker.getUrl().isBlank()) {
            throw new IllegalStateException("券商「" + broker.getName() + "」没有配置 url");
        }
        Thread thread = new Thread(() -> keepBrowserOpen(broker), "broker-login-" + broker.getName());
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 读取自动下载现状。
     *
     * @return 目录、时点、上次结果与券商列表
     */
    public DownloadStatus status() {
        List<BrokerConfigView> brokers = properties.getBrokers().stream()
                .map(b -> new BrokerConfigView(b.getName(), b.isEnabled(), b.getUrl(),
                        b.getSteps() == null ? 0 : b.getSteps().size(), headless(b)))
                .toList();
        return new DownloadStatus(properties.isEnabled(), running.get(),
                blankToDefault(properties.getChannel(), "chromium"), directories(), SCHEDULE_POINTS,
                lastRun == null ? null : lastRun.finishedAt(), lastRun, brokers);
    }

    private DownloadRunResult run(String trigger, String onlyBroker) {
        LocalDateTime startedAt = LocalDateTime.now();
        if (!running.compareAndSet(false, true)) {
            return new DownloadRunResult(trigger, startedAt, LocalDateTime.now(), 0, 0, 0,
                    List.of(new DownloadBrokerResult(onlyBroker == null ? "全部" : onlyBroker,
                            "SKIPPED", 0, 0, "上一次下载还没结束，本次跳过")));
        }
        List<DownloadBrokerResult> details = new ArrayList<>();
        int files = 0;
        try {
            List<DownloadProperties.Broker> targets = properties.getBrokers().stream()
                    .filter(DownloadProperties.Broker::isEnabled)
                    .filter(b -> onlyBroker == null || onlyBroker.equals(b.getName()))
                    .toList();
            if (targets.isEmpty()) {
                details.add(new DownloadBrokerResult(onlyBroker == null ? "全部" : onlyBroker,
                        "SKIPPED", 0, 0, "没有启用的券商任务，请先在 application.yml 配置 trade-journal.download.brokers"));
            }
            for (DownloadProperties.Broker broker : targets) {
                DownloadBrokerResult result = download(broker);
                details.add(result);
                files += result.files();
            }
        } finally {
            running.set(false);
        }

        int importedRows = 0;
        if (files > 0) {
            // 下载完立刻扫一次，不用等 09:35 的定时任务
            SyncRunResult sync = fileSyncService.runNow();
            importedRows = sync.success();
            log.info("【自动下载】下载完成后立即入库：扫描 {} 个文件，写入 {} 行", sync.scanned(), sync.success());
        }

        DownloadRunResult result = new DownloadRunResult(trigger, startedAt, LocalDateTime.now(),
                details.size(), files, importedRows, details);
        lastRun = result;
        log.info("【自动下载】{}｜执行 {} 个券商，落盘 {} 个文件，入库 {} 行",
                trigger, result.brokers(), files, importedRows);
        return result;
    }

    /** 执行一个券商的导出流程。任何异常都收敛成 FAILED，不影响其它券商。 */
    private DownloadBrokerResult download(DownloadProperties.Broker broker) {
        long started = System.currentTimeMillis();
        List<Path> saved = new ArrayList<>();
        Playwright playwright = null;
        BrowserContext context = null;
        try {
            Path dir = downloadDirectory();
            Files.createDirectories(dir);
            Path profile = profileDirectory(broker);
            Files.createDirectories(profile);

            playwright = createPlaywright(broker);
            context = playwright.chromium().launchPersistentContext(profile, launchOptions(broker));
            Page page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
            page.setDefaultTimeout(timeoutMillis(broker));
            if (broker.getUrl() != null && !broker.getUrl().isBlank()) {
                page.navigate(broker.getUrl(), new Page.NavigateOptions().setTimeout(timeoutMillis(broker)));
            }
            for (DownloadProperties.Step step : broker.getSteps()) {
                apply(page, broker, step, dir, saved);
            }
            long elapsed = System.currentTimeMillis() - started;
            if (saved.isEmpty()) {
                return new DownloadBrokerResult(broker.getName(), "EMPTY", 0, elapsed,
                        "页面打开了，但没有任何步骤触发下载；请检查 steps 里的 download 动作");
            }
            return new DownloadBrokerResult(broker.getName(), "OK", saved.size(), elapsed,
                    "已保存：" + String.join("、", saved.stream().map(p -> p.getFileName().toString()).toList()));
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - started;
            String message = friendlyMessage(e);
            log.warn("【自动下载】券商 {} 失败：{}", broker.getName(), message, e);
            return new DownloadBrokerResult(broker.getName(), "FAILED", saved.size(), elapsed, message);
        } finally {
            // 浏览器已经被用户关掉时 close 会抛异常，但这不该影响已经落盘的结果
            closeQuietly(context);
            closeQuietly(playwright);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            log.debug("关闭浏览器资源失败（忽略）：{}", e.getMessage());
        }
    }

    private void apply(Page page, DownloadProperties.Broker broker, DownloadProperties.Step step,
                       Path dir, List<Path> saved) {
        String action = step.getAction() == null ? "" : step.getAction().trim().toLowerCase(Locale.ROOT);
        String selector = step.getSelector();
        int timeout = step.getTimeoutSeconds() == null ? timeoutMillis(broker) : step.getTimeoutSeconds() * 1000;
        switch (action) {
            case "click" -> page.click(selector, new Page.ClickOptions().setTimeout(timeout));
            case "fill" -> page.fill(selector, step.getValue() == null ? "" : step.getValue(),
                    new Page.FillOptions().setTimeout(timeout));
            case "press" -> page.press(selector, step.getValue(), new Page.PressOptions().setTimeout(timeout));
            case "waitforselector" -> page.waitForSelector(selector, new Page.WaitForSelectorOptions().setTimeout(timeout));
            case "wait" -> page.waitForTimeout(parseLong(step.getValue(), 1000));
            case "download" -> {
                Download download = page.waitForDownload(
                        () -> page.click(selector, new Page.ClickOptions().setTimeout(timeout)));
                Path target = dir.resolve(FILE_STAMP.format(LocalDateTime.now()) + "-"
                        + safeFileName(download.suggestedFilename()));
                download.saveAs(target);
                saved.add(target);
            }
            default -> throw new IllegalStateException("未知步骤 action：" + step.getAction()
                    + "（可用 click / fill / press / waitForSelector / wait / download）");
        }
    }

    /** 有头打开导出页，直到用户手动关掉窗口。 */
    private void keepBrowserOpen(DownloadProperties.Broker broker) {
        try (Playwright playwright = createPlaywright(broker)) {
            BrowserType.LaunchPersistentContextOptions options = launchOptions(broker).setHeadless(false);
            try (BrowserContext context = playwright.chromium()
                    .launchPersistentContext(profileDirectory(broker), options)) {
                Page page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
                page.navigate(broker.getUrl());
                log.info("【自动下载】已打开 {} 的登录窗口，登录完成后请关闭浏览器窗口", broker.getName());
                while (!page.isClosed()) {
                    page.waitForTimeout(1000);
                }
            }
        } catch (Exception e) {
            log.warn("【自动下载】打开登录窗口失败：{}", friendlyMessage(e), e);
        }
    }

    private BrowserType.LaunchPersistentContextOptions launchOptions(DownloadProperties.Broker broker) {
        BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                .setHeadless(headless(broker))
                .setAcceptDownloads(true)
                .setViewportSize(1440, 900);
        String channel = channel(broker);
        if (channel != null && !channel.isBlank()) {
            options.setChannel(channel);
        }
        return options;
    }

    private String channel(DownloadProperties.Broker broker) {
        return broker.getChannel() == null || broker.getChannel().isBlank()
                ? properties.getChannel() : broker.getChannel();
    }

    /**
     * 用本机 Chrome/Edge 时禁用驱动的浏览器下载。
     *
     * <p>Playwright.create() 默认会先把 Chromium/Firefox/WebKit 各下一套（几百 MB），
     * 首次执行会长时间卡住甚至超时；既然指定了 channel，直接用系统已装的浏览器即可。
     */
    private Playwright createPlaywright(DownloadProperties.Broker broker) {
        String channel = channel(broker);
        if (channel == null || channel.isBlank() || "chromium".equalsIgnoreCase(channel)) {
            return Playwright.create();
        }
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        return Playwright.create(new Playwright.CreateOptions().setEnv(env));
    }

    private boolean headless(DownloadProperties.Broker broker) {
        return broker.getHeadless() == null ? properties.isHeadless() : broker.getHeadless();
    }

    private int timeoutMillis(DownloadProperties.Broker broker) {
        return Math.max(5, properties.getTimeoutSeconds()) * 1000;
    }

    private DownloadProperties.Broker findBroker(String name) {
        return properties.getBrokers().stream()
                .filter(b -> b.getName() != null && b.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    /** 下载目录：优先 download.directories，其次 sync.directories，最后系统下载目录。 */
    private List<String> directories() {
        if (!properties.getDirectories().isEmpty()) {
            return properties.getDirectories();
        }
        if (!syncProperties.getDirectories().isEmpty()) {
            return syncProperties.getDirectories();
        }
        return List.of(Paths.get(System.getProperty("user.home"), "Downloads").toString());
    }

    private Path downloadDirectory() {
        return Paths.get(directories().get(0)).toAbsolutePath().normalize();
    }

    private Path profileDirectory(DownloadProperties.Broker broker) {
        if (broker.getUserDataDir() != null && !broker.getUserDataDir().isBlank()) {
            return Paths.get(broker.getUserDataDir()).toAbsolutePath().normalize();
        }
        String base = blankToDefault(properties.getUserDataDir(),
                Paths.get(System.getProperty("user.home"), ".trade-journal", "browser").toString());
        return Paths.get(base, safeDirName(broker.getName())).toAbsolutePath().normalize();
    }

    /** 券商名当目录名用：只替换非法字符，不补扩展名。 */
    private String safeDirName(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "broker" : cleaned;
    }

    /** 去掉文件名里的路径分隔符和冒号，避免 saveAs 写到目录外。 */
    private String safeFileName(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (cleaned.isEmpty()) {
            return "broker-export.xlsx";
        }
        int dot = cleaned.lastIndexOf('.');
        String extension = dot < 0 ? "" : cleaned.substring(dot + 1).toLowerCase(Locale.ROOT);
        return SUPPORTED_EXTENSIONS.contains(extension) ? cleaned : cleaned + ".xlsx";
    }

    private String friendlyMessage(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (e instanceof PlaywrightException) {
            if (message.contains("Executable doesn't exist") || message.contains("BrowserType.launch")) {
                return message + "｜请确认已安装 Chrome 或 Edge；或在 trade-journal.download.channel 里改成 msedge，"
                        + "或执行 mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args=\"install chromium\"";
            }
            if (message.contains("Target page, context or browser has been closed")) {
                return "浏览器被关闭，流程中断｜请先点「登录券商」完成一次登录，执行期间不要手动关闭浏览器窗口";
            }
        }
        if (e instanceof java.nio.file.AccessDeniedException) {
            return "没有权限访问 " + message + "｜请检查浏览器配置目录和下载目录是否存在、当前账号是否有写权限";
        }
        if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
            return message;
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private static long parseLong(String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
