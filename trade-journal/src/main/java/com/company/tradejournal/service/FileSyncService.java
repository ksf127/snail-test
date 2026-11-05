package com.company.tradejournal.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.company.tradejournal.config.SyncProperties;
import com.company.tradejournal.dto.imports.ImportCommitRequest;
import com.company.tradejournal.dto.imports.ImportCommitResult;
import com.company.tradejournal.dto.imports.ImportPreview;
import com.company.tradejournal.dto.imports.ImportRow;
import com.company.tradejournal.dto.imports.SyncFileLog;
import com.company.tradejournal.dto.imports.SyncRunResult;
import com.company.tradejournal.dto.imports.SyncStatus;
import com.company.tradejournal.entity.SyncFileEntity;
import com.company.tradejournal.mapper.SyncFileMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 定时扫描下载目录，把券商导出的文件自动入库。
 *
 * <p>【解决什么问题】每天手工下载再上传太慢，还容易漏。券商网页没有对外接口，
 * 所以采用「你在券商端一键导出 → 系统定时扫下载目录」的方式：不用登录券商，
 * 也不依赖页面结构，换券商只要把导出文件放到同一个目录即可。
 *
 * <p>【怎么判断是不是新文件】对文件内容取 SHA-256。同一份文件重复导出、改名都只处理一次；
 * 文件内容变了（比如午间导出比开盘导出多几笔）会被当成新文件再导一遍，
 * 重复成交由导入服务按「日期+代码+方向+价格+数量」跳过，不会翻倍。
 *
 * <p>【怎么认类型】先看文件名里的「成交 / 资金 / 持仓」等关键词，认不出再看表头：
 * 用三种类型各试解析一次，取「必填列齐全且数据行最多」的那种。都认不出就记一条 SKIPPED，
 * 不猜着导，避免把持仓文件当成交写进去。
 *
 * <p>【时间点】交易日 09:35、11:35、13:05、15:05 各扫一次，对应开盘、午间休息、下午开盘、收盘。
 */
@Service
public class FileSyncService {

    private static final Logger log = LoggerFactory.getLogger(FileSyncService.class);

    private static final Set<String> EXTENSIONS = Set.of("xlsx", "csv", "txt");

    private static final List<String> SCHEDULE_POINTS = List.of("09:35", "11:35", "13:05", "15:05");

    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    private final SyncProperties properties;

    private final DataImportService importService;

    private final SyncFileMapper syncFileMapper;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 最近一次扫描结果，进程内保留，重启后从记录表看历史。 */
    private volatile SyncRunResult lastRun;

    public FileSyncService(SyncProperties properties, DataImportService importService,
                           SyncFileMapper syncFileMapper) {
        this.properties = properties;
        this.importService = importService;
        this.syncFileMapper = syncFileMapper;
    }

    /** 开盘与午间各扫一次。 */
    @Scheduled(cron = "0 35 9,11 * * MON-FRI", zone = "Asia/Shanghai")
    public void morningScan() {
        if (properties.isEnabled()) {
            run("定时·09:35/11:35");
        }
    }

    /** 下午开盘与收盘各扫一次。 */
    @Scheduled(cron = "0 5 13,15 * * MON-FRI", zone = "Asia/Shanghai")
    public void afternoonScan() {
        if (properties.isEnabled()) {
            run("定时·13:05/15:05");
        }
    }

    /**
     * 立即扫描并导入，供页面「立即同步」按钮手动触发。
     *
     * @return 本次结果
     */
    public SyncRunResult runNow() {
        return run("手动触发");
    }

    /**
     * 读取自动同步现状。
     *
     * @return 目录、定时点、上次结果与最近记录
     */
    public SyncStatus status() {
        List<SyncFileLog> recent = syncFileMapper.selectRecent(properties.getRecentLimit()).stream()
                .map(this::toLog)
                .toList();
        LocalDateTime lastScanAt = recent.isEmpty() ? null : recent.get(0).scannedAt();
        return new SyncStatus(properties.isEnabled(), running.get(), properties.getAccountId(),
                directories().stream().map(Path::toString).toList(), SCHEDULE_POINTS,
                lastScanAt, lastRun, recent);
    }

    private SyncRunResult run(String trigger) {
        LocalDateTime startedAt = LocalDateTime.now();
        if (!running.compareAndSet(false, true)) {
            return new SyncRunResult(trigger, startedAt, LocalDateTime.now(),
                    0, 0, 0, 0, 0, 0, 0, 0, List.of("上一次扫描还没结束，本次跳过"));
        }
        List<String> messages = new ArrayList<>();
        int scanned = 0;
        int imported = 0;
        int skippedFiles = 0;
        int failedFiles = 0;
        int rows = 0;
        int success = 0;
        int duplicate = 0;
        int failedRows = 0;
        try {
            for (Path file : collectFiles(messages)) {
                scanned++;
                SyncFileEntity entity = handle(file, messages);
                if (entity == null) {
                    continue;
                }
                rows += value(entity.getTotal());
                success += value(entity.getSuccess());
                duplicate += value(entity.getSkipped());
                failedRows += value(entity.getFailed());
                if ("IMPORTED".equals(entity.getStatus())) {
                    imported++;
                } else if ("FAILED".equals(entity.getStatus())) {
                    failedFiles++;
                } else {
                    skippedFiles++;
                }
            }
            if (scanned == 0) {
                messages.add("监听目录里没有最近 " + properties.getMaxAgeMinutes() + " 分钟内导出的新文件");
            }
        } finally {
            running.set(false);
        }
        SyncRunResult result = new SyncRunResult(trigger, startedAt, LocalDateTime.now(),
                scanned, imported, skippedFiles, failedFiles, rows, success, duplicate, failedRows, messages);
        lastRun = result;
        log.info("【自动同步】{}｜扫描 {} 个文件，入库 {} 个，跳过 {} 个，失败 {} 个；写入 {} 行",
                trigger, scanned, imported, skippedFiles, failedFiles, success);
        return result;
    }

    /** 列出候选文件：扩展名匹配、不是临时文件、修改时间在窗口内。 */
    private List<Path> collectFiles(List<String> messages) {
        LocalDateTime since = LocalDateTime.now().minusMinutes(Math.max(1, properties.getMaxAgeMinutes()));
        List<Path> files = new ArrayList<>();
        for (Path dir : directories()) {
            if (!Files.isDirectory(dir)) {
                messages.add("目录不存在：" + dir);
                continue;
            }
            try (var stream = Files.list(dir)) {
                List<Path> found = stream
                        .filter(Files::isRegularFile)
                        .filter(this::supported)
                        .filter(p -> {
                            try {
                                return Files.getLastModifiedTime(p).toInstant()
                                        .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().isAfter(since);
                            } catch (IOException e) {
                                return false;
                            }
                        })
                        .toList();
                files.addAll(found);
            } catch (IOException e) {
                messages.add("读取目录失败：" + dir + "（" + e.getMessage() + "）");
            }
        }
        files.sort(Comparator.comparingLong(this::lastModified));
        return files.size() > properties.getMaxFiles() ? files.subList(0, properties.getMaxFiles()) : files;
    }

    private boolean supported(Path path) {
        String name = path.getFileName().toString();
        if (name.startsWith("~$") || name.startsWith(".")) {
            return false;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * 处理单个文件。已处理过的内容返回 null（不重复记日志）。
     */
    private SyncFileEntity handle(Path file, List<String> messages) {
        String fileName = file.getFileName().toString();
        long size;
        byte[] bytes;
        try {
            size = Files.size(file);
            if (size > MAX_FILE_BYTES) {
                messages.add(fileName + " 超过 20MB，已跳过");
                return null;
            }
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            messages.add(fileName + " 读取失败：" + e.getMessage());
            return null;
        }
        String fingerprint = fingerprint(bytes);
        if (syncFileMapper.selectByFingerprint(fingerprint) != null) {
            return null;
        }

        SyncFileEntity entity = new SyncFileEntity();
        entity.setFingerprint(fingerprint);
        entity.setFileName(fileName);
        entity.setFilePath(file.toAbsolutePath().toString());
        entity.setAccountId(properties.getAccountId());
        entity.setScannedAt(LocalDateTime.now());
        entity.setTotal(0);
        entity.setSuccess(0);
        entity.setSkipped(0);
        entity.setFailed(0);

        try {
            String type = detectType(bytes, fileName);
            if (type == null) {
                entity.setStatus("SKIPPED");
                entity.setMessage("认不出这是成交流水、每日资金还是当前持仓，请确认表头后手动导入");
                messages.add(fileName + "：认不出导入类型，已跳过");
                syncFileMapper.insert(entity);
                return entity;
            }
            entity.setImportType(type);
            ImportPreview preview = importService.preview(type, properties.getAccountId(), bytes, fileName);
            if (!preview.missing().isEmpty()) {
                entity.setStatus("SKIPPED");
                entity.setTotal(preview.total());
                entity.setMessage("缺少必填列：" + String.join("、", preview.missing()));
                messages.add(fileName + "：缺少必填列，已跳过");
                syncFileMapper.insert(entity);
                return entity;
            }
            if (preview.total() == 0) {
                entity.setStatus("SKIPPED");
                entity.setMessage("文件里没有数据行");
                messages.add(fileName + "：没有数据行，已跳过");
                syncFileMapper.insert(entity);
                return entity;
            }
            ImportCommitResult result = importService.commit(new ImportCommitRequest(
                    type, properties.getAccountId(), properties.getDefaultReason(), true, commitRows(preview)));
            entity.setTotal(result.total());
            entity.setSuccess(result.success());
            entity.setSkipped(result.skipped());
            entity.setFailed(result.failed());
            entity.setImportedAt(LocalDateTime.now());
            entity.setStatus(result.success() > 0 ? "IMPORTED"
                    : result.failed() > 0 ? "FAILED" : "SKIPPED");
            entity.setMessage(buildMessage(type, result));
            if ("FAILED".equals(entity.getStatus())) {
                messages.add(fileName + "：" + entity.getMessage());
            }
            syncFileMapper.insert(entity);
            return entity;
        } catch (Exception e) {
            entity.setStatus("FAILED");
            entity.setMessage("处理失败：" + e.getMessage());
            syncFileMapper.insert(entity);
            messages.add(fileName + "：处理失败（" + e.getMessage() + "）");
            return entity;
        }
    }

    private String buildMessage(String type, ImportCommitResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("识别为").append(typeLabel(type)).append("；写入 ").append(result.success())
                .append(" 行，跳过 ").append(result.skipped()).append(" 行，失败 ").append(result.failed()).append(" 行");
        if (!result.errors().isEmpty()) {
            sb.append("。").append(String.join("；", result.errors().subList(0, Math.min(3, result.errors().size()))));
        }
        return sb.toString();
    }

    /**
     * 先按文件名关键词认类型，认不出再用表头试解析。
     *
     * @return trades / daily / positions，都认不出时 null
     */
    private String detectType(byte[] bytes, String fileName) throws IOException {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (containsAny(name, "持仓", "position", "股份余额")) {
            return ImportParser.TYPE_POSITIONS;
        }
        if (containsAny(name, "资金", "对账", "净值", "资产", "cash", "equity", "balance")) {
            return ImportParser.TYPE_DAILY;
        }
        if (containsAny(name, "成交", "交易", "trade", "deal", "order")) {
            return ImportParser.TYPE_TRADES;
        }
        String best = null;
        int bestScore = 0;
        for (String type : List.of(ImportParser.TYPE_TRADES, ImportParser.TYPE_DAILY, ImportParser.TYPE_POSITIONS)) {
            ImportPreview preview = importService.preview(type, properties.getAccountId(), bytes, fileName);
            if (!preview.missing().isEmpty() || preview.total() == 0) {
                continue;
            }
            int score = preview.valid() + preview.duplicate();
            if (score > bestScore) {
                bestScore = score;
                best = type;
            }
        }
        return best;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 预览行转提交行；问题行不提交，其余带上行号方便报错定位。 */
    private List<Map<String, String>> commitRows(ImportPreview preview) {
        Map<Integer, Map<String, String>> unique = new LinkedHashMap<>();
        for (ImportRow row : preview.rows()) {
            if ("INVALID".equals(row.status()) || row.data() == null) {
                continue;
            }
            Map<String, String> data = new LinkedHashMap<>(row.data());
            data.put("_rowNo", String.valueOf(row.rowNo()));
            unique.put(row.rowNo(), data);
        }
        return new ArrayList<>(unique.values());
    }

    private List<Path> directories() {
        Set<Path> result = new LinkedHashSet<>();
        List<String> configured = properties.getDirectories();
        if (configured == null || configured.isEmpty()) {
            result.add(Paths.get(System.getProperty("user.home"), "Downloads").toAbsolutePath().normalize());
        } else {
            for (String dir : configured) {
                if (dir != null && !dir.isBlank()) {
                    result.add(Paths.get(dir.trim()).toAbsolutePath().normalize());
                }
            }
        }
        return new ArrayList<>(result);
    }

    private String fingerprint(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }

    private int value(Integer number) {
        return number == null ? 0 : number;
    }

    private String typeLabel(String type) {
        if (ImportParser.TYPE_DAILY.equals(type)) {
            return "每日资金";
        }
        return ImportParser.TYPE_POSITIONS.equals(type) ? "当前持仓" : "成交流水";
    }

    private SyncFileLog toLog(SyncFileEntity entity) {
        return new SyncFileLog(entity.getId(), entity.getFileName(), entity.getFilePath(), entity.getImportType(),
                entity.getStatus(), value(entity.getTotal()), value(entity.getSuccess()),
                value(entity.getSkipped()), value(entity.getFailed()), entity.getMessage(),
                entity.getScannedAt(), entity.getImportedAt());
    }
}
