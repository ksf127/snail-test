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
| 2 | JMM 有序性：内存屏障 | `concurrency/OrderingDemo` | `GET /phase1/concurrency/ordering`、`/ordering/compare` |
| 3 | 原子性：五种计数器 | `concurrency/CounterComparison` | `GET /phase1/concurrency/counter`、`/counter/all` |
| 4 | 自研 CAS 自旋锁 | `concurrency/CasSpinLock` | 单元测试 `CasSpinLockTest` |
| 5 | 生产者-消费者 | `concurrency/BoundedBuffer` | 单元测试 `BoundedBufferTest` |
| 6 | 虚拟线程 vs 平台线程 | `concurrency/VirtualThreadComparison` | `GET /phase1/concurrency/threads` |
| 7 | 策略 + 工厂 | `pattern/pricing/*` | `GET /phase1/patterns/pricing` |
| 8 | 责任链 | `pattern/validation/*` | `GET /phase1/patterns/validation` |
| 9 | 状态机 | `pattern/statemachine/*` | `GET /phase1/patterns/state-machine` |

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
# A组（普通字段）：读线程一路自旋到本轮上限，写入从未可见
curl "http://localhost:8201/phase1/concurrency/visibility/hoisting?useVolatile=false&settleMillis=500"
# B组（volatile 字段）：主线程一置位它立刻退出
curl "http://localhost:8201/phase1/concurrency/visibility/hoisting?useVolatile=true&settleMillis=500"
```

区别只在于**循环体够不够干净**。这个实验把循环压到只剩一次字段读：

```java
long spins = 0L;
while (!holder.stop && spins < limit) { spins++; }
```

条件里只有一次字段读、循环体只有一次自增，C2 能证明「循环期间没人写这个字段」，于是把读挪到循环外只做一次，循环退化成 `while (spins < limit)` —— 这就是 **loop-invariant code motion（读提升）**。

| 参数 | 返回 `sawWrite` | `spins` | 含义 |
|---|---|---|---|
| `useVolatile=false` | `false` | 等于 `spinLimit` | 读被提出循环，写入永远看不到 |
| `useVolatile=true` | `true` | 远小于 `spinLimit`（本机实测约 1.5~4.5 亿） | volatile 读禁止提升，立刻看到 |

**`spinLimit` 不是常量，是每轮按本机实测速度现算的**：先用与实验完全相同的循环空转 5000 万次，量出每毫秒能自旋多少次，再乘 `settleMillis + 1000ms`。原因很实在——写死过 20 亿，本机自旋约 15 亿次/秒，1.3 秒就跑满退出，比 `settleMillis=2000` 还短，置位发生在它结束之后，两组都报「没看到写入」，实验直接失效（这就是「volatile 没体现出来」的根因）。

本机实测日志（`settleMillis=300`）：

```
INFO  【读提升实验】单次调用｜A组 普通字段(无volatile)｜开始：读线程执行 while(!stop && n<limit){n++}，300ms 后置位；本轮上限 3120139100 次
INFO  【读提升实验】单次调用｜A组 普通字段(无volatile)｜现在置位 stop=true
WARN  【读提升实验】单次调用｜A组 普通字段(无volatile)｜结果：跑满上限 3120139100 次仍未看到写入，耗时 1280ms → 读被提出循环
INFO  【读提升实验】单次调用｜B组 volatile 字段｜结果：看到写入，自旋 448657158 次（上限 3479862100），耗时 312ms → 读没有被提出循环
```

这就是**一个数量级、且方向确定**的差异 —— 一眼能分辨，不是噪声。

为什么 `spins` 要设上限：没有上限的话普通字段那次会永远转下去，把 Tomcat 请求线程彻底挂死。加上限后「没看到写入」表现为「跑满上限」，照样能判定，但不会拖垮服务。

#### 正式对比：`/visibility/hoisting/compare`

单跑一次的结果取决于 C2 当时有没有编译完，可能「碰巧通过」。要看规律必须数频次：

```powershell
curl "http://localhost:8201/phase1/concurrency/visibility/hoisting/compare?settleMillis=2000&trials=3"
```

同一套循环，普通字段（A组）和 volatile 字段（B组）各跑 `trials` 轮，**交替执行**（否则先跑的那组会独占 JIT 预热，条件不对等），最后统计各自「跑满上限才退出」的次数：

| 组 | 字段 | `settleMillis` | `trials` | 返回字段 | 实测 |
|---|---|---|---|---|---|
| A组 | 普通（无 volatile） | 2000 | 3 | `plainStuck` | `3`（说明读被提升） |
| B组 | volatile | 2000 | 3 | `volatileStuck` | `0`（恒为 0） |

返回体里 `plainRuns` / `volatileRuns` 是每轮的明细，可以逐轮看 `spins` 和 `sawWrite`。

**耗时**：A组一旦读提升就要跑满上限（`settleMillis + 1s`），B组置位后立刻退出。最坏约 `trials × (2 × settleMillis + 1s)`，默认参数（2000ms × 3 轮）大约 15 秒，不是卡死。

判读：

- `plainStuck == trials` → 每一轮都卡死，读提升已经稳定生效；
- `plainStuck < trials` → 前几轮还在解释执行，之后 C2 编译完成就再也不看内存了 —— **这正是最危险的地方：本地第一次跑能过，上线跑一会儿就卡死**；
- `volatileStuck == 0` → volatile 读禁止被提升，任何轮次都能看到写入；
- `volatileStuck > 0` → 说明本轮上限没有晚于置位时刻到达，结果不可信，日志会直接提示重跑。

> 工程结论：`useVolatile=false` 在这台机器上「跑得通」不能证明代码是对的。读提升是否发生取决于 C2 的现场决策——解释执行阶段不会提升，编译后就永远不提升了。换 ARM、换 JDK、改一行循环体，问题就可能突然出现。**并发正确性只能靠 JMM 推，不能靠「跑通倒推」**。

### 2. 有序性：volatile 的内存屏障

可见性回答「什么时候能看到」，有序性回答「指令能不能换顺序」。volatile 的第二个语义就是**禁止特定重排序**，靠的是编译器在字节码层面插入的四类内存屏障：

| 屏障 | 插在哪 | 禁止的重排 |
|---|---|---|
| StoreStore | volatile **写**之前 | 前面的普通写被挪到 volatile 写之后 |
| StoreLoad | volatile **写**之后 | volatile 写与后面的读互换（x86 唯一允许的重排，也是最坑的） |
| LoadLoad | volatile **读**之后 | 后面的普通读被挪到 volatile 读之前 |
| LoadStore | volatile **读**之后 | 后面的普通写被挪到 volatile 读之前 |

```powershell
# A组：普通字段 → 大量重排
curl "http://localhost:8201/phase1/concurrency/ordering?useVolatile=false&rounds=200000"
# B组：volatile 字段 → 重排恒为 0
curl "http://localhost:8201/phase1/concurrency/ordering?useVolatile=true&rounds=200000"
# 正式对照：两组交替各跑 3 轮，直接看汇总
curl "http://localhost:8201/phase1/concurrency/ordering/compare?rounds=200000&trials=3"
```

**实验长什么样**（经典 Dekker 对拍，每一轮由 Phaser 卡同一时刻起跑）：

```java
// 线程 1
flag1 = true;              // 写
boolean r1 = flag2;        // 读对方的标志位
// 线程 2
flag2 = true;
boolean r2 = flag1;
```

程序顺序上「两个写」都在「两个读」之前。如果 `r1 == false && r2 == false`——两人都没看到对方刚写的值——程序顺序不可能产生这个结果，只能是**写被重排到了读之后**（x86 的 store buffer 允许 Store→Load 重排）。

| 组 | 字段 | `rounds` | 实测重排次数 | 占比 |
|---|---|---|---|---|
| A组 | 普通（无 volatile） | 200000 | 约 5.7 万 | 25%~35% |
| B组 | volatile | 200000 | 0 | 0% |

本机实测日志：

```
WARN 【有序性实验】A组 普通字段(无volatile)｜结果：20000 次对拍里重排 5713 次（28.565%），耗时 58ms → 写没有先落到内存，后面的读先执行了
INFO 【有序性实验】B组 volatile 字段｜结果：20000 次对拍里重排 0 次，耗时 18ms
INFO 【有序性对照实验】A组 普通字段(无volatile)：重排 2282 次，占比 22.820%
INFO 【有序性对照实验】B组 volatile 字段          ：重排 0 次，占比 0.000%
INFO 【有序性对照实验】判读：A组出现了重排，B组一次都没有 —— 这就是 volatile 写之后的 StoreLoad 屏障在起作用
```

**耗时**：每轮对拍约 1µs，20 万轮约 0.2 秒；对照接口默认参数（20 万 × 3 轮 × 2 组）约 1 秒。

判读：

- A组重排次数 **> 0** → 普通字段挡不住 StoreLoad 重排。这就是 `if (instance == null)` 双重检查、Dekker 互斥这类代码不加 volatile 会悄悄出错的根因：**它不会每次都错，但每次都可能错**；
- B组恒为 0 → volatile 写之后的 StoreLoad 屏障强制先刷出去再读；
- A组偶发 0 是概率问题（窗口没撞上），加大 `rounds` 即可，不代表代码安全。

> 工程结论：volatile = 可见性 + 有序性，**但不保证原子性**。所以它救不了 `count++`，却足以让「一个线程写标记、另一个线程根据标记读数据」这种发布-订阅模型成立 —— 前提是写标记用 volatile，且数据写在标记之前。

### 3. 五种计数器

```powershell
curl "http://localhost:8201/phase1/concurrency/counter/all?threads=8&increments=200000"
curl "http://localhost:8201/phase1/concurrency/counter?strategy=LONG_ADDER&threads=8&increments=1000000"
```

对照 `PLAIN` / `SYNCHRONIZED` / `ATOMIC_LONG` / `LONG_ADDER` / `SPIN_LOCK` 的正确性与耗时：
裸 `long` 丢更新 → `synchronized` 正确但有锁竞争 → `CAS` 中等 → `LongAdder` 分段累加在高并发写场景最优（读场景反而是 `AtomicLong` 好）。

`/counter/all` 的日志是「测什么 / 谁和谁对照 / 判读」三段，最后一行直接把五个策略按耗时排好：

```
INFO 【计数器对比】测什么：同一个 count++ 问题，五种同步手段的正确性与吞吐；谁和谁对照：PLAIN（对照组，必然丢更新）vs 其余四种（正确性应全为 true）；参数：线程数=8，每线程自增=200000次
WARN 【计数器对比】结果不一致，策略=PLAIN，期望=1600000，实际=1042371，丢失=557629 次更新。原因是没有互斥或原子性保证
INFO 【计数器对比】判读：存在丢更新（符合 PLAIN 预期），耗时从快到慢 [PLAIN=12ms, LONG_ADDER=38ms, ATOMIC_LONG=41ms, SPIN_LOCK=95ms, SYNCHRONIZED=132ms]
```

判读只看两点：**`PLAIN` 必须丢更新**（否则说明压测没压起来），**其余四种必须 `实际 == 期望`**（有一个不等就是实现写错了，不是性能问题）。

### 4. 自研自旋锁 `CasSpinLock`

看 `lock()` 的 `compareAndSet` 循环，以及每 1024 次自旋 `Thread.yield()` 的原因：
- 纯自旋会持续占 CPU，`yield` 让出时间片让持锁线程更快跑完
- 它**不可重入**——同一线程二次加锁会死锁，这正是 `ReentrantLock` 要维护 owner + holdCount 的原因

### 5. 生产者-消费者 `BoundedBuffer`

重点看 `take()` 里为什么是 `while (count == 0)` 而不是 `if`：
`await()` 被唤醒后必须重新检查条件（可能被其他线程抢走、也可能是伪唤醒），否则会读到空数据。

### 6. 虚拟线程

```powershell
curl "http://localhost:8201/phase1/concurrency/threads?tasks=2000&ioMillis=50&poolSize=200"
```

2000 个任务、每个阻塞 50ms：平台线程池 200 个 → 约 `(2000/200)*50 = 500ms`；虚拟线程 → 约 50ms 量级。
结论：**I/O 密集用虚拟线程，CPU 密集用它没有收益**。

日志里直接给了理论下限和结论，不用自己心算：

```
INFO 【线程模型对比】测什么：同一批阻塞型任务在两种调度模型下的墙钟耗时；谁和谁对照：A组 平台线程池(容量200，超出部分只能排队) vs B组 虚拟线程(每任务一个线程)；参数：任务数=2000，单任务阻塞=50ms
INFO 【线程模型对比】判读：平台组 511ms（理论下限约 500ms，受并发度 200 限制），虚拟线程组 63ms（接近单次阻塞 50ms），加速比 8.11x → 阻塞型负载下虚拟线程优势明显
```

加速比低于 2 通常不是虚拟线程不行，而是 `tasks / poolSize` 太小（排队深度不够）或机器核数已成瓶颈。

### 7. 设计模式（每组都有"坏味道"对照实现）

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

两个版本的日志要**并排看**，才知道模式换来了什么：

```
# 策略模式版
INFO 【计价】策略=VIP，数量=2，单价=100，小计=200，应付=190.00
# Legacy 版：金额必须一致，但日志是拼在 if 分支里的
INFO 【Legacy 计价】策略=VIP，小计=200，应付=190.00

# 责任链版：能看到「执行到第几个节点」
WARN 【校验链】节点 数量校验 校验失败，已执行节点=[用户状态校验]，原因=购买数量必须大于 0
# Legacy 版：只能看到挂了，看不到前面跑过什么
WARN 【Legacy 校验】规则「数量在 (0, 100]」不通过，原因=购买数量不合法
```

同参下两边**金额/结果必须一致**；不一致说明两条实现已经漂移，比"哪个更快"严重得多。

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

**日志怎么看**：每个 Lab 的日志都是「测什么 / 谁和谁对照 → 结果 → 判读」三段，先看第一段确认自己跑的是哪一组，再看判读确认结论对不对：

```
INFO 【穿透实验】测什么：用数据库里不存在的 id=999999 连续查 100 次，看缓存层能不能挡住穿透流量；谁和谁对照：A组 NAIVE（不防护）vs B组 NULL_CACHE（空值哨兵）vs C组 BLOOM（位图前置拦截）；本次跑 BLOOM（C组 布隆前置拦截），TTL=60s
INFO 【穿透实验】C组 布隆前置拦截｜结果：请求 100 次，回源 DB 0 次，布隆拦截 100 次，耗时 0ms
INFO 【穿透实验】判读：C组布隆拦截 100 次、回源 0 次；位图判断「不存在」是确定性的，不会误杀真实数据，代价是极低误判率与位图维护成本
```

判读口径：A 组回源次数应**等于**请求数，B 组应**只有 1 次**（首次回源后写空值哨兵），C 组应为 **0 次**。哪一组不符合，先看该组日志里的参数，而不是先怀疑缓存实现。

### 5. 缓存一致性

```powershell
curl "http://localhost:8202/phase2/scenario/cache-consistency?strategy=UPDATE_DB_THEN_DELETE_CACHE&rounds=100"
curl "http://localhost:8202/phase2/scenario/cache-consistency?strategy=DELAYED_DOUBLE_DELETE&rounds=100"
curl "http://localhost:8202/phase2/scenario/cache-consistency?strategy=LOCK_PROTECTED&rounds=100"
```

看**脏读次数**：先删缓存后写库 / 先写库后删缓存 / 延迟双删 / 加锁，各自的脏窗口。

日志会先把四种写路径的对照关系写清楚，再按本次 strategy 给逐轮判读：

```
INFO 【缓存一致性实验】测什么：同一条「读缓存→回源→回填」读路径配上不同写路径，看哪一组会在缓存里留下长期脏数据；谁和谁对照：A组 先更库后删缓存 / B组 先删缓存后更库 / C组 延迟双删 / D组 读写共用 key 锁；本次跑 DELAYED_DOUBLE_DELETE（延迟双删），rounds=100
INFO 【缓存一致性实验】本次策略（延迟双删）的机制：写后删一次，再延迟删一次，覆盖读线程已经拿到旧值但还没回填的窗口
INFO 【缓存一致性实验】判读：延迟双删 组 100 轮结束后缓存与数据库一致，脏轮数 0/100
```

判读口径：**只有 `LOCK_PROTECTED` 能保证脏读恒为 0**；其余三种都是「把脏窗口压小」，跑 100 轮碰巧 0 次不代表正确。

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

**日志怎么看**：这个模块的三条关键日志，分别对应「预热」「刷盘」「容量」三处容易看错的地方。

```
# 1. 布隆预热：预热前位图是空的，存量短码全判「一定不存在」
INFO 【短链-布隆预热】预热前：本次写入 0 条，位数组 8388608 bit（约 1024 KB），占用 0.0%，预计误判率 0.0%；内存版此时位图全空，存量短码跳转会全部 404
INFO 【短链-布隆预热】预热后：写入 10000 条，位数组 8388608 bit（约 1024 KB），占用 11.4%，预计误判率 0.01%；耗时 18 ms
INFO 【短链-布隆预热】判读：预热后无效 key 会在布隆层被拦下，不打到缓存和 DB；10000 条只占位图 11.4%，预计每 1 万次随机查询约 1 次假阳性穿透到缓存
# 2. 点击刷盘：刷盘前缓冲量 → 落库量 → 未落盘量
INFO 【短链-点击刷盘】刷盘前缓冲 3210 次（178 个短码有增量）→ 刷盘后落库 3210 次，未落盘 0 次
INFO 【短链-点击刷盘】判读：正常未落盘应为 0；进程被 kill -9 时最多丢一个刷新周期的计数，点击统计允许这点误差，换来的是主链路零 IO
# 3. 容量判读：不只看入参，还看算出来的节点数与利用率
INFO 【短链-管理】容量判读：目标 QPS=1000000，需要 8 个节点（分片），单机 200000 QPS、平均每节点 125000 QPS；L1 接住 900000，L2 承担 90000（Redis 利用率 0.45），存储承担 10000（存储利用率 0.10）→ 两层均在 70% 安全水位内，当前配置够用
```

判读要点：布隆预热**没跑完就对外服务**等于所有存量短码 404；刷盘日志的「未落盘」非 0 只可能是进程被杀，不是代码 bug；容量判读的 `feasible=false` 或出现 `【短链-管理】容量告警` 时，先加节点再看利用率。

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

失败路径的日志按「测什么 / 谁和谁对照 / 判读」写成三行，不用对照返回体也能看懂：

```
INFO 【分布式事务实验】测什么：同一笔 failAt=2 的业务在 AT / TCC / Saga 三种机制下的终态与回滚代价
INFO 【分布式事务实验】谁和谁对照：A组 AT（undo log 回滚）、B组 TCC（Cancel 释放冻结）、C组 Saga（反向补偿）；三组跑同一 failAt=2
WARN 【AT】分支失败，触发全局回滚: xid=AT:order:1:1, 已执行 2 个分支, error=模拟第 3 个分支失败
INFO 【分布式事务实验】结束: mechanism=AT, completed=false, consistent=true, failure=模拟第 3 个分支失败；判读：失败已完整回滚：库存与余额回到初始值、订单不存在、无冻结残留
INFO 【分布式事务实验】横向对比完成: failAt=2, 结果=[AT[completed=false, consistent=true, stock=100, balance=10000, order=false], TCC[...], SAGA[...]]
```

判读只看 `consistent`：三种机制在**失败后都必须为 true**。若某个机制 `consistent=false`，说明它的补偿没覆盖住 `failAt` 那一步——这正是 TCC 要写空回滚/悬挂判断、Saga 要写反向补偿的原因。

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

---

## 八、代码注释与日志标准

四个阶段服务统一按这一套写，新加实验照着抄即可（标杆文件：`concurrency/VisibilityDemo`、`concurrency/OrderingDemo`）。

### 1. 类注释：三段式

必须有且按这个顺序，回答三个问题，不写「这个类是什么」这种废话：

| 段落 | 回答的问题 | 写法要求 |
|---|---|---|
| `【解决什么问题】` | 真实业务里不这么做会出什么事 | 举具体故障现象，不写「为了解耦」这种空话 |
| `【为什么这样设计】` | 为什么是这个结构，不是别的 | 说出被否掉的方案和否掉的原因 |
| `【怎么读这段代码】` | 从哪进、按什么顺序看 | 点名方法/端点，给出阅读路径 |

### 2. 方法注释

- 公开方法写 `@param` / `@return` / `@throws`；
- 有并发语义的方法额外写一行「并发安全」：可否重入、共享了什么状态；
- 复杂算法写 `@implNote`，说明边界（比如「上限必须晚于置位时刻」）。

### 3. 行内注释：只解释「为什么」

- 不写 `// i++` 这种复述代码的注释；
- 写「不这么写会怎样」，例如「日志必须在取时间戳之前，否则 IO 会把延迟从微秒放大到百微秒」；
- 循环体内、自旋体内**禁止打日志**：IO 带内存屏障副作用，会把实验结论改掉。

### 4. 日志规范

- 只用 slf4j 手写 `LoggerFactory`，**禁止 `System.out`、禁止 lombok `@Slf4j`**；
- 前缀统一 `【场景名】`，同一实验的每条日志前缀必须一致；
- 用 `{}` 占位符，不拼字符串；
- 对照实验（A/B 两组）必须打三类日志：

```
INFO 【XX对照实验】测什么：<一句话说清被验证的机制>
INFO 【XX对照实验】谁和谁对照：A组 = <实现>，B组 = <实现>；两组交替各 N 轮
INFO 【XX实验】第 1/3 轮 A组｜结果：<数字 + 占比 + 耗时>。判读：<这组数字说明什么>
...
INFO 【XX对照实验】A组 ...：<汇总数字>
INFO 【XX对照实验】B组 ...：<汇总数字>
INFO 【XX对照实验】判读：<结论>
```

- 业务链路（短链、商城）不用 A/B 模板，但要打「入参 → 关键分支 → 结果」，能用数字就别用形容词；
- 概率实验必须报**频次**（`n/N`）而不是单次结果，并说明「参数越大越容易撞上窗口」。

### 5. 反例清单（评审时按这个抓）

| 反例 | 为什么不行 |
|---|---|
| 只打「开始 / 结束」两个词 | 看不出测什么、谁和谁比 |
| 两组分别跑、日志各写各的 | 看日志的人要自己心算对照关系 |
| 只在出错时 `warn` | 对照组缺失时没有任何正向判读 |
| 用固定常量当实验上限 | 换台机器就失效，实验可能变成「两组都没测到」 |
| 自旋/热路径里打日志 | IO 屏障直接改变实验结果 |




