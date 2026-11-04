# arch-learning 三阶段学习手册

把架构能力拆成三个阶段，每个阶段一个独立服务，按 **1 → 2 → 3** 的顺序看。
依赖是线性的：阶段一的锁与内存语义 → 阶段二的缓存 / 中间件 / 分布式锁 → 阶段三把前面所有东西用到真实场景。

所有实验都是**纯本地、独立启动**，不接 Nacos，默认全内存实现，改配置即可切 Redis / RabbitMQ。

---

## 一、模块总览

| 阶段 | 模块 | 端口 | 上下文路径 | 主题 | JDK | 调试入口（启动后打开） |
|---|---|---|---|---|---|---|
| 阶段一 | `phase1-service` | 8201 | `/phase1` | 多线程 + 设计模式 | **21**（虚拟线程） | http://localhost:8201/phase1/doc.html |
| 阶段二 | `phase2-service` | 8202 | `/phase2` | 缓存 + 中间件 + 各种锁 | 17 | http://localhost:8202/phase2/doc.html |
| 阶段三 A | `phase3-shortlink-service` | 8203 | `/shortlink` | 100 万 QPS 短链 | 17 | http://localhost:8203/shortlink/doc.html |
| 阶段三 B | `phase3-mall-service` | 8204 | `/mall` | 商城 + 分布式事务 | 17 | http://localhost:8204/mall/doc.html |
| 工具 | `arch-learning-benchmark` | — | — | JMH 基准测试 | 17 | — |

统一启动方式（先 `-am` 把同级 starter 一起编出来）：

```powershell
mvn -o -pl arch-learning/phase1-service -am spring-boot:run
```

把 `phase1-service` 换成其他模块即可。所有接口统一返回 `ApiResult<T>`，参数非法返回 `40001`。

**启动后调试入口**（Knife4j 中文界面，左侧树形菜单，参数带默认值，点「调试」直接发请求）：

| 阶段 | 地址 |
|---|---|
| 阶段一 | http://localhost:8201/phase1/doc.html |
| 阶段二 | http://localhost:8202/phase2/doc.html |
| 阶段三短链 | http://localhost:8203/shortlink/doc.html |
| 阶段三商城 | http://localhost:8204/mall/doc.html |

---

## 二、阶段一：多线程 + 设计模式（:8201）

### 建议阅读顺序

| # | 实验 | 类 | 接口 |
|---|---|---|---|
| 1 | JMM 可见性 | `concurrency/VisibilityDemo` | `GET /phase1/concurrency/visibility` |
| 2 | 原子性：五种计数器 | `concurrency/CounterComparison` | `GET /phase1/concurrency/counter`、`/counter/all` |
| 3 | 自研 CAS 自旋锁 | `concurrency/CasSpinLock` | 单元测试 `CasSpinLockTest` |
| 4 | 生产者-消费者 | `concurrency/BoundedBuffer` | 单元测试 `BoundedBufferTest` |
| 5 | 虚拟线程 vs 平台线程 | `concurrency/VirtualThreadComparison` | `GET /phase1/concurrency/threads` |
| 6 | 策略 + 工厂 | `pattern/pricing/*` | `GET /phase1/patterns/pricing` |
| 7 | 责任链 | `pattern/validation/*` | `GET /phase1/patterns/validation` |
| 8 | 状态机 | `pattern/statemachine/*` | `GET /phase1/patterns/state-machine` |

先读类上的 Javadoc，里面写了「实验结论」，再跑接口对照数字。

### 1. 可见性

```powershell
# 裸字段：读线程可能看不到写线程的更新
curl "http://localhost:8201/phase1/concurrency/visibility?useVolatile=false&timeoutMillis=2000"
# volatile：写后立即可见
curl "http://localhost:8201/phase1/concurrency/visibility?useVolatile=true&timeoutMillis=2000"
```

**怎么看返回值**（字段含义决定了这实验有没有读对）：

| 字段 | 含义 |
|---|---|
| `observed` | 读线程在超时前是否看到了新值 |
| `latencyMicros` | **真正的可见性延迟**：从写线程置位那一刻算起，到读线程读到为止 |
| `polls` | 读线程自旋了多少圈 |
| `elapsedMillis` | 从读线程启动算起的总耗时，**含写线程固定睡的 50ms**，别名"会骗人的那个数" |

关键点：`elapsedMillis` 永远是 50ms 起步，两种实现都一样，因为那 50ms 是写线程故意睡觉，不是可见性延迟。**只看 `latencyMicros`**。

本地跑 `useVolatile=false` 大概率也是几微秒就看到了，这是正常的：

- x86-64 是 TSO 模型，普通写也会很快传播到其他核心，比 JMM 的要求严格得多；
- 本实验的循环体里有 `System.nanoTime()` 和接口调用 `flag.get()`，JIT（C2）无法证明读是循环不变量，所以没把读提升出循环。

**所以这个接口在 x86 上测出来的数字差异全是调度噪声**（甚至会出现 volatile 那次更慢的倒挂）。重复跑它只会得到随机数，看不出任何规律。

#### 真正能看出差别的接口：`/visibility/hoisting`

```powershell
# 普通字段：读线程一路自旋到 20 亿次上限，写入从未可见
curl "http://localhost:8201/phase1/concurrency/visibility/hoisting?useVolatile=false&settleMillis=500"
# volatile：主线程一置位它立刻退出
curl "http://localhost:8201/phase1/concurrency/visibility/hoisting?useVolatile=true&settleMillis=500"
```

区别只在于**循环体够不够干净**。这个实验把循环压到只剩一次字段读：

```java
long spins = 0L;
while (!holder.stop && spins < LIMIT) { spins++; }
```

条件里只有一次字段读、循环体只有一次自增，C2 能证明「循环期间没人写这个字段」，于是把读挪到循环外只做一次，循环退化成 `while (spins < LIMIT)` —— 这就是 **loop-invariant code motion（读提升）**。

| 参数 | 返回 `sawWrite` | `spins` | 含义 |
|---|---|---|---|
| `useVolatile=false` | `false` | 等于 `spinLimit`（20 亿） | 读被提出循环，写入永远看不到 |
| `useVolatile=true` | `true` | 远小于上限（实测约 4.5 亿） | volatile 读禁止提升，立刻看到 |

本机实测日志：

```
WARN  读线程跑满 2000000000 次上限才退出：写入从未对它可见
INFO  读线程看到了写入并退出：自旋 451178365 次，耗时 311ms
```

这就是**两个数量级、且方向确定**的差异 —— 一眼能分辨，不是噪声。

为什么 `spins` 要设上限：没有上限的话普通字段那次会永远转下去，把 Tomcat 请求线程彻底挂死。加上限后「没看到写入」表现为「跑满上限」，照样能判定，但不会拖垮服务。

#### 正式对比：`/visibility/hoisting/compare`

单跑一次的结果取决于 C2 当时有没有编译完，可能「碰巧通过」。要看规律必须数频次：

```powershell
curl "http://localhost:8201/phase1/concurrency/visibility/hoisting/compare?settleMillis=2000&trials=3"
```

同一套循环，普通字段和 volatile 字段各跑 `trials` 轮，**交替执行**（否则先跑的那组会独占 JIT 预热，条件不对等），最后统计各自「跑满上限才退出」的次数：

| 字段 | `settleMillis` | `trials` | 返回字段 | 实测 |
|---|---|---|---|---|
| 普通 | 2000 | 3 | `plainStuck` | `3`（接近 `trials`，说明读被提升） |
| volatile | 2000 | 3 | `volatileStuck` | `0`（恒为 0） |

返回体里 `plainRuns` / `volatileRuns` 是每轮的明细，可以逐轮看 `spins` 和 `sawWrite`。

**耗时**：约 `2 × trials × settleMillis`，默认参数（2000ms × 3 轮 × 2 组）大约 13 秒，不是卡死。

判读：

- `plainStuck == trials` → 每一轮都卡死，读提升已经稳定生效；
- `plainStuck < trials` → 前几轮还在解释执行，之后 C2 编译完成就再也不看内存了 —— **这正是最危险的地方：本地第一次跑能过，上线跑一会儿就卡死**；
- `volatileStuck == 0` → volatile 读禁止被提升，任何轮次都能看到写入。

> 工程结论：`useVolatile=false` 在这台机器上「跑得通」不能证明代码是对的。读提升是否发生取决于 C2 的现场决策——解释执行阶段不会提升，编译后就永远不提升了。换 ARM、换 JDK、改一行循环体，问题就可能突然出现。**并发正确性只能靠 JMM 推，不能靠「跑通倒推」**。

### 2. 五种计数器

```powershell
curl "http://localhost:8201/phase1/concurrency/counter/all?threads=8&increments=200000"
curl "http://localhost:8201/phase1/concurrency/counter?strategy=LONG_ADDER&threads=8&increments=1000000"
```

对照 `PLAIN` / `SYNCHRONIZED` / `ATOMIC_LONG` / `LONG_ADDER` / `SPIN_LOCK` 的正确性与耗时：
裸 `long` 丢更新 → `synchronized` 正确但有锁竞争 → `CAS` 中等 → `LongAdder` 分段累加在高并发写场景最优（读场景反而是 `AtomicLong` 好）。

### 3. 自研自旋锁 `CasSpinLock`

看 `lock()` 的 `compareAndSet` 循环，以及每 1024 次自旋 `Thread.yield()` 的原因：
- 纯自旋会持续占 CPU，`yield` 让出时间片让持锁线程更快跑完
- 它**不可重入**——同一线程二次加锁会死锁，这正是 `ReentrantLock` 要维护 owner + holdCount 的原因

### 4. 生产者-消费者 `BoundedBuffer`

重点看 `take()` 里为什么是 `while (count == 0)` 而不是 `if`：
`await()` 被唤醒后必须重新检查条件（可能被其他线程抢走、也可能是伪唤醒），否则会读到空数据。

### 5. 虚拟线程

```powershell
curl "http://localhost:8201/phase1/concurrency/threads?tasks=2000&ioMillis=50&poolSize=200"
```

2000 个任务、每个阻塞 50ms：平台线程池 200 个 → 约 `(2000/200)*50 = 500ms`；虚拟线程 → 约 50ms 量级。
结论：**I/O 密集用虚拟线程，CPU 密集用它没有收益**。

### 6. 设计模式（每组都有"坏味道"对照实现）

```powershell
# 策略 + 工厂
curl "http://localhost:8201/phase1/patterns/pricing?strategy=VIP&quantity=2&unitPrice=100"
curl "http://localhost:8201/phase1/patterns/pricing/legacy?strategy=VIP&quantity=2&unitPrice=100"   # 金额应一致
curl "http://localhost:8201/phase1/patterns/pricing/strategies"                                    # 容器里注册了哪些策略

# 责任链
curl "http://localhost:8201/phase1/patterns/validation?quantity=2"
curl "http://localhost:8201/phase1/patterns/validation?quantity=-1"       # 触发短路
curl "http://localhost:8201/phase1/patterns/validation/chain"             # 装配顺序

# 状态机
curl "http://localhost:8201/phase1/patterns/state-machine?state=CREATED&event=PAY"
curl "http://localhost:8201/phase1/patterns/state-machine/allowed?state=CREATED"
```

看三件事：新增策略时是否只加一个 Bean（开闭原则）、校验失败时能否看到"执行到第几个节点"、非法状态迁移是否被拦截。

---

## 三、阶段二：缓存 + 中间件 + 各种锁（:8202）

默认配置 `company.lock.provider: memory`、`arch-lab.cache.remote: memory`，改成 `redis` 即切真实中间件。

### 建议阅读顺序

| # | 主题 | 包 | 接口 |
|---|---|---|---|
| 1 | 缓存穿透 | `cache/CacheLab` + `BloomFilter` | `GET /phase2/cache/penetration` |
| 2 | 缓存击穿 | `cache/CacheLab` | `GET /phase2/cache/breakdown` |
| 3 | 缓存雪崩 | `cache/CacheLab` | `GET /phase2/cache/avalanche` |
| 4 | 热点 Key / 多级缓存 | `cache/MultiLevelProductCache` | `GET /phase2/cache/hot-key` |
| 5 | 缓存一致性 | `scenario/consistency` | `GET /phase2/scenario/cache-consistency` |
| 6 | 分布式锁 | `scenario/lock` | `GET /phase2/scenario/lock-counter` |
| 7 | 限流 | `scenario/rateLimit` | `GET /phase2/scenario/rate-limit` |
| 8 | 幂等 | `scenario/idempotency` | `GET /phase2/scenario/idempotency` |
| 9 | 库存超卖 | `scenario/stock` | `GET /phase2/scenario/oversell` |

### 1~4. 缓存三大问题

```powershell
# 穿透：NAIVE 全打到 DB，BLOOM 先过滤
curl "http://localhost:8202/phase2/cache/penetration?requests=100&mode=NAIVE"
curl "http://localhost:8202/phase2/cache/penetration?requests=100&mode=BLOOM"

# 击穿：同一热点 key 失效瞬间并发回源
curl "http://localhost:8202/phase2/cache/breakdown?threads=16&loadMillis=50&mode=NAIVE"
curl "http://localhost:8202/phase2/cache/breakdown?threads=16&loadMillis=50&mode=SINGLE_FLIGHT"

# 雪崩：大量 key 同时过期
curl "http://localhost:8202/phase2/cache/avalanche?keys=50&baseTtlMillis=300&jitter=false"
curl "http://localhost:8202/phase2/cache/avalanche?keys=50&baseTtlMillis=300&jitter=true"

# 热点 key：开/关 L1 本地缓存
curl "http://localhost:8202/phase2/cache/hot-key?requests=1000&l1Enabled=false"
curl "http://localhost:8202/phase2/cache/hot-key?requests=1000&l1Enabled=true"
```

关注返回里的 **DB 回源次数**：BLOOM / SINGLE_FLIGHT / 随机 TTL / L1 各自的治理效果都体现在这个数字上。

### 5. 缓存一致性

```powershell
curl "http://localhost:8202/phase2/scenario/cache-consistency?strategy=UPDATE_DB_THEN_DELETE_CACHE&rounds=100"
curl "http://localhost:8202/phase2/scenario/cache-consistency?strategy=DELAYED_DOUBLE_DELETE&rounds=100"
curl "http://localhost:8202/phase2/scenario/cache-consistency?strategy=LOCK_PROTECTED&rounds=100"
```

看**脏读次数**：先删缓存后写库 / 先写库后删缓存 / 延迟双删 / 加锁，各自的脏窗口。

### 6. 分布式锁

```powershell
curl "http://localhost:8202/phase2/scenario/lock-counter?locked=false&threads=8&iterations=100"
curl "http://localhost:8202/phase2/scenario/lock-counter?locked=true&threads=8&iterations=100"
```

看最终计数是否等于 `threads * iterations`。切 Redis 时把 `application.yml` 的 `company.lock.provider` 改成 `redis`，观察续约（watchdog）与租约时间的影响。

### 7. 限流

```powershell
curl "http://localhost:8202/phase2/scenario/rate-limit?mode=FIXED_WINDOW&limit=10&windowMillis=1000"
curl "http://localhost:8202/phase2/scenario/rate-limit?mode=SLIDING_WINDOW&limit=10&windowMillis=1000"
```

固定窗口的**临界双倍放行**问题，在滑动窗口下消失。

### 8. 幂等

```powershell
curl "http://localhost:8202/phase2/scenario/idempotency?mode=NONE&requests=50"
curl "http://localhost:8202/phase2/scenario/idempotency?mode=TOKEN&requests=50"
curl "http://localhost:8202/phase2/scenario/idempotency?mode=STATE_MACHINE&requests=50"
```

看**重复提交被拦下的数量**：token 前置去重 vs 状态机兜底。

### 9. 库存超卖

```powershell
curl "http://localhost:8202/phase2/scenario/oversell?mode=NONE&stock=1&buyers=2&instances=2"
curl "http://localhost:8202/phase2/scenario/oversell?mode=LOCAL_LOCK&stock=1&buyers=2&instances=2"
curl "http://localhost:8202/phase2/scenario/oversell?mode=OPTIMISTIC&stock=1&buyers=2&instances=2"
```

`instances=2` 模拟多实例部署——这时**本地锁失效**，必须用分布式锁或乐观版本号，这是阶段三商城的前置知识。

---

## 四、阶段三 A：100 万 QPS 短链（:8203）

### 请求链路（按此顺序读代码）

```
发号（号段 SegmentIdGenerator / Base62）→ 布隆过滤器 → 多级缓存（L1 Caffeine + L2 Redis）
→ 回源 Repository → 302 跳转 → 点击上报（内存聚合 + 批量落库）
```

| 环节 | 类 | 位置 |
|---|---|---|
| 发号 | `SegmentIdGenerator` / `SegmentSource` / `Base62` | `domain/` |
| 布隆 | `BloomFilter` / `BloomMath` / `MemoryBloomFilter` / `RedisBloomFilter` | `domain/`、`infra/` |
| 多级缓存 | `ShortlinkCache` / `MemoryRemoteCache` / `RedisRemoteCache` | `infra/` |
| 跳转 | `RedirectController` | `web/` |
| 管理 | `ShortlinkController` | `web/` |
| 点击上报 | `ClickRecorder` / `ClickStore` | `service/`、`infra/` |
| 预热 | `BloomWarmUp` + `shortlink-prefetch` 线程池 | `support/` |
| 容量模型 | `CapacityModel` | `capacity/` |

### 常用接口

```powershell
# 建链
curl -X POST "http://localhost:8203/shortlink/links" -H "Content-Type: application/json" `
  -d '{"url":"https://example.com/very/long/path","expireAt":null}'

# 查链（短码固定 7 位 Base62）
curl "http://localhost:8203/shortlink/links/AbC1234"

# 跳转：302，且 Cache-Control: no-store
curl -i "http://localhost:8203/shortlink/AbC1234"

# 点击量 / 手动刷增量 / 运行统计
curl "http://localhost:8203/shortlink/links/AbC1234/clicks"
curl -X POST "http://localhost:8203/shortlink/links/clicks/flush"
curl "http://localhost:8203/shortlink/links/stats"

# 容量模型：默认就是 100 万 QPS 场景
curl "http://localhost:8203/shortlink/links/capacity"
curl "http://localhost:8203/shortlink/links/capacity?l1HitRatio=0.95&l2HitRatioOfL1Miss=0.9"
```

**为什么是 302 而不是 301**：301 会被浏览器长期缓存，后续点击不再到服务端，点击统计失真，且删链/改指向收不回来。

**容量模型怎么读**：调 `l1HitRatio` 从 0.95 降到 0.90，看 Redis 与 DB 层的实际 QPS 增长多少——这是 100 万 QPS 设计里最敏感的一个参数。

---

## 五、阶段三 B：商城 + 分布式事务（:8204）

### 下单链路（按此顺序读代码）

```
建商品 → 下单（库存锁：等待 3s / 租约 10s）→ 订单落库 → 写入 outbox
→ relay 投递 MQ（memory 可切）→ 支付 → 通知（最多 6 次）→ 支付超时关单（30 分钟）
```

| 环节 | 类 |
|---|---|
| 商品 | `service/ProductService` |
| 下单 / 支付 / 取消 / 关单 | `service/OrderService` |
| 事件投影（读写分离观察） | `service/OrderEventProjector` |
| Outbox 投递 | `service/OutboxRelay` |
| 支付通知重试 | `service/PaymentNotifier` |
| 统计 | `service/MallStatsService` |

### 常用接口

```powershell
# 建商品
curl -X POST "http://localhost:8204/mall/products" -H "Content-Type: application/json" `
  -d '{"name":"demo","priceCents":1999,"stock":10}'

# 下单（requestId 是幂等键，重试必须复用同一个值）
curl -X POST "http://localhost:8204/mall/orders" -H "Content-Type: application/json" `
  -d '{"requestId":"req-1","userId":1,"lines":[{"productId":1,"quantity":2}]}'

# 查订单：同时返回写端状态与投影状态，观察最终一致性收敛
curl "http://localhost:8204/mall/orders/{orderNo}"

# 支付 / 取消
curl -X POST "http://localhost:8204/mall/orders/{orderNo}/pay"
curl -X POST "http://localhost:8204/mall/orders/{orderNo}/cancel?reason=user"

# 运维触发点（线上由定时任务驱动，这里手动推一轮）
curl -X POST "http://localhost:8204/mall/ops/outbox/relay"
curl -X POST "http://localhost:8204/mall/ops/notify"
curl -X POST "http://localhost:8204/mall/ops/orders/close-expired"

curl "http://localhost:8204/mall/stats"
```

### 分布式事务对照实验

```powershell
# 单一机制：failAt=2 表示第 2 步失败，-1 表示全成功
curl "http://localhost:8204/mall/distributed-tx?mechanism=AT&failAt=2"
curl "http://localhost:8204/mall/distributed-tx?mechanism=TCC&failAt=2"
curl "http://localhost:8204/mall/distributed-tx?mechanism=SAGA&failAt=2"

# 三种机制同一场景横向对比
curl "http://localhost:8204/mall/distributed-tx/compare?failAt=2"
curl "http://localhost:8204/mall/distributed-tx/compare?failAt=-1"
```

看三种机制的**一致性代价**差异：

| 机制 | 补偿方式 | 一致性 | 业务侵入 |
|---|---|---|---|
| AT | undo log 自动回滚 | 强（局部） | 低 |
| TCC | Cancel 归还冻结份额 | 最终一致 | 高（要写三段） |
| Saga | 反向补偿事务 | 最终一致 | 中（要写补偿） |

---

## 六、按阶段推进的检查清单

- [ ] **阶段一**
  - [ ] 能说清 `volatile` 为什么不能保证 `value++`
  - [ ] 能说清 `BoundedBuffer.take()` 为什么必须 `while`
  - [ ] 能说清 `CasSpinLock` 为什么不可重入、为什么要 `yield`
  - [ ] 能说清虚拟线程适合 I/O 密集而非 CPU 密集
  - [ ] 能说出策略/责任链/状态机各自解决什么坏味道
- [ ] **阶段二**
  - [ ] 穿透 / 击穿 / 雪崩三者的触发条件与治理手段各不同
  - [ ] 缓存一致性四种策略的脏窗口
  - [ ] 固定窗口 vs 滑动窗口的临界问题
  - [ ] 本地锁在多实例下的失效场景
- [ ] **阶段三**
  - [ ] 100 万 QPS 下每层缓存命中率的压力传导
  - [ ] 302 vs 301 的取舍
  - [ ] outbox + 幂等键如何保证"不丢不重"
  - [ ] AT / TCC / Saga 的补偿语义差异

---

## 七、常见问题

**Q：不想手写 curl，怎么直接调试？**
每个服务都接了 Knife4j（基于 springdoc），地址见文首「模块总览」。左侧树形菜单列出全部接口，中文界面，参数带默认值，点 **调试** 就能直接发请求并看到响应。

OpenAPI JSON 在 `<服务地址>/v3/api-docs`，可以直接导入 Postman / Apifox：`Import → Link →` 粘贴该地址即可，不用一个个手动建。

**Q：为什么四个服务不用 Nacos？**
学习实验只需要"能单独跑起来"，注册与配置中心会引入额外依赖。需要时把依赖和 `application.yml` 里的 Nacos 段落加回来即可。

**Q：想切真实中间件怎么办？**
都是配置开关：
- 阶段二：`company.lock.provider: redis`、`arch-lab.cache.remote: redis`
- 阶段三短链：`application.yml` 里 segment / cache / bloom / click 四个 memory 开关
- 阶段三商城：`company.mall.outbox.transport: mq`

**Q：JMH 基准怎么跑？**

```powershell
mvn -o -pl arch-learning/arch-learning-benchmark -am package
java -jar arch-learning/arch-learning-benchmark/target/arch-learning-benchmarks.jar
```




