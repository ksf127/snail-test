# 券商自动下载配置模板

配置位置：`application.yml` → `trade-journal.download.brokers`（把原来的 `brokers: []` 整段替换掉）。

系统不存账号密码。第一次用某个券商时，先调一次登录接口打开有头浏览器，你手工登录，登录态会保存在 `userDataDir`，之后定时任务复用。

## 1. 最小可用模板（直接改 3 处即可）

```yaml
trade-journal:
  download:
    enabled: true
    headless: true          # 定时任务无头；登录接口会自动切有头
    channel: msedge         # 复用本机 Edge；装的是 Chrome 就写 chrome
    brokers:
      - name: 华泰-成交
        enabled: true
        url: https://trade.htsc.com.cn/xxx        # 改成你的导出页地址
        userDataDir: ${user.home}/.trade-journal/browser/htsc
        steps:
          - action: waitForSelector
            selector: "#export-btn"                # 改成导出按钮的 selector
          - action: download
            selector: "#export-btn"
```

跑通这一个之后，再按同样的结构补资金、持仓两个任务。

## 2. 完整模板（成交 / 资金 / 持仓 三个任务）

同一券商的三类导出共用一套登录态，所以三个任务的 `userDataDir` 必须写成同一个目录；`name` 不同是给接口和日志区分用的。

```yaml
trade-journal:
  download:
    enabled: true
    headless: true
    channel: msedge
    timeout-seconds: 45
    brokers:
      # ---------- 成交流水 ----------
      - name: 华泰-成交
        enabled: true
        url: https://trade.htsc.com.cn/xxx
        userDataDir: ${user.home}/.trade-journal/browser/htsc
        steps:
          - action: click
            selector: "#menu-query"                # 进入查询菜单
          - action: click
            selector: "text=交割单"                 # 支持文本选择器
          - action: waitForSelector
            selector: "#trade-table"               # 等表格渲染出来再导出
          - action: wait
            value: "1000"                          # 毫秒；页面异步查询时留一点时间
          - action: download
            selector: "button:has-text('导出')"

      # ---------- 每日资金 ----------
      - name: 华泰-资金
        enabled: true
        url: https://trade.htsc.com.cn/xxx
        userDataDir: ${user.home}/.trade-journal/browser/htsc
        steps:
          - action: click
            selector: "#menu-query"
          - action: click
            selector: "text=资金流水"
          - action: waitForSelector
            selector: "#cash-table"
          - action: download
            selector: "button:has-text('导出')"

      # ---------- 当前持仓 ----------
      - name: 华泰-持仓
        enabled: true
        url: https://trade.htsc.com.cn/xxx
        userDataDir: ${user.home}/.trade-journal/browser/htsc
        steps:
          - action: click
            selector: "#menu-query"
          - action: click
            selector: "text=持仓"
          - action: waitForSelector
            selector: "#position-table"
          - action: download
            selector: "button:has-text('导出')"
```

如果三类导出在同一个页面、只是三个按钮，也可以只配一个任务，连续写三个 `download` 步骤，一个任务会依次下载多个文件：

```yaml
      - name: 华泰-全部导出
        enabled: true
        url: https://trade.htsc.com.cn/xxx
        userDataDir: ${user.home}/.trade-journal/browser/htsc
        steps:
          - action: download
            selector: "#export-trade"
          - action: download
            selector: "#export-cash"
          - action: download
            selector: "#export-position"
```

## 3. 字段说明

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `name` | 是 | 任务名。登录接口按这个名字找任务；不填 `userDataDir` 时还会被当作登录态子目录名 |
| `enabled` | 否 | 默认 `true`；先配 `false` 调通再开 |
| `url` | 是 | 导出页地址。登录态失效时会停在这里，需要重新登录 |
| `userDataDir` | 否 | 登录态目录。多个任务要共用登录态时，填同一个绝对路径 |
| `headless` | 否 | 覆盖全局；首次调试建议 `false`，能看见点击过程 |
| `channel` | 否 | `msedge` / `chrome`；不填则跟随全局，全局再空就用 Playwright 自带 Chromium |
| `steps` | 是 | 按顺序执行的动作列表 |

单步字段：

| 字段 | 说明 |
| --- | --- |
| `action` | `click` / `fill` / `press` / `waitForSelector` / `wait` / `download` |
| `selector` | CSS 选择器（也支持 `text=xxx`、`button:has-text('导出')` 这类 Playwright 选择器）；`wait` 不需要 |
| `value` | `fill` 填的文本、`press` 按的键（如 `Enter`）、`wait` 等的毫秒数 |
| `timeoutSeconds` | 单步超时，覆盖全局 `timeout-seconds` |

## 4. 各动作什么时候用

| 动作 | 用途 |
| --- | --- |
| `waitForSelector` | 等页面元素出现，最常用于页面跳转后、表格渲染后 |
| `click` | 切菜单、切标签页、点查询 |
| `fill` | 填日期区间、填代码 |
| `press` | 在输入框里按回车触发查询 |
| `wait` | 兜底等待（毫秒），页面有异步查询但找不到明确元素时用 |
| `download` | **点这个元素并等下载完成**，文件保存到下载目录；必须指向真正触发下载的按钮 |

一个任务只配 `download` 也能用，页面默认就停在导出页时最简单。

## 5. 怎么拿 selector

1. 在登录后的浏览器里打开导出页；
2. F12 → 选中「导出」按钮 → 右键 `Copy` → `Copy selector`；
3. 尽量用 `#id` 或带文本的选择器，少用 `div > div:nth-child(3) > span` 这种层级，页面一改就失效；
4. 按钮在弹窗里时，先加一步 `waitForSelector` 等弹窗出现。

## 6. 登录一次

```bash
curl -X POST "http://localhost:8210/downloads/login?broker=华泰-成交"
```

`broker` 必须和配置里的 `name` 完全一致。会弹出有头浏览器，登录完成（含短信/验证码）后**手动关闭窗口**，登录态即写入 `userDataDir`。三个任务共用同一个 `userDataDir` 时，登录其中任意一个即可。

## 7. 验证顺序

```text
POST /downloads/run?broker=华泰-成交    → 看是否下载成功
POST /imports/sync/run                  → 手动触发入库
GET  /imports/sync/status               → 看识别类型、写入行数
GET  /trades、/stats/overview           → 核对笔数、持仓、胜率
```

`/downloads/run` 不传 `broker` 时跑全部启用的任务；单个券商失败不影响其它券商。下载成功后会自动触发一次同步，不用再点导入。

## 8. 已知限制

- **不支持 iframe 内的元素**：如果导出页嵌在 iframe 里，先取到 iframe 的地址直接配成 `url`，否则选择器找不到。
- **`fill` 的 `value` 是字面量**：没有 `{today}` 这类日期占位符。日期区间建议选「全部」或留空；确需动态日期要改代码加占位符。
- **同一 `userDataDir` 不能并发**：系统内部是串行执行券商任务的，但不要同时用两个进程跑同一个 `userDataDir`，Chromium 会锁目录报 `Lock file can not be created`。
- **登录态会过期**：券商一般几小时到几天失效，失败信息会提示「页面跳到了登录页」之类的现象，重新调一次 `/downloads/login` 即可。

## 9. 常见报错对照

| 报错 | 原因 |
| --- | --- |
| `没有权限访问 ...` | 登录态目录或下载目录不可写；检查 `userDataDir`、下载目录权限 |
| `浏览器被关闭，流程中断` | 执行期间被手动关掉窗口；等任务结束后再关 |
| `Timeout ... waiting for selector` | selector 不对或页面没加载完；先用有头模式看点击到哪一步 |
| `Lock file can not be created!` | 同一个 `userDataDir` 被另一个浏览器/进程占用 |
| 任务成功但没入库 | 下载目录不在 `trade-journal.sync.directories` 里，或文件类型/表头识别不出来 |
