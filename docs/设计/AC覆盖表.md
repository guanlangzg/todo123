# AC-01–AC-20 覆盖表

> 状态：v2.0，2026-10-05。依据已放行规格第 11 节逐条映射，并按 v2.0《架构契约》修正 F1（门禁命令）、F2（完成态单一真相）、F8（账本编辑语义）三处会导致"照表执行必红"的问题。
>
> **本表是分工与执行方式计划，不是执行结果**：所列 AC 检查在本轮**全部未运行**（本轮只产出契约文档，产品工程尚不存在）。
>
> 三类归属定义：
> - **自动化**：无设备条件下由 `cargo test` / `gradlew.bat testDebugUnitTest` 完成。
> - **设备**：必须有 Android 设备或模拟器（本机当前**阻塞**，见《架构契约》E14/E15）。
> - **人工/视觉**：必须由人评价层次、可读性或无障碍，不能由断言替代。

## 1. 总表

| AC | 主路径 | 自动化 | 设备 | 人工/视觉 | 主承担模块 | 协作 |
| --- | --- | --- | --- | --- | --- | --- |
| AC-01 新日日常实例 | 惰性生成 + 幂等 | ✅ 必做 | — | 空/满态视觉 | `rust-domain` | `data-room` |
| AC-02 组内排序与分组 | 排序稳定、跨组拒绝 | ✅ 必做 | 重启后顺序 | 拖动反馈 | `data-room` | `rust-domain`、`ui-compose` |
| AC-03 完成折叠区与撤销 | 撤销只影响今天（**事件重放唯一真相**） | ✅ 必做 | — | 折叠/撤销动效 | `rust-domain` | `data-room`、`ui-compose` |
| AC-04 模式选择不启动计时 | 取消不覆盖分钟 | ✅ 必做 | 旋转后状态 | 输入错误提示 | `rust-domain` | `ui-compose` |
| AC-05 暂停不累计 | 10+3+5=15 分钟 | ✅ 必做（核心） | 前后台/锁屏 | — | `rust-domain` | `android-system` |
| AC-06 单活动会话与切换 | 原子切换 + 前置条件裁决 | ✅ 必做（核心） | — | 确认文案明确 | `rust-domain` | `data-room`、`ui-compose` |
| AC-07 提前结束/到点/主动完成 | 到点不自动完成（**读派生视图**） | ✅ 必做 | 到点提醒、震动 | 结果页两选项 | `rust-domain` | `android-system`、`ui-compose` |
| AC-08 跨午夜拆分与完成日选择 | 10/20 分钟拆分 + 切片日长同源 | ✅ 必做 | 真实跨午夜 | 昨天/今天单选 | `rust-domain` | `ui-compose` |
| AC-09 异常中断恢复 | 空档不入账 + 恢复幂等 + **不误判** | 部分（Rust 纯算） | ✅ **必须真机注入** | 接受/修正/舍弃界面 | `android-system` | `rust-domain`、`ui-compose` |
| AC-10 归档期与重新启用 | 不造漏做、同日复用 | ✅ 必做 | — | 归档状态区分 | `rust-domain` | `data-room` |
| AC-11 临时重开与再完成 | 事件重放 | ✅ 必做 | — | 历史可追溯呈现 | `rust-domain` | `data-room` |
| AC-12 账本顺序重放 | S1：20→50→**35**→45；**两模式合并单一总量** | ✅ 必做（核心） | — | 前后预览 | `rust-domain` | `data-room`、`ui-compose` |
| AC-13 跨日贡献编辑/删除 | S2=45、S3=80、**S5=60**（含 kind=2 编辑） | ✅ 必做（核心） | — | 影响范围提示 | `rust-domain` | `data-room` |
| AC-14 改名与标题切片 | 旧段旧名、ID 不拆 | ✅ 必做 | — | 权威历史明细 | `rust-domain` | `data-room`、`ui-compose` |
| AC-15 时区与 DST | 历史不重算、23/25 小时、app_date 一致 | ✅ 必做（核心） | 系统时区切换 | 变更预告与影响摘要 | `rust-domain` | `android-system`、`ui-compose` |
| AC-16 备份导出/恢复失败安全 | 错误密码/损坏不改数据 | ✅ 部分（Robolectric 文件库 + 崩溃恢复状态机） | ✅ SAF 选路径 | 覆盖范围警示 | `backup-crypto` | `data-room`、`android-system` |
| AC-17 通知/勿扰/锁屏/强制停止 | 到点不自动完成（Rust 侧） | **未验证**（Robolectric 能否断言通知未实测） | ✅ 必做 | 文案不承诺必定响铃 | `android-system` | `ui-compose`、`rust-domain` |
| AC-18 空态/长文本/大字体/离线 | — | **未验证**（Compose 测试基础设施未搭建） | — | ✅ 必做 | `ui-compose` | `ui-navigation` |
| AC-19 同签名升级保数据 | — | — | ✅ 必做（隔离环境、同包名同签名） | 升级后核对账本 | `engine-build` | `data-room` |
| AC-20 真机性能 | — | — | ✅ 必做（真实中端机 + Macrobenchmark） | ✅ 必做 | `engine-build` | `ui-compose` |

**统计**：完全可自动化 11 条；必须设备 6 条（AC-09、AC-16、AC-17、AC-19、AC-20，及 AC-05/AC-08/AC-15 设备侧补充）；必须人工/视觉 4 条。**没有任何一条 AC 能只靠自动化判定通过。**

## 2. 逐条执行方式与命令

> `<root>` = `D:\PROJECT_ZZZZZZZZZ\todo1010`。Rust 用例置于 `rust/tests/*.rs`（**单 crate 布局**，见《工程布局与版本锁定》§1），属性测试用 `proptest`/`quickcheck` 择一。

### 自动化条目

**AC-01 / AC-10 / AC-15 —— 日常实例生成、归档、时区**
- 断言：同 `(task_id, app_date)` 连续 `EnsureOccurrence` 三次仅一行；归档期不生成；重新启用同日复用 `occurrence_id`；系统时区改变**不新增** `zone_epoch`；跨纪元同标签求和。
- 命令：`cargo test --manifest-path rust/Cargo.toml --test occurrences`
- DST 与日长：`cargo test --manifest-path rust/Cargo.toml --test day_length`（E9/E10 已证 `jiff` 可算 23/25 小时与日窗口）

**AC-02 —— 排序稳定**
- 断言：A/B/C 重排为 C/A/B 后顺序一致；把临时任务插入日常组返回错误且顺序不变。
- 命令：`cargo test --manifest-path rust/Cargo.toml --test ordering` + `./gradlew.bat testDebugUnitTest`（`sort_key` 持久化）

**AC-03 / AC-11 —— 完成折叠、撤销、临时重开（F2 修正）**
- 断言：撤销后 `daily_occurrence_view.is_completed=0`，且 `focus_session`/`ledger_entry` 行数与秒数**不变**；临时重开追加 `action=1` 事件、`action=0` 保留。
- **必须新增等价性断言**：`RebuildOccurrenceView` 的结果与增量维护的视图逐字段相等（这是 AC-03 与 AC-07 不再打架的保证，见《架构契约》§4.1）。
- 命令：`cargo test --manifest-path rust/Cargo.toml --test occurrence_events`

**AC-04 —— 取消不覆盖最近分钟**
- 断言：`SetTaskRecentCountdown(35)` 在点"开始"前**不被调用**；取消路径零命令；新任务默认 25。
- 命令：`./gradlew.bat testDebugUnitTest`（`DomainBridge` 用 fake）

**AC-05 —— 暂停不累计（核心）**
- 断言：600 秒 + 暂停 180 秒 + 300 秒 → 重放得 900 秒；暂停区间不产生 `session_segment`。
- 命令：`cargo test --manifest-path rust/Cargo.toml --test session_pause`
- 属性测试：随机段序列下"有效秒数 = 各运行段之和，且恒 ≤ 墙钟跨度"。

**AC-06 —— 单活动会话、原子切换与前置条件（核心，F6 修正）**
- 断言：已有活动会话时 `StartSession` → `ConcurrentSession`；并发两条同 `expected_revision` 命令时，后到者**不得无条件重放**，前置条件不通过须返回 `PreconditionFailed`；确认切换后旧会话 `Finished` 且 `active_session_slot` 仅一行。
- 命令：`cargo test --manifest-path rust/Cargo.toml --test single_session` + `./gradlew.bat testDebugUnitTest`（Robolectric 文件库事务断言，E13 已证可跑）

**AC-07 —— 到点不自动完成（F2 修正）**
- 断言：倒计时到点 → 产生 `FinishSession` 效果、**不产生** `occurrence_completion_event`；`daily_occurrence_view.is_completed` 保持 0；提前结束保存的实际秒数小于目标。
- **读点统一**：与 AC-03 读同一处（派生视图 == 事件重放）。
- 命令：`cargo test --manifest-path rust/Cargo.toml --test countdown`

**AC-08 —— 跨午夜拆分（核心，F7 + N3 修正）**
- 断言：23:50–00:20 → 前日 600 秒、次日 1200 秒、合计 1800；**同一 `wall_ms` 经 `AppDateOf` 与经 `SliceInterval` 归属一致（归属以 `DayWindow` 区间为准）**；`DayWindow(d).day_seconds` == `SliceInterval` 在该日切出的秒数和。
- **N3 新增必测（午夜发生 DST 跳变）**：在 `America/Sao_Paulo 2018-11-04`（该日无 `00:00`，实测 start=01:00、len=82800s）与 `America/Havana 2018-11-04`（len=90000s）两组，断言：(a) 连续两日 `DayWindow` 首尾相接；(b) 跨该日的一段区间切分后合计秒数 == 区间长度；(c) 任取一刻 `t`，恰有一个 `DayWindow` 满足 `start <= t < end`。
- 命令：`cargo test --manifest-path rust/Cargo.toml --test midnight_split`（注入 `ClockSample`）+ `cargo test --manifest-path rust/Cargo.toml --test day_length`

**AC-12 / AC-13 —— 账本重放（核心，F8 + N4 + N5 + N10 修正）**

按《架构契约》§4.3 的五个**已裁决场景**断言，不再给出互斥期望：
- **S1（AC-12）**：`+20, +30, set 35, +10` ⇒ 20 → 50 → **35** → **45**（对应规格 11 节原文"20、50、35、45 分钟"）。
- **S2（AC-13 编辑 set 之前的条目）**：`+20→+40, +30, set 35, +10` ⇒ 40 → 70 → **35** → **45**（`set` 仍钉住 35）。
- **S3（AC-13 删除 set 条目）**：`+40, +30, (set 已逻辑删除), +10` ⇒ **80**（恢复的是**编辑后**前序累计）。
- **S4（编辑 set 之后的条目）**：`+20, +30, set 35, +10→+25` ⇒ **60**。
- **S5（N5 编辑 kind=2 行）**：`+20, +30, set 35→set 50, +10` ⇒ 20 → 50 → **50** → **60**。

- **N4 新增必测（排序锚点）**：构造"**生成顺序 ≠ 可信发生时间顺序**"的场景（惰性生成使昨天 23:50–00:20 的切片行晚于今天若干行才写入），断言聚合按 `occurred_wall_ms` 排序得出、而非按 `ledger_seq`，且结果与"按发生时间顺序插入同样的行"一致。**这条是守护测试**：v2.0 的用例全部用显式 seq 顺序，不覆盖该场景。
- **N5 新增断言**：`EditLedgerEntry` 能编辑 kind=1 与 kind=2 且参数与 `kind` 不匹配时返回 `LedgerInvariantViolation`；`DeleteLedgerEntry` 删除 kind=2 行时返回 `Notice` 含 `affected_app_date` 与 `resulting_total_seconds`；`ledger_entry_audit` 留下 `prev_*`/`new_*`。
- **N10 新增断言（Q7 已确认"两种方式共享时间记录和统计"）**：同一 Task 同一应用日在**正向**与**倒计时**各记一段，断言合并为**单一总量**，且日历、周/月趋势、热力图、任务占比**四处同值**。访谈依据：需求访谈与决策记录.md:149"两种方式共享时间记录和统计，不是两个孤立模块"。v2.0 的 20 条 AC 无一条覆盖此已确认项。
- 另断言：编辑用 `EditLedgerEntry` **原地改值并保留 `ledger_seq`**；删除保留顺序槽；同 `slice_id` 二次入账被 `UNIQUE(kind, ref_id)` 拒收。
- 命令：`cargo test --manifest-path rust/Cargo.toml --test ledger_replay` + `./gradlew.bat testDebugUnitTest`

**AC-14 —— 标题切片**
- 断言：段内改名 → 旧段 `title_snapshot` 不变、新增段用新名；`task_id` 与长期累计不被拆分。
- 命令：`cargo test --manifest-path rust/Cargo.toml --test title_slice`

### 设备条目（本机当前**阻塞**）

**AC-09 —— 异常中断与恢复（F4/F5/N7 修正）**
- 方法：`adb shell am kill <pkg>`（进程回收）与 `adb shell am force-stop <pkg>`（强制停止）各一轮；重开后断言
  1. 进入 `RecoveryPending`，且**未确认前 `ledger_entry` 零新增**；
  2. "舍弃"只写入 `[segment.start, last_heartbeat]` 的可信段，**空档不入账**；
  3. **同一接受动作重复 3 次，当日总秒数不翻倍**（`slice_id` 确定性，见《架构契约》§6）。
- **N7 新增负例断言（必须做）**：同一进程内正常计时静置 25 分钟（不杀进程、不锁屏异常），重建 UI 后**不得**进入 `RecoveryPending`。判定必须同时满足 §7.3 的两个条件；**心跳陈旧程度不得作为判定条件**。规格 5.2:110 原文要求"正常锁屏和切应用不弹此选择"。
- 命令：`adb shell am kill <pkg>` → 重启 → 比对导出库
- **阻塞**：`adb devices -l` 无设备（E14）。

**AC-15（设备侧）—— 系统时区切换**
- 方法：改设备时区；断言应用内日期口径不变、无新 `zone_epoch` 行。
- 注：改系统时区属设备状态变更，只能在**本项目专用**测试设备/AVD 上做。
- **阻塞**：唯一 AVD `axcoder_phase0_api35` 属他项目且含约 2.9 GiB 数据，不得改动（E14）。

**AC-16（设备侧）—— SAF 导出与恢复**
- 方法：真实走 SAF 选路径；错误密码、截断文件、未知高版本三类失败各一次，断言现有数据字节级不变；正确恢复断言总量与身份一致。
- **追加崩溃恢复验证**（F9 修正）：在《架构契约》§8.1 的 ⑨–⑫ 之间注入杀进程，覆盖状态机的五个分支，断言收敛到"全旧"或"全新"。
- **阻塞**：无设备。

**AC-17 —— 通知/勿扰/锁屏/force-stop**
- 方法：拒绝通知权限、开勿扰、锁屏、force-stop 四种状态各一轮；断言应用内到点结果可见；**不得**断言 force-stop 后仍能响铃。
- **阻塞**：无设备。

**AC-19 —— 同签名升级保数据**
- 方法：构造**同包名同签名**的旧版与新版两个调试 APK，覆盖安装后断言任务/累计/恢复点保留，运行中会话走恢复规则。
- **[契约]** 单个 APK 不算完成 AC-19；必须两个构建。调试签名可用于隔离环境实验，**不构成**正式发布签名准备。
- **阻塞**：无隔离设备/模拟器。

**AC-20 —— 真实中端设备性能**
- 方法：Macrobenchmark 测冷启动与关键列表/热力图滑动，记录型号、系统版本、刷新率与测量方法；目标见规格第 10 节（冷启动 p90 ≤ 2.5 s、帧时 p90 ≤ 16.7 ms / p95 ≤ 33 ms）。
- **不得**用模拟器数据替代真机；**不得**用设备级电量读数冒充单 App 功耗。
- **阻塞**：无真机。

### 人工/视觉条目

**AC-18 —— 空态/长文本/大字体/素材缺失/离线**
- 检查：新安装无任务首页、统计空态、超长标题与备注、系统字体最大、素材缺失、飞行模式；TalkBack 能理解并走通创建任务。
- 辅助自动化：Compose UI 测试可断言触控目标与语义节点存在，但**不能替代** TalkBack 人工走通（规格第 10 节）。**[未验证]** 本轮未搭建 Compose 测试基础设施。
- 承担：`ui-compose`。**人工项未执行**。

**AC-20（人工项）** —— 视觉层级、不卡顿、无明显字体/图片跳动，由人评价。

## 3. 本机当前可执行性（本轮实测）

| 门禁 | 命令 | 本轮是否运行 | 结论 |
| --- | --- | --- | --- |
| Rust 单测（**门禁命令逐字**） | `cargo test --manifest-path rust/Cargo.toml --test occurrences` | **运行（探针工程）** | EXIT=0，`1 passed`（E3） |
| Rust 全量单测 | `cargo test --manifest-path rust/Cargo.toml` | **运行（探针）** | EXIT=0 |
| Rust 格式 | `cargo fmt --manifest-path rust/Cargo.toml --check` | **运行（探针）** | EXIT=0 |
| Rust lint | `cargo clippy --manifest-path rust/Cargo.toml --all-targets -- -D warnings` | **运行（探针）** | EXIT=0 |
| **panic 策略门禁（N1 新增）** | `cargo rustc --manifest-path rust/Cargo.toml --release --lib -- --print cfg` | **运行（探针）** | EXIT=0，输出 `panic="unwind"`；改为 `abort` 后输出 `panic="abort"`，门禁确实失败（E16）。**必须带 `--lib`**（crate 有 lib+bin 两目标；省略即报错），**且必须保留 `--`**（省略在 stable 上 EXIT=101） |
| **FFI 级 panic 遏制（N2 新增）** | ctypes 调 `uniffi_n2core_fn_func_boom` | **运行（探针）** | 调用正常返回，`status.code=2`、payload = panic 消息；`panic="abort"` 构建下进程终止（EXIT=127）（E17/E18） |
| **午夜 DST 日窗口（N3 新增）** | jiff 探针（São Paulo/Havana/Santiago/Beirut 2018）；并在产品形态 crate 内跑三条断言 | **运行（探针）** | 日窗口首尾相接、该日无 `00:00`（前滚到 01:00、len=82800s）（E19）；`midnight_dst_windows_are_contiguous`、`every_instant_belongs_to_exactly_one_day`、`havana_25_hour_day` 三条用例在 `D:	mpinalcheck` 实跑 **3 passed**（E20） |
| UniFFI 绑定生成 | `cargo run --release --bin uniffi-bindgen -- generate --library …` | **运行（探针）** | EXIT=0，产出含 `dayBounds`（E4） |
| Gradle 编译 + APK | `gradle-8.13/bin/gradle.bat --no-daemon assembleDebug` | **运行（探针）** | `BUILD SUCCESSFUL`（E7） |
| Gradle 单测 | `./gradlew.bat --no-daemon testDebugUnitTest` | **运行（探针）** | `BUILD SUCCESSFUL`（E7/E13） |
| Android lint | `./gradlew.bat --no-daemon clean lintDebug` | **运行（探针）** | `BUILD SUCCESSFUL`（E7） |
| Rust → Android `.so` | `cargo build --target aarch64-linux-android` | **运行（探针）** | **失败**，exit 101，target 未安装（E15） |
| Android 仪器测试 | `./gradlew.bat connectedDebugAndroidTest` | **未运行** | 无设备，**阻塞** |
| 真机性能 | Macrobenchmark on device | **未运行** | 无真机，**阻塞** |
| TalkBack | 人工 | **未运行** | 无设备，**阻塞** |

**[契约] 诚实口径**：

1. 上表"运行（探针）"指在 `D:\tmp` 下的临时等价工程验证**门禁命令本身可用**，与产品工程的 AC 通过无关。**AC-01–AC-20 本轮一条都没通过，也一条都没判失败**——产品代码尚不存在。
2. **N11 修正**：上述探针工程目录（`D:\tmp\rev2`、`D:\tmp\agptest` 等）在产出本表后**已被删除**，因此上表各项**目前不可由第三方就地复验**。凡"运行（探针）"条目，复验需按《工程布局与版本锁定》§1.1 重建等价工程。产品工程建立后必须在**工作区内**（`scripts/cmd/`）保留可复跑脚本，不再依赖 `D:\tmp`。

## 4. 不可自动化的边界

| 禁止的替代 | 原因 |
| --- | --- |
| 用单元测试代替 APK 安装与前后台行为 | AC-09/AC-17 的失败模式只在真实进程生命周期出现 |
| 用模拟器性能代替真实中端机数据 | AC-20 明确要求真实设备 |
| 用截图比对代替 TalkBack 手测 | AC-18 与规格第 10 节明确 |
| 用单个 APK 代替 AC-19 升级演练 | AC-19 要求同包名同签名的旧/新两个 APK |
| 用静态调色板检查代替页面级对比度 | 规格第 7 节要求按实际页面验证 |
| 用 Robolectric 通过代替设备验收 | Robolectric 不产生真实通知、后台限制与 OEM 行为 |
| 用宿主 cargo 通过代替 `.so` 交付 | 宿主门禁与 Android `.so` 打包是两回事（《架构契约》§12） |
