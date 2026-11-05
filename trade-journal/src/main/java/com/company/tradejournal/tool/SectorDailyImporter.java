package com.company.tradejournal.tool;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.company.tradejournal.entity.SectorDailyEntity;
import com.company.tradejournal.mapper.SectorDailyMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 真实行情导入：把 V2 脚本里的示例点位替换成中证银行（399986）的收盘行情。
 *
 * <p>【解决什么问题】示例数据只能联调页面，不能用来做判断。这个导入器按交易日拉取
 * 真实收盘点位与涨跌幅，幂等写入 {@code t_sector_daily}，让「板块参考」变成可用数据。
 *
 * <p>【为什么这样设计】1）只用 JDK 自带 HttpClient 加 Jackson，不引第三方行情 SDK；
 * 2）写成可重跑的批处理而不是定时任务：一次导入一个月，重复执行结果一致；
 * 3）默认先删掉 note='示例' 的行，避免示例点位和真实点位混在一张表里；
 * 4）通过 {@code sector-import} profile 开启，正常启动服务时它不会执行。
 *
 * <p>【怎么读这段代码】{@link #run} 依次做四件事：算区间 → 请求接口 → 逐行解析 →
 * 清示例数据并 upsert。解析依赖东方财富 kline 的字段顺序：
 * {@code 日期,开,收,高,低,量,额,振幅,涨跌幅,涨跌额,换手}，所以收盘取下标 2、涨跌幅取下标 8。
 */
@Component
@ConditionalOnProperty(prefix = "trade-journal.sector-import", name = "enabled", havingValue = "true")
public class SectorDailyImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SectorDailyImporter.class);

    private static final String KLINE_URL = "https://push2his.eastmoney.com/api/qt/stock/kline/get"
            + "?secid=%s&fields1=f1,f2,f3,f4,f5,f6"
            + "&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61"
            + "&klt=101&fqt=0&beg=%s&end=%s";

    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private static final String SAMPLE_NOTE = "示例";

    private final SectorDailyMapper sectorDailyMapper;

    private final String secid;

    private final String start;

    private final String end;

    private final boolean purgeSample;

    public SectorDailyImporter(SectorDailyMapper sectorDailyMapper,
                               @Value("${trade-journal.sector-import.secid:0.399986}") String secid,
                               @Value("${trade-journal.sector-import.start:}") String start,
                               @Value("${trade-journal.sector-import.end:}") String end,
                               @Value("${trade-journal.sector-import.purge-sample:true}") boolean purgeSample) {
        this.sectorDailyMapper = sectorDailyMapper;
        this.secid = secid;
        this.start = start;
        this.end = end;
        this.purgeSample = purgeSample;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException, InterruptedException {
        LocalDate to = end.isBlank() ? LocalDate.now() : LocalDate.parse(end);
        LocalDate from = start.isBlank() ? to.minusDays(30) : LocalDate.parse(start);
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("start 不能晚于 end：" + from + " > " + to);
        }

        String url = String.format(KLINE_URL, secid, from.format(BASIC_DATE), to.format(BASIC_DATE));
        log.info("【行情导入】拉取 secid={}｜{} ~ {}", secid, from, to);

        JsonNode data = fetch(url);
        if (data == null || data.isMissingNode() || !data.hasNonNull("klines")) {
            log.warn("【行情导入】接口没有返回行情数据，可能是代码错误或区间内全为非交易日，未做任何写入");
            return;
        }

        String sectorCode = secid.contains(".") ? secid.substring(secid.indexOf('.') + 1) : secid;
        String sectorName = data.path("name").asText(sectorCode);

        List<SectorDailyEntity> rows = new ArrayList<>();
        for (JsonNode node : data.get("klines")) {
            rows.add(parseLine(node.asText(), sectorCode, sectorName));
        }
        if (rows.isEmpty()) {
            log.warn("【行情导入】区间内没有可解析的交易日，未做任何写入");
            return;
        }

        if (purgeSample) {
            int deleted = sectorDailyMapper.deleteByNote(SAMPLE_NOTE);
            log.info("【行情导入】清理示例数据 {} 行", deleted);
        }
        for (SectorDailyEntity row : rows) {
            sectorDailyMapper.upsert(row);
        }
        log.info("【行情导入】完成：{} {} 行，{} ~ {}", sectorName, rows.size(),
                rows.get(0).getTradeDate(), rows.get(rows.size() - 1).getTradeDate());
    }

    private JsonNode fetch(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("行情接口返回 HTTP " + response.statusCode());
        }
        return new ObjectMapper().readTree(response.body()).path("data");
    }

    /**
     * 解析一行 kline 文本。
     *
     * @param line       逗号分隔的行情，字段顺序见类注释
     * @param sectorCode 板块代码
     * @param sectorName 板块名称
     * @return 待写入的行情行，note 留空表示真实数据
     */
    private SectorDailyEntity parseLine(String line, String sectorCode, String sectorName) {
        String[] parts = line.split(",");
        if (parts.length < 9) {
            throw new IllegalStateException("行情字段不足，无法解析：" + line);
        }
        SectorDailyEntity row = new SectorDailyEntity();
        row.setTradeDate(LocalDate.parse(parts[0]));
        row.setSectorCode(sectorCode);
        row.setSectorName(sectorName);
        row.setCloseValue(new BigDecimal(parts[2]).setScale(4, RoundingMode.HALF_UP));
        row.setPctChg(new BigDecimal(parts[8]).setScale(4, RoundingMode.HALF_UP));
        // 真实行情不写备注：note='示例' 是清理示例数据的筛选条件，这里必须为空
        row.setNote(null);
        return row;
    }
}
