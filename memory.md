# 项目记忆

## 文档职责与入口

- [需求访谈与决策记录](docs/需求/需求访谈与决策记录.md) 保存全部问题、选项、当时推荐、真实用户回答、确认边界和变更历史。它是需求访谈的追溯依据，不能用本文件替代。
- [Android 个人艺术 Todo 开发需求规格](docs/需求/Android个人艺术Todo开发需求规格.md) 汇总首版范围、任务/计时/账本/备份/架构与可执行验收；**该规格已于 2026-10-05 获用户整体通过**（预授权方式：用户一次性放行全部评审关卡并说明事后自行验收，未逐页审阅）。[艺术视觉与交互开发提示词](docs/需求/艺术视觉与交互开发提示词.md) 是独立视觉实施简报；[全项目开发 Workflow 执行提示词](docs/需求/全项目开发Workflow执行提示词.md) 供后续按规格与视觉审阅关卡启动或续做完整开发。
- `AGENTS.md` 保存协作规则；本文件仅保存精简产品约束、稳定经验及文档入口。
- Q1–Q51 已全部回答，Q25 后来补答，Q51 选择 Compose 原生界面＋Rust 业务核心。用户停止业务细节追问；剩余小规则在规格中标为设计默认，不能冒充逐项确认。当前不编写产品代码、不默认进入实现。
- 推荐不等于用户确认，后台任务通知不等于用户回答。用户没有写题号时按选项含义匹配，不按列表位置机械错配。

## 核心确认约束

### 产品与视觉

- 个人 Android 手机软件，本地优先、无账号、无首发云同步。最新 Q39 将首发范围改为 APK 为主、开发注意规范，不要求实际 Google Play 分发；不能继续沿用 Q38 的“双分发首发”，不能自行注册、上传或发布。
- 用户已选择 Q51 A：Kotlin/Jetpack Compose 原生 UI 与 Android 生命周期、Room/SQLite 唯一数据库写入权；Rust 实际承担任务、计时、跨日及统计规则，经窄 UniFFI 接口连接。高级艺术视觉和真机验证与核心正确性同为门禁，不把 Rust 语言名或默认 Compose 组件当作高级感的保证。
- 高级感是首要要求，方向为鲜明艺术插画。具体配色、排版、卡片与动效由开发方负责，不让用户反复选择设计细枝末节。统计视觉要丰富，必须包含 GitHub 风格热力图，但不能牺牲可读性、准确性与流畅度。
- 首发有精心搭配的内置艺术视觉；后期支持手动换图、本地上传和随机配置。

### 任务与历史

- 两组任务：未完成临时任务始终在日常前面；日常每天在午夜 00:00 重新出现，前一天漏做不积压到首页。
- 全部日常每天出现，无周频率、目标、分类、标签、项目。任务为标题、备注与手动完成状态，不默认独立链接、附件、优先级或子任务。
- 同组内手动拖动，顺序稳定。已完成日常进入可展开的底部折叠区，临时完成后进历史。
- 不再做的任务归档；历史与投入保留。归档不等于完成。
- 过去日常完成状态可补记/撤销，不影响今天。改名影响当前及以后，历史保留当时名称，累计身份不因改名改变；备注仅当前说明，不保存旧版本。
- 临时完成可重新打开续做，日常归档可当天重新启用，累计续接；归档期不生成应做记录也不算漏做。新建日常立即出现，创建前不补漏做。
- 固定应用时区，默认首次安装时所在时区，手机系统变化不改变口径。跨日完成让用户选择日期，默认今天，不同时完成多天。

### 计时与统计

- 卡片打开进入正向/自定义分钟倒计时选择，明确点击开始才计时。Q25 已补答：每任务独立记忆分钟数，新任务默认 25 分钟，每次可改，这不是时间目标。
- 支持暂停、继续、提前结束；暂停不累计，结束保留实际投入。同一时间仅一个活动计时器，换任务先确认结束旧计时并开始新任务。
- 主动勾选计时中任务需确认结束计时并完成；到点不会自动完成任务。
- 正常切应用或锁屏继续计时，跨午夜按有效区间分配到两日且不中断。异常中断恢复时由用户接受、修正或舍弃异常部分，不能把推算时间直接当实际投入。
- 倒计时到点停止并让用户选择休息或继续；休息不启动倒计时，继续需明确开始新一段，不自动循环。
- 可补录、修改、删除记录；手动填写日期与时长、不伪造时段。Q46 选 B：既能额外增加，也能修正指定任务某日总投入；调整必须可追溯且所有图表一致，不无提示毁损原始自动记录，不重复累计。
- 日历每日总投入及明细、周/月趋势、按任务占比与 GitHub 风格热力图。只到点提醒，默认震动，可开声音，无日常催促。
- 备份恢复完整替换，不做合并；手动导出加本机自动恢复点，可选密码保护。实际替换前需验证文件并保留当前恢复点，失败不损坏原数据；本机恢复点不能当防卸载/丢机备份。用户要求不再问小规则，默认流程在规格中明确。
- 后期图片仅内置精选插画＋本地上传、离线随机，分配后固定，只有用户主动随机换图才变，不随每日刷新或重开变化。

## 调研与验证限制

- 已调研公开资料：TickTick、Todoist、Focus To-Do、Amazing Marvin；Super Productivity、Vikunja、Habitica、ActivityWatch；Things 3、Sunsama、Structured、Endel 的产品定位。未安装参考软件、未做截图级视觉比较。
- 每日刷新不是“完成后才重复”，任务本身、每日执行状态、时间记录必须区分。规则借鉴与复制代码/插画分别评估许可证。
- Android 官方资料已核查：正常后台应继续计时，但通知权限、Doze、厂商省电、关机或用户停止可能影响到点送达；不能承诺任意状态必定响铃，不能把划掉近期任务、系统停止和 force-stop 混为一谈。精确闹钟权限按目标 SDK 与具体方案核查。
- Android 依据：[前台服务](https://developer.android.com/develop/background-work/services/foreground-services)、[通知权限](https://developer.android.com/develop/ui/views/notifications/notification-permission)、[闹钟](https://developer.android.com/develop/background-work/services/alarms/schedule)、[Doze](https://developer.android.com/training/monitoring-device-state/doze-standby)。2026-10-05 起已实际搭建 Gradle＋Rust 工程并跑通 JVM 侧垂直切片与 Rust 门禁；**仍未做真机验证**（当时无已连接设备），APK 受 NDK 许可证阻断未产出，详见下方“构建与验证入口”。
- 商店分发不是单纯打包。选择 Google Play 时，个人内部测试与正式公开发行是不同路径，后者涉及账户、测试与审核门槛；实际提交前重新核对官方政策。不注册、购买、上传或发布，除非另获明确授权。

## 当前访谈进度

- Q1–Q51 已全部回答并归档，Q25 已补答，Q51 选择 A（Compose 原生 UI＋Rust 领域核心，Kotlin/Room 持久化、UniFFI 窄接口）。业务访谈停止，质量门禁与视觉开发提示词已写入需求文档；用户已于 2026-10-05 整体通过规格并预授权后续全部评审关卡，开发进入实施阶段。
- 剩余小业务规则在最终规格中明确为设计默认，不再逐项追问，不借默认扩展首发范围；路线选项和真实回答仍保存独立访谈文档。
- 文档自检应确认 Q1–Q51 每题有真实回答、选项和状态；Q25 有原题与补答两个标题属有意保留漏答过程，不能当作新增一题。核对 Q39 对 Q38 的范围覆盖、设计默认标记及文档互链；文档检查不能冒充产品测试。

## 构建与验证入口（2026-10-05 建立，已实跑）

- 门禁脚本落在本工作区，可直接复跑、不依赖临时目录：`scripts/cmd/run-rust-gates.cmd`（cargo test/fmt/clippy/panic 门禁/绑定生成，**已全绿**）、`scripts/cmd/run-android-gates.cmd`（assembleDebug/testDebugUnitTest/lintDebug）、`scripts/cmd/build-android-so.cmd`（交叉编译 .so 再打包，**当前受阻**）。
- 环境前置（缺一即失败）：`JAVA_HOME=E:\devtools\jdk-17`、`ANDROID_HOME=E:\Android\Sdk`、`GRADLE_USER_HOME=E:\caches\gradle`。**不要创建 `local.properties`**：Windows 路径未转义会让 `lintDebug` 报 `PropertyEscape` 失败，用 `ANDROID_HOME` 即可。
- Gradle wrapper 固定 8.13（缓存命中目录 `E:/caches/gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d`，与 base36(md5(distributionUrl)) 公式一致）；本机 `gradle` 命令是 8.11.1，仅用于生成 wrapper。
- 依赖首次构建需联网（`maven.aliyun.com` 镜像，已在 `settings.gradle.kts` 显式声明，不依赖 init 脚本）；离线模式会因 `ui-tooling/kotlin-stdlib-common` 未缓存而失败。
- **Kotlin 块注释可嵌套**：`/** ... */` 里出现 `/*` 会静默吞掉后续代码并报 “Unclosed comment”。KDoc 里写路径要用 `app/schemas` 而不是含 `/**` 的 glob。
- JVM 测试跑真实 Rust 库的方式：`generateUniffiBindings` 以 `rust/` 为工作目录，故宿主 cdylib 落在 `rust/build/rust-host/release`，用 `testOptions.unitTests.all { it.systemProperty("jna.library.path", ...) }` 指过去即可；Android 侧 `.so` 是另一条路径。
- **`cargo fmt --check` 在仓库根目录直接跑必然 EXIT=1，但这不是格式问题**：根目录没有 `Cargo.toml`，命令以 `error: could not find 'Cargo.toml' in D:\PROJECT_ZZZZZZZZZ\todo1010 or any parent directory` 失败（bash 与 cmd.exe 实测一致）。正确形式是 `cargo fmt --manifest-path rust/Cargo.toml --check` 或先 `cd rust`；`run-rust-gates.cmd` 用的正是前者。**2026-10-06 实测 `cargo fmt` 对 33 个 `.rs` 是 no-op（改前改后 md5 全同，EXIT=0）**。再看到 fmt「退出 1」先核对调用方式，别去找不存在的格式差异。
- **`verify_art_assets.py` 2026-10-06 实测 350/350、EXIT=0**（`python scripts/test/verify_art_assets.py`；bash 与 `cmd` 下 `python` 指向同一解释器，numpy 2.2.5 / PIL / playwright + Chromium 均在）。之前某轮工作流报过 exit 1，但工作区里**没有那次运行的原始输出**，无法归因，不能据此认定素材不合格。注意该脚本会**按设计重写** `docs/设计/_素材渲染/*.png`、`素材总览.png`、`素材适配样例.png` 与 `assets/art/thumbs/*.webp`，这些改动是门禁产物，不是有人改素材。
- **NDK 未安装且被许可证阻断（第 1 轮构建失败的根因）**：`E:\Android\Sdk\ndk` 不存在，本机任何位置都无 NDK。`sdkmanager "ndk;27.2.12479018"` 交互提示 `License android-sdk-license: Accept? (y/N)`；不回答（stdin 关闭）即得到 `Skipping following packages as the license is not accepted: NDK (Side by side) 27.2.12479018`。`E:\Android\Sdk\licenses`、`ANDROID_USER_HOME=E:\Android\UserHome\licenses` 均不存在。
  - 已排除的其他原因：该版本在远端仓库确实存在（`sdkmanager --list` 列出 `ndk;27.2.12479018`），远端仓库可正常访问（能拉取 repository），`rustup target list --installed` 已含两个 Android target，`cargo check --target` 对两目标均通过。故唯一障碍是许可证，不是网络、版本或 Rust 代码。
  - **解除条件（需用户动作）**：由用户执行 `sdkmanager --licenses` 接受许可，再安装 `ndk;27.2.12479018`；代理不得代答 `y`（法律协议 + 项目提示词第 23/50 行明令“不接受 SDK 许可”）。装好后 `scripts/cmd/build-android-so.cmd` 一步产出 APK。本机无 `cargo-ndk`，但**本项目不需要**（`app/build.gradle.kts` 直接用 NDK clang 作 linker）。
- **【主会话已裁定，阶段 C 起长期有效】`cargoNdkBuild` 视为「用户侧硬阻塞」**：本条门禁在本机不可能通过，根因是用户侧许可证缺失，**不是代码缺陷**。后续阶段（脚本仍在第 7/9/10 阶段调用 `assembleDebug`）若再遇同一失败：**不重试、不尝试让它通过、不申请权限**，直接回报同一根因并引用本登记，然后继续其余实现工作。禁止改用假数据、跳过检查或伪造通过。
- **禁止的绕行（已全部排除并记录）**：① 生成缺 `.so` 的 APK；② `-x cargoNdkBuild` 跳过；③ 用 build-tools 的 `lld.exe`/`aarch64-linux-android-ld.exe` 硬凑——全机搜不到任何 Android 原生 sysroot（`find E:/Android/Sdk -name libc.so` 为空），缺 `libc/libm/libdl/liblog` 无法链接 Rust cdylib，且与契约 §4.3 规定的 NDK 工具链不符；④ 改 `app/build.gradle.kts:78-84` 的前置检查来让它变绿。
- 交叉编译前的定位手段：`cargo check --target aarch64-linux-android`（只编译不链接）能独立证明 Rust 代码对 Android 目标可编译，与“缺链接器”区分开；`cargo build --target ...` 的报错是 `linker 'cc' not found`。
- 阶段 C 交付口径（已裁定）：不存在可安装 APK，终态只能归为「实现受阻」或「代码完成、验收受阻」，**不得**写「首版验收完成」。`app-debug.apk` 未产出、`.so` 真实加载与装入均记为 **[未验证]**。
- **APK 路径上除 `cargoNdkBuild` 外的任务已全部实测通过**（2026-10-05）：`gradlew processDebugResources processDebugManifest mergeDebugAssets mergeDebugJavaResource dexBuilderDebug` → `BUILD SUCCESSFUL`。故 NDK 是唯一剩余障碍，解除后即可出 APK。
- **effect 列表是有序契约，FK 依赖顺序**（真实缺陷，已修 + 已加守护测试）：`active_session_slot.session_id` 是 → `focus_session` 的外键，因此 `OpenSession` 必须在 `SetActiveSession` **之前**。原实现顺序相反，导致每次 `StartSession` 在 Room 事务内报 `SQLITE_CONSTRAINT_FOREIGNKEY (code 787)`。Kotlin 按列表顺序应用 effects（`EffectsApplier`），所以 **Rust 侧 `state.rs` 的 `effects.push` 顺序即写入顺序**，新增任何带外键的 effect 都要检查生产者顺序。守护测试：`rust/tests/single_session.rs` 的 `opening_a_session_persists_the_session_before_the_active_slot`。
- **本机 Room 外键是真正生效的**（`AppDatabase` 的 `onOpen` 执行 `PRAGMA foreign_keys = ON`），JVM/Robolectric 下同样生效——这正是上一条缺陷在 JVM 测试里就暴露出来的原因，不要以为“只有真机才会碰到”。
- **新增 Rust 投影导出必须同步桥接**：契约 §3.2 要求统计投影是“查询”粒度、§9 要求返回类型统一 `DomainResult`，故所有「查询」类导出都要在接口 `DomainBridge` 与实现 `UniffiDomainBridge` 各加一条，否则页面会绕过唯一 seam 直接 `import app.arttodo.core.*` 调裸导出。守护测试：`app/src/test/java/app/arttodo/DomainBridgeProjectionTest.kt`（含 N10 的“日历/趋势/热力图/占比四处同值”断言）。

### Rust 领域核心测试（2026-10-05 建立，已实跑）

- 属性测试用 `proptest = "1.11"`（dev-dependency，已 `cargo fetch` 落缓存；crates.io 可达，非离线环境）。测试文件放 `rust/tests/<name>.rs`，门禁命令 `cargo test --manifest-path rust/Cargo.toml --test <name>`。新增 `--test` 目标会自动进入整包 `cargo test`，无需改脚本。
- **Windows 批处理门禁必须使用 CRLF 行尾**：`run-rust-gates.cmd`、`run-android-gates.cmd`、`build-android-so.cmd` 均须使 CRLF 数等于 LF 数。2026-10-05 实测 `run-android-gates.cmd` 为 LF-only 时，`cmd.exe` 未进入 Gradle，反复把 `REM` 注释正文当命令执行（日志持续增长到约 68 MB、CPU 长时间占用）；恢复 CRLF 后 `build-android-so.cmd` 能立即以 NDK 缺失的预期错误退出。改批处理后先检查行尾，再运行长门禁。
- 该脚本的 panic 门禁原先把 `type "%CFG%"` 放在同一括号块内再 `goto :fail`，导致汇总行打印 `exit code 0`（`%errorlevel%` 已被 `type` 覆盖）；已改为独立 `:panicfail` 标签、汇总行不再打印退出码。验证门禁"真的会失败"的方法：把 `[profile.release] panic` 改成 `abort`，脚本应 EXIT=1 并打印 `FAILED: release profile does not unwind`；改回 `unwind` 应 EXIT=0。**改完必须复原**。
- 属性测试踩坑：`ledger_seq` 是**同一 `occurred_wall_ms` 的并列打破键**，所以"翻转/交换行"的属性只在各操作时刻互不相同时成立；把同行时刻的行互换会合法地改变结果。写重放顺序属性时必须先把时刻改成严格递增，否则会误报为领域缺陷。
- 领域实现的读点约定：日常完成态以 `DomainState.occurrence_completion_events`（权威事件）为准，`OccurrenceRecord.is_completed` 只是缓存；`RebuildOccurrenceView` 重放事件并写回缓存。临时任务同理用 `temporary_completion_events` + `temporary_completed()`。统计四处（日历/趋势/占比/热力图）统一走 `stats::{day_totals, period_totals, task_shares}`，聚合按 `(task_id, app_date)` 标签、不含 `zone_epoch_seq`（跨纪元同标签求和）。
- **仍未验证（本工作区无设备）**：AC-09 真机注入、AC-15 系统时区切换、AC-16 SAF、AC-17 通知、AC-19 升级、AC-20 真机性能、TalkBack；Rust→Android `.so` 交叉编译仍因 SDK 许可证阻塞。宿主 cargo 测试全绿**不能**替代这些设备项（见《AC覆盖表》§4）。

### Room 数据层与迁移（2026-10-05 建立，已实跑）

- 门禁：`gradlew.bat testDebugUnitTest`（含 Robolectric，无需设备）。本轮实测 **43 tests / 0 failures / 0 errors**，`lintDebug` 亦 `BUILD SUCCESSFUL`。数据层测试类：`app/src/test/java/app/arttodo/data/{DatabaseWriteTest,DatabaseMigrationTest,BackupStructureTest}.kt`（共 34 例）。
- **【真实缺陷，已修】`@Insert(onConflict = REPLACE)` 在带 `ON DELETE CASCADE` 子表的父表上会删掉整段历史**：SQLite 把 `INSERT OR REPLACE` 实现为 delete+insert，因此 `task` / `daily_occurrence` / `focus_session` 的 REPLACE upsert 会级联删除 `occurrence_completion_event`（唯一真相）、`daily_occurrence_view`、`session_segment`、`ledger_entry`、`temporary_completion_event`。修法：这三张父表改为 **update-first upsert**（`@Update` 返回受影响行数，0 才 `@Insert(ABORT)`）。守护测试 `DatabaseWriteTest.a_task_re_upsert_keeps_its_occurrences_sessions_and_ledger` 等三例；已用「把 upsert 改回 REPLACE」实测它会变红（事件数 1 → 0）。
- **规律（写任何新 upsert 前先查）**：先确认该表是否被别的表以 `CASCADE` 引用。是 → 只能 update-first；否（叶子表如 `daily_occurrence_view`、`session_segment`、`app_setting`、`session_heartbeat`）→ REPLACE 安全。
- **提交顺序**：`AppDatabase.build(context, name)` 是生产与测试共用的唯一打开路径（同一 migrations、同一 `PRAGMA foreign_keys=ON`、同一 WAL）。测试**必须**走它，自己 `Room.databaseBuilder` 就测不到这些承诺。
- **迁移测试方法（无需设备）**：用 `androidx.room:room-migration`（随 room-runtime 传递引入，无需新增依赖）+ `org.json`。v1 样本库**从已提交的 `app/schemas/app.arttodo.data.AppDatabase/1.json` 的 `createSql` 建表**，不手抄 DDL（避免与冻结 schema 漂移）。两个坑：① JSON 里是 `` `${TABLE_NAME}` ``（**带反引号**），替换时要连反引号一起换，否则得到空表名 → `near "task": syntax error`；② Robolectric 的 JUnit4 不接受 `fun x() = runBlocking{}`（返回类型非 void → `InvalidTestClassError`），必须写 `fun x(): Unit = runBlocking {}`。
- **schema 版本**：`@Database(version = 2)`，`AppDatabase.SCHEMA_VERSION` 必须与它一致；`DatabaseMigrationTest` 断言迁移链覆盖 `BASE_VERSION..SCHEMA_VERSION`、每版 JSON 都已提交，并**断言每条迁移的实际结尾版本等于 Room 创建的版本**（本轮实证：`endVersion=1` 时 Room 仍抛 “A migration from 1 to 2 was required but not found”）。**不设 `fallbackToDestructiveMigration`**。
- v1→v2 的内容：`task_title_revision` 的 `(task_id, effective_wall_ms)` 索引改为 UNIQUE（历史明细按切片起点解析标题，同刻两条会让标签取决于返回顺序）；迁移里先 `DELETE ... WHERE revision_seq NOT IN (SELECT MAX(revision_seq) ... GROUP BY task_id, effective_wall_ms)` 修数据再建唯一索引。已实测：去掉修复步 → 迁移抛 `SQLITE_CONSTRAINT_UNIQUE`。
- **追加型表不要按内容去重**：完成事件/审计的顺序与重复本身就是语义。用「同 action + 同 `occurred_wall_ms`」去重会在时钟分辨率粗时误删合法事件（守护测试 `two_events_at_the_same_instant_are_both_recorded`，实测该规则会把 3 条压成 2 条）。真正需要幂等的是 **kind=0 账本切片**（`slice_id` 是区间的纯函数，见契约 §6），按 `UNIQUE(kind, ref_id)` 判定；kind=1/2 行的重放由 `command_log` 主键 + 领域 `DuplicateCommand` 挡住。
- 写边界校验：`FieldLimits`（标题 120 / 备注 2000 / 分钟 1..1440，与 `rust/src/state.rs` 常量对齐）在 `EffectsApplier` 事务内抛 `DataViolationException` → 整表回滚 → `CommandExecutor` 转成 `DomainFailure.Validation`。备份结构校验在 `BackupStructure`（magic/版本带/必需字段/表数与行数上限）；JSON 整数类型由 `org.json` 按字面量大小决定（Integer/Long/BigInteger），判断整数要三型都接受，写死 Long 会误拒合法文件。

### Compose 页面与主题（2026-10-05 建立，已实跑）

- 记录页 `dayTotal` 必须从选中日期的账本行按 `task_id + app_date` 经 `DomainBridge.replayDailyTotal` 汇总；不要把某个任务的 `SetDailyTotalSeconds` 当作全日总量。补记预览同样必须分别显示所选任务前后量与全日投影值。
- 洞察页全历史热力图不能复用「从年初截取」的账本子集；日历/趋势/占比/热力图应来源同一完整 ledger snapshot。Compose `horizontalScroll` 必须在有界 viewport（如 `Box(weight(1f))`）内，不能嵌在先无限测量其子项的 `Row.horizontalScroll()`；语义描述测试可直接断言合并节点树文本。
- `CalendarGrid` 的「无记录」与「零投入」要依赖事实存在集合，不可用 `totalSeconds > 0` 推断记录存在；月空态判断也不能只检查正值。
- Compose JVM 全套门禁：`JAVA_HOME=/e/devtools/jdk-17 ANDROID_HOME=E:/Android/Sdk GRADLE_USER_HOME=E:/caches/gradle ./gradlew --no-daemon testDebugUnitTest`；Lint 用 `... ./gradlew --no-daemon lintDebug`。`assembleDebug` 在 2026-10-05 因 NDK 27.2.12479018 未安装/SDK license 未接受而被 `cargoNdkBuild` 阻断；严禁移除/跳过 NDK 任务或伪产出无 .so APK。
- 原型核验会删除并重写 `docs/设计/_原型渲染/*.png` 与 `_渲染记录.txt`，运行前确保它确属指定视觉原型测试产物；当前 `verify_prototype_offline.py` 按脚本定义可重复生成 43 张原型图。
- 记录页的手动「额外增加」与「设为当日总量」必须按任务维度投影；同日全量合计不能直接作为任务某日总量修正参数。`replayDailyTotal(taskId, appDate, rows)` 应复用桥接投影，先从同日明细加载完成后再显示总计，避免异步读行期间短暂显示 0。
- `ComposeAccessibilityTest` 固定日历语义数据时，加入“有记录”日期到 `recordedDates`；零秒和无记录不可被同一测试 fixture 混为一类。

- **设计系统落地位置**：`app/src/main/java/app/arttodo/ui/theme/{StudioTokens,StudioTypography,StudioTheme,StudioIcons}.kt` 是唯一 token 来源；`ui/common/` 放共享组件、格式化与绘制工具；五个页面在 `ui/{today,focus,record,insight,settings}/`。页面不写裸 `fontSize`/`RoundedCornerShape(20.dp)`/动画时长。`LocalAppState`/`LocalAppViewModel`/`LocalSnackbarHostState` 由 `nav/ArtTodoNavHost.kt` 提供；`LocalAppState` 默认空态（便于预览/组件测试），`LocalAppViewModel` 无默认值。
- **视觉素材落地**：`assets/art/**` 的 SVG **未**转 VectorDrawable；改为把 `docs/设计/_素材渲染/*.png` 复制为 `app/src/main/res/drawable-nodpi/art_<name>.png`（authorship 与 2x 尺寸见《素材适配样例》）。4 幅 cover 的 `radialGradient` 因此无需改写。**未**做 SVG→VectorDrawable 转换，也未在任何 Android 设备上渲染核对。
- **对比度门禁只是静态色板**：`python scripts/test/verify_design_tokens.py`（78 项正向 + 4 违禁 + 7 色阶格反向，EXIT=0）不检查任何 Compose 代码。改 `StudioColors` 的十六进制必须同时改脚本，否则两边会静默漂移。
- **material3 1.4.0 的 `MaterialTheme.motionScheme` 存在但为 `internal`**：实测探针 `MaterialTheme.motionScheme` 报 `Cannot access 'val motionScheme': it is internal in 'androidx.compose.material3.MaterialTheme'`。故设计系统 §10.1 的「先用编译期验证」已有结论：**不可用**，动效只能从 `StudioTokens` 取 `tween`/`spring`（现状如此），不得为用它而升级 BOM。
- **Compose UI 测试在 JVM 可用（Robolectric）**：`androidx.compose.ui:ui-test-junit4` + `ui-test-manifest`（BOM 管版本，`settings.gradle.kts` 的阿里云镜像可拉到 `1.11.4`）。三个必要的坑：① **必须加 `@RunWith(RobolectricTestRunner::class)`**，否则 `ComposeUiTest` 内部读 `Build.FINGERPRINT` 得到 null → NPE；② 默认窗口只有 **320×470dp**，比设计目标 360×800dp 小，断言「首屏可见」会因窗口而非布局失败，用 `@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")`；③ 页面级断言需要真 `AppViewModel`（测试里 `AppViewModel(app.container)` 即可，宿主 dll 已由 `jna.library.path` 指好）。
- **页面级「按钮→Rust→Room→重开仍可读」垂直切片已可自动化**：`app/src/test/java/app/arttodo/ui/TodayAddFlowPersistenceTest.kt` 从真实 FAB（`contentDescription="新建任务"`）点开创建面板、填标题、点「添加」，走 `AppViewModel`→`CommandExecutor`→真 Rust→Room，再另开只读句柄读回同一文件。**页面测试必须自己 provide 三个 CompositionLocal**（`LocalAppState`/`LocalAppViewModel`/`LocalSnackbarHostState`，默认值会 `error()` 或空态），并给 `TodayScreen(onOpenFocus = {})` 传参。填字段用 `onAllNodes(hasSetTextAction())[0]`。
- **Robolectric 跨测试类共享一个沙箱，`AppDatabase` 单例会串库**：`AppDatabase.get()` 的 `instance` 不会随测试类重置，因此不同测试类里 `getDatabasePath(DATABASE_NAME)` 可能解析到不同目录，而单例仍指向先前的库。「重开后仍可读」类断言若用 `AppDatabase.build(context, DATABASE_NAME)` 重建句柄会读错文件（实测报 `expected not to be: null`，单独跑绿、整包跑红）。**正确做法**：从容器句柄取真实路径 `requireNotNull(db.openHelper.writableDatabase.path)`，再用 `SQLiteDatabase.openDatabase(path, null, OPEN_READONLY)` 另开句柄读回，断言不受沙箱目录影响。该测试已做红/绿双向验证：删掉 `TodayScreen` 里 `viewModel.createTask(...)` 一行会转红，恢复后转绿。
- **`Modifier` 顺序决定语义是否生效**：`Modifier.semantics { contentDescription = ... }` 写在 `.clickable()` **之前**会被 `clickable` 自带的语义节点替换掉（实测该节点只剩 text，没有 description）。合并描述要写在 `clickable` 之后。另：`Modifier.semantics` 里读 CompositionLocal（如 `Studio.colors`）会报「@Composable invocations can only happen from the context of a @Composable function」——`Canvas` 的绘制 lambda 里同样，颜色必须在组合期取出再传进 lambda。
- **Kotlin 块注释可嵌套**（记忆里已有）：KDoc 里写 `assets/art/**` 会吞掉后续代码并报 `Unclosed comment`。
- **领域侧新增 `RestoreLedgerEntry`（删除撤销）**：`DomainCommand` 新变体，原地清 `is_deleted` 并写 `change_kind=2` 审计。**不能用「重新追加等值行」代替**：追加会落到 `(occurred_wall_ms, ledger_seq)` 顺序的末尾，被其后的 kind=2 总量事件吞掉，撤销后当日总量回不去。守护测试：`rust/tests/ledger_semantics.rs::undoing_a_delete_restores_the_row_in_its_original_slot`、`app/src/test/java/app/arttodo/data/LedgerEditingTest.kt`（走 Room + 真 Rust）。**`find_row` 会过滤已删除行**，撤销必须直接遍历 `state.ledger` 查找。
- **异常恢复入口现已接线（2026-10-06）**：`ArtTodoApplication.warmUp` 对库中 Running/Paused 且当前进程 `SessionOwner` 未持有的会话派发 `MarkRecoveryPending`；Rust `ResolveRecovery` 和 `FocusScreen.RecoveryPrompt` 承担用户确认。宿主/Robolectric 测试覆盖该路径；真机 `am kill`、force-stop、重启行为尚未验证。**仍需守住全部绕行出口**：普通完成、结束、切换任务不得绕过 `RecoveryPending` 把待确认空档入账。
- **系统与备份入口已接线（2026-10-06）**：`SettingsScreen` 现在使用 `BackupTransferControls` 提供 SAF 导出/导入、密码输入、恢复点列表与恢复，并接入 `rememberNotificationPermissionState`；`SessionRuntimeService` 与 `FocusReminder` 处理前台计时和提醒。之前“未接入”的描述已过时。设备端 SAF、通知/Doze/锁屏、崩溃与文件级原子切换仍未验证；实际恢复当前是 `BackupManager` 的暂存校验＋Room 事务整表替换，**未实现**架构契约的 pending/old/intent 文件级状态机。
- **无设备时的验证边界**：Compose 语义树能证明「节点存在、目标尺寸、状态有文字描述、文案在位」，**不能**证明 TalkBack 实际朗读、OLED 色偏、系统字体栅格化、帧率、误触率。这些仍按《AC覆盖表》§4 记为未验证。

### Compose 视觉收尾（2026-10-06，已实跑）

- **Robolectric 不做真实字体排版**：`Text` 节点宽度约 1px/字（实测 4 字标题 = 4px、20 字说明 = 21px），**任何依赖文字宽度的 JVM 断言都不可信**；能可靠断言的是节点存在、`boundsInRoot` 的相对位置与 dp 尺寸。行宽类结论只能引用浏览器原型门禁。
- **`ComposeUiTest.waitUntil` 的条件若只读 ViewModel 状态会「永远」超时**：它只 sleep + 推进 Compose 时钟，不 drain Robolectric 主 looper，`viewModelScope` post 到 Main 的续体永不恢复（实测 10s 超时）。条件里先 `compose.waitForIdle()` 再判状态，或直接把 UI 节点当条件。**也不要用 `runBlocking` 包 Compose 测试体**（阻塞主线程，同样卡死）；块体写 `fun x() {}` 即可。
- **`StudioArt` 的画幅是固定尺寸**：`Cover/Panel/Card` 会把容器写成 328×200 / 328×160 / 72dp，调用方再传 `fillMaxSize()` 无效（后写的 `size()` 赢）。后果实例：今天页封面在 152/112dp 档被裁而非缩放；专注页背景只是左上角一枚 72dp 缩略图。现新增 `ArtFrameKind.Fill`（调用方定尺寸）、`frameSize: DpSize?`（覆盖契约帧）、`showDegradation`（装饰性背景缺失时直接不画）。
- **素材与设计口径冲突（未解决）**：`state_focusing/state_paused` 交付的是 `card` 帧 216×216，而设计系统 §7.4 要求「panel 全幅铺底」；全幅铺底即把 216px 素材放大到整屏，真机可能偏软。未改素材，记为待插画侧确认。
- **记录页每任务合计必须走 `DomainBridge.replayDailyTotal`**：`rows.filter{kind!=2}.sumOf{delta}` 会漏掉「设为当日总量」（同日 `+600s` 与 `set 3600s`：页头 1 小时、组头 10 分钟）。投影的任务集合还必须取 `state.tasks + 当日账本行` 的并集，否则**归档任务**（不在 `state.tasks`）的分钟数从当日合计里消失。守护测试 `ComposeAccessibilityTest.the_record_page_shows_the_projected_task_total_rather_than_a_local_sum`（改回本地累加即变红，已双向验证）。
- **归档任务的历史明细/占比名称用 `UiState.titleOf`**（`tasks → archivedTasks → 原 id`），只用 `taskById` 会显示成 `task:xxxx`。`titleOf` 只是**当前**名称，不能拿来标注历史行。
- **「当时名称」投影已接上（2026-10-06）**：领域侧新增 `project_ledger_row_titles(rows, segments, tasks) -> LedgerRowTitle{ledger_seq, title, is_title_snapshot}`（`rust/src/ledger.rs::row_titles`）。kind=0 行用 `ref_id` 匹配 segment 的 `title_snapshot`——**`ref_id` 必须从右往左切**（`session_id:seg_seq:slice_start:zone_epoch`，而 session_id 本身是 `sess:<command id>` 含 `:`），并以 `row.occurred_wall_ms == 切片起点` 作一致性闸门；kind=1/2 行与所有匹配失败情形一律返回「当前标题 + `is_title_snapshot=false`」，**绝不编造当时名称**。桥接三处同步：`DomainBridge.ledgerRowTitles`、`UniffiDomainBridge`、`DomainStateLoader.titleSources()`（读**全部** task 行含归档，避免归档任务退化为裸 ID）。
- **记录页显示契约**：自动行第一行 = 当时快照，第二行 = 「自动计时 · 来源：…」；快照与当前名不同时正上方加 `labelM`「当前名称：…」（不覆盖旧名）；手动/设总量行没有快照，保持原样，不借用当前名冒充历史。守护测试 `app/src/test/java/app/arttodo/ui/RecordTitleHistoryTest.kt`（3 例：改名后自动行留旧名且组标题为新名、手动+设总量行不产生「当前名称」行、归档任务不显示 `task:…`）。**该夹具不要写死 command id**：`command_log` 不随 `DELETE FROM task` 清理，复用字面 id 会被幂等去重成空夹具（实测报 `Collection contains no element matching the predicate`）。
- **【真实缺陷，已修】`RenameTask` 从不持久化 `task.title`（2026-10-06）**：该命令只改内存 `next.tasks` 并推送 `RenameTitleRevision`（写 `task_title_revision`），**缺 `UpsertTask`**，而后台所有「当前标题」读的都是 `task` 表那一列 → 改名后今天页卡片、记录页组标题、`UiState.titleOf` 仍显示旧名，重启后新名彻底丢失；`ZoneEpochCoherenceTest` 只断言 revision 行所以一直没暴露。修法：`rust/src/state.rs` 的 `RenameTask` 分支补 `effects.push(LedgerEffect::UpsertTask { task })`（与 `EditNote` 同款）。守护测试 `rust/tests/title_history.rs::automatic_rows_keep_the_title_they_were_recorded_under`（改动前 `persisted.len()==0` 红）。**任何新命令都要检查「核心 next_state 变了但没发对应 effect」这一模式**：effects 才是 Kotlin 真正落库的东西。
- **仍未实现**：记录页「跨日拆分」`labelM` 标记（关键页面说明 §3.2）依旧缺失；洞察页按任务占比仍用当前名称（聚合口径，未接历史快照）。
- **记录页日历格 43.4×44dp 的补偿入口已补**：点月份标题打开 `DayPickerDialog`（每行 ≥56dp、未来日期禁用），这是关键页面说明 §7 承诺的等效控件，别把它删掉只留小格子。
- **`clearAndSetSemantics` 写在 `Image` 之前仍会清掉它自己的 `contentDescription`**：语义改由合并父节点承载（今天页封面 = 单个可点节点「今日封面：…」），或在 `clickable` **之后**再 `semantics { contentDescription = … }`。
- **图片缺失态在 JVM 测不到**：Robolectric 的 `BitmapFactory.decodeResource` 能成功解码随包 PNG，`StudioArt` 的退化分支不会被触发；该分支目前只有代码路径证据，真机/可控失败注入才算验证。

### 计时记账与恢复入口修复（2026-10-06，已实跑）

- **`CompletionChoice::AfterSeconds` 的单位是「会话累计投入秒数」**（屏幕与服务都传这个数：专注页 `elapsedSeconds` 求和、按钮文案「已投入 …」、前台服务剩余时间循环）。Rust 侧用 `session::total_end_wall_ms(segments, session_id, total_seconds)` 把它换算成结束瞬间：**已闭合段算作已花掉**，只有余量落进开段；`None` 表示没有开段（暂停态），调用方退回 `now`。若把它当成「当前开段的秒数」，暂停/改名后再结束就会把已入账段再记一遍（实测 10 分钟 + 暂停 3 分钟 + 5 分钟 → 入账 1500 s 而非 900 s；倒计时到点 2100 s 而非 1500 s）。守护测试：`rust/tests/finish_accounting.rs`。
- **换算不得依赖命令时刻**：`total_end_wall_ms` 只由段布局与 total 决定（下界为开段起点）。曾加过「不得超过 now」的上限，立刻打挂 `DomainBridgeProjectionTest.all_four_statistics_views_report_the_same_total`（测试注入的 wallClock 冻结在 BASE，合法入账被裁成 0）。守护测试：`finish_accounting.rs::the_booked_total_does_not_depend_on_the_command_clock`。
- **暂停态必须能结束**：没有开段时 `FinishSession{AfterSeconds}` 不再报 `PreconditionFailed("no open segment to measure from")`，而是直接结束（已入账部分不变）。守护测试：`finish_accounting.rs::stopping_a_paused_session_saves_the_investment_instead_of_failing`、`app/src/test/java/app/arttodo/ui/FocusFinishWiringTest.kt`。
- **暂停态的恢复窗口以「段的终点」为界**：恢复投影与 `ResolveRecovery` 都用 `segment.endWallMs ?: now` 作为 `recovery_wall_ms`，于是暂停会话的 gap 恒为 0；`ResolveRecovery` 在无开段时只关会话（不改段、不追加切片、发 `RecoveryPausedNoGap` 通知）。旧实现取最后一个已闭合段并把 `[start, now]` 当恢复窗，接受后把 3 小时暂停记成学习（实测 12000 s）。守护测试：`rust/tests/recovery_paused.rs`。
- **进入 `RecoveryPending` 的判据只有进程内 `SessionOwner`**（契约 §7.3，AC覆盖表 N7）：DB 说 Running/Paused + 活动槽指向它 + 本进程未持有 → 必须待确认。原实现的时钟合理性 `RecoveryClockAssessment`（boot tag/墙钟-单调钟一致性）会让「同一开机周期内进程被杀再重开」静默继续计时并把空档入账；该类与其测试已删除。守护测试：`app/src/test/java/app/arttodo/system/SessionRecoveryEntryTest.kt`（含「本进程持有 → 不弹」的负例）。
- **今天页勾选计时中的任务走 `CompleteWhileRunningDialog` → `completeWhileRunning(sessionId, appDate)`**：先确认、结束保存、跨日时给「昨天/今天」单选（默认今天，`UiState.activeSessionStartDate` 由 `DomainBridge.appDateOf` 产出，Kotlin 不做日期运算）。直接调 `setDailyCompletion` 会让计时继续跑且投入不入账。守护测试：`app/src/test/java/app/arttodo/ui/TodayCompletionWhileRunningTest.kt`。
- **专注页结束路径只能派发一次**：按钮原先既自己 `finishAt` 又回调 `onFinished`（内部再 `finishAt`），第二次必然 `SessionNotFound`，导致 `onClose`/「已记录」提示永不执行。现在按钮只回调，派发在 `onFinished` 内。
- **测试沙箱共享一个 Room 文件**：新增页面级测试类必须在每个用例前后清空 `task` 表（级联带走会话/切片/事件），否则会把 `ComposeAccessibilityTest`（断言 `tasks.size == 1`）和 `TodayAddFlowPersistenceTest`（等待自己的卡片可见）打成红。另一坑：等待条件要等**UI 节点**（`onAllNodesWithContentDescription(...).fetchSemanticsNodes()`），只等 ViewModel 状态会在重组合刷新前返回，随后点击命中旧树。
- **`BackupArchiveLimitTest` 已修（2026-10-06）**：原用例在 512 MiB 测试堆里直接 `ByteArray(MAX_ARCHIVE_BYTES)`，实测 `OutOfMemoryError`（JUnit 报 `expected IllegalArgumentException but was OutOfMemoryError`）——红门禁**什么都没验证到**。里面是两条独立缺陷：① 断言写成 `IllegalArgumentException`，而 `BackupArchive` 的每条拒绝路径都抛 `BackupException(code)`（同目录 `BackupArchiveTest` 对每个 code 都这样断言）；② 用真实 512 MiB 载荷去触边界，小堆必 OOM。**超大备份是用户数据状况，不是编程错误**：`code` 会经 `CommandExecutor.applyGuarded` 的 `catch (error: BackupException) → DomainFailure.RecoveryPointUnavailable(error.code)`（`CommandExecutor.kt:218`）与 `AppViewModel.kt:592` 直接显示，抛别的类型会被 `BackupManager` 的 `catch (Exception) → "ExportFailed"` 改写成错误的 code。修法：`encode`/`readBounded` **保留原签名**并各自委托给带显式上限的 `encodeWithin`/`readBoundedWithin`（`internal`；校验仍是「只读声明长度、且在任何拷贝/加密之前」），测试改用**缩小的上限**真实穿过边界，另断言真实契约数字（`MAX_ARCHIVE_BYTES == 512 MiB`、`MAX_PAYLOAD_BYTES == 上限 − 实测 archive 开销`）。已做变异验证：`>` 改 `>=`、`MAX_PAYLOAD_BYTES` 去掉开销、`total > limitBytes` 改 `* 2` 三种破坏分别被对应断言抓住。`decode`/`crypt`/`makeHeader`/`parseHeader` 与恢复语义未动。

### 时区纪元、单写者与恢复点边界（2026-10-06，已实跑）

- **`zone_epoch_seq` 必须取 `max(epoch_seq)`，不能取行数**：首装并不写 epoch 1 行（`DomainStateLoader.initialiseIfEmpty` 至今无调用者），首次手动改时区生成 `epoch_seq=2` 的**唯一**一行，行数=1 → 载入的 seq 退化成 1 → 第二次改时区再算 2 并 upsert，**覆盖第一行、历史纪元丢失**。守护测试：`app/src/test/java/app/arttodo/system/ZoneEpochCoherenceTest.kt`（`a_second_manual_change_appends_an_epoch_instead_of_overwriting_the_first`）。
- **命令与 UI 的「当前时区」只能来自最新 `zone_epoch` 行**：`DomainStateLoader.load` 返回的 `state.zoneId` 即权威值，`CommandExecutor.prepared()` 用它刷新 `effectiveZoneId`，`AppViewModel.refresh()` 也用它算「今天」。`ZoneProvider.appZoneId()` 只是启动引导值（写 `app_setting.app_zone_id`），`ensureInitialised()` 每次启动按 epoch 表对齐；`app_setting` 指针由 `EffectsApplier` 在处理 `UpsertZoneEpoch` 时**同一事务**写入。旧实现把 provider 缓存当第二真相，手动改时区后改名的标题快照会写成「新纪元 + 旧时区日期」。
- **整库替换必须与命令同一把锁**：`CommandExecutor.exclusive { }`（同一 writer dispatcher + 同一 `Mutex`）是唯一安全入口，`CommandExecutor.dispatch` 的临界区不可重入。守护测试手法（`BackupRestoreWriterExclusionTest`）：把命令卡在 `beforeMutation`（`CompletableDeferred`），此时启动恢复，断言恢复在命令释放锁前**跑不完**，且最终库里只剩恢复点的行；无 gate 时实测 `expected to be false` 为红。
- **日恢复点按应用自然日**：`BackupManager` 注入 `dayLabelOf: suspend (Long) -> String?`，生产用 `AppDayLabel`（epoch 表取时区 → `bridge.dayLabelOf`，不是 `appDateOf`，也不是 UTC 日期）；`null` 直接抛 `RecoveryPointDayUnavailable`，绝不猜一天。声音/震动设置同样走恢复点边界（`RecoveryPointCoordinator.writeSetting`：先捕获后写入，捕获失败不写、不谎报成功）。
- **catch 顺序即契约**：`DataViolationException : IllegalStateException` 与 `BackupException` 都继承 `Exception`，把 `catch (e: Exception)` 写在前面会同时吞掉两者（实测「TitleTooLong」被报成 `Internal`、「RecoveryPointUnavailable」被报成「内部错误」）。正确顺序：具体类型 → `CancellationException`（rethrow）→ `Exception`。`DomainFailure` 新增 Kotlin 侧 `RecoveryPointUnavailable(code)`（契约 §3.4 的 sealed 集需另记）。
- **Robolectric 测试里不要用 `writableDatabase.beginTransaction()` 占住连接再让别的线程读写**：实测线程 dump 卡在 `SQLiteConnectionPool.waitForConnection`（真死锁，测试跑 20 分钟不结束，需 `taskkill` 收尾）。要注入「恢复点捕获失败」就替换 `dayLabelOf` 或 `beforeMutation` 抛 `BackupException`。
- **`BackupManager` 新增参数会改变尾随 lambda 的含义**：`BackupManager(ctx, db) { now }` 原本绑定 `wallClock`，加了 `dayLabelOf` 后变成给 `dayLabelOf` 赋一个 `Long` → 编译错；所有测试改用具名 `wallClock = { … }`。
- **未实现/未验证**：契约 §8.3 的文件级 `app.db.pending` / `app.db.old` / `restore.intent.json` 原子交换与崩溃状态机**没有实现**（现状是 ATTACH staging + 单个 Room 事务整表替换，行级原子性有测试，文件级产物与 ⑨–⑫ 状态机没有）；`AppViewModel.refresh()` 的时区选择只有代码路径证据（`AppContainer` 用 `AppDatabase.get()` 进程单例，不便注入独立库测试）；恢复成功后 `SessionOwner` 的陈旧持有未重新评估（留给后续）。
- **`RecoveryPending` 必须只有一个入账出口，闸门要放在 `state.rs::finish_session`**：`MarkRecoveryPending` 故意保留活动槽（提示需要它），于是 `CompleteTaskWhileRunning`、`FinishSession`（`Now`/`AfterSeconds`）、`StartSession{replaces_session_id}` 三条普通路径都仍把它当作“当前活动会话”，按命令时刻闭合开段并**整段入账**。2026-10-06 用真实核心复现：start@BASE、heartbeat@+600 s、pending@+600 s、普通完成@+3600 s → `booked=3600 session=Finished ledger_rows=1`（可信仅 600 s）。修法：`finish_session` 开头遇到 `SessionState::RecoveryPending` 直接 `PreconditionFailed`（这三个命令都走它，故是唯一通用边界），拒绝时 `reduce` 天然返回 `next_state = state`、空 `effects`。守护测试 `rust/tests/recovery_bypass.rs`（7 例：三命令各自 + 全套普通命令边界 + 改名不产生切片 + `ResolveRecovery` 正例仍入账 600/3600）。`RenameTask` 的分段已被 `state == Running` 判断挡住，别删那个判断。
- **JVM 门禁里的“沙箱遗留活动会话”会伪装成恢复故障**：页面级类 `ComposeAccessibilityTest` 真起会话却不清理，下一个测试类的 `Application.onCreate → warmUp → assessRecovery` 会把它标成 `RecoveryPending`（新进程的 `SessionOwner` 不认识它，符合 AC-09 判据），而下一个类夹具里那句 `finishNow(遗留)` 在修复后必然被拒 → 夹具继续用陈旧活动槽，`startSession` 撞 `ConcurrentSession`，断言以“booked 0”变红。根因在泄漏而不在夹具：给泄漏类补 `@After DELETE FROM task`（级联清会话/切片/账本，与 `TodayCompletionWhileRunningTest` 同一约定）。判定手法：`--tests` 单跑该类绿、与泄漏类两两组合跑红。
- **跑整包 JVM 门禁必须加 `--rerun`**：实测 `testDebugUnitTest` 可能直接 `FROM-CACHE`，输出 `BUILD SUCCESSFUL` 而**一个用例都没执行**（首次实跑 149 例；另外 Gradle 不把 jna 加载的 dylib 当输入，换 Rust 代码后测试任务仍可能命中缓存）。
- **UI 现状（未改）**：待确认会话在专注页有唯一入口（`FocusScreen` 的 `RecoveryPrompt`）；今天页勾选计时中的任务仍会走 `CompleteWhileRunningDialog`，被核心拒绝后只弹 Snackbar「状态已变化，请重试：session … awaits the recovery decision」——不入账但也不完成，且“请重试”对本案不成立、英文 reason 外露。今天页已具备 `UiState.recoveryAmounts`/`recoverySessionId`，改造只需在卡片勾选分支识别 `RECOVERY_PENDING`（未实现，记为可用性缺口）。

### 倒计时结果入口与回前台重读（2026-10-06，已实跑）

- **后台到点的结果页原本不可发现**：`FocusScreen` 只在 `completedCountdownSession.taskId == taskId` 时渲染结果页，而 `SessionRuntimeService` 是直接向核心写库（`CommandExecutor.dispatch` + `FocusReminder.countdownFinished`），既不导航也不通知 ViewModel；从通知点回来只走 `onNewIntent`（`CLEAR_TOP|SINGLE_TOP`），进程内快照永远是旧的。三处接线：今天页 `countdown_result` 入口（`TodayScreen.kt:139`，放在空态/错误**提前 return 之前**）、`AppViewModel.consumeCountdownResult`（清标记 + `refresh()`）、`ArtTodoNavHost` 的 `LifecycleResumeEffect` 回前台重读。
- **【真实缺陷，已修】「继续专注」原本是死路**：结果页第二个分支的 `onContinue` 只设 `continueMinutes`，而分支条件 `state.completedCountdownSession?.taskId == taskId` 仍为真 → 结果页原地重绘，用户永远看不到选择器。清标记必须同时重新投影（`refresh()`），只写 prefs 不够。守护测试 `TodayCountdownResultEntryTest.continuing_from_the_result_requires_another_explicit_start`。
- **页面级测试若在 `setContent` 之前用 `waitUntil` 等状态会必然超时**：没进组合时 `compose.waitForIdle()` 不驱动 Robolectric 主 looper，`viewModelScope` 的续体不恢复（实测 10s 超时）。顺序只能是先 `setContent`，再等 UI 节点/状态。
- **`SessionDao.session(id)` 返回实体，`state` 是 Int 码**（0 运行/1 暂停/2 结束/3 待确认）；断言走 `WorkDomainMappers.toDomain().state`，别写裸 `2`。
- 级联链：`focus_session.task_id → task` 与 `active_session_slot.session_id → focus_session` 都是 `ON DELETE CASCADE`，所以测试类 `@After DELETE FROM task` 足以清掉页面测试留下的运行中会话；结果标记在 prefs（`FocusReminder.PREFS`）里，需要单独 clear。
- 红绿与变异证据（本轮实测，`--tests` 指定 + `--rerun`）：改动前 3 例卡在找不到入口、1 例卡在「继续后仍停在结果页」；改动后 5 例全绿；注释掉 `LifecycleResumeEffect` → 回前台那例红；把入口搬到空态 return 之后 → 空列表那例红。相关 7 个测试类共 43 例绿（整包门禁本轮未跑）。
- **仍未验证**：真机通知点击返回（`SINGLE_TOP`/`onNewIntent`）、锁屏/勿扰/force-stop 下的表现。另有可用性缺口：**应用已在前台时服务结束计时没有 resume 事件**，入口要等下一次 `refresh()`；归档任务的待选会话仍显示入口，但结果页只会给「任务已不在列表」的降级页。

### 死代码清理与工作区整理（2026-10-06，已实跑）

- **本轮删除（全部经全仓引用核查）**：`andro-gates.out`（65.4 MiB 日志，内容是一次 `dir /s /b` 失败把 stderr 重定向进文件）、根目录 `nul`（185 字节，同一次命令的误产物；**Windows 保留设备名，`del nul` 无效，必须用扩展路径 `\\?\D:\...\nul` 通过 Python/.NET 删**）、`ui/focus/FocusImports.tmp`（3199 字节导入清单草稿，不在任何 source set 内）。根 `build/` 只有生成的问题报告，删后 Gradle 会自行重建，不必清理。
- **三个死源文件**：`ui/today/TodayViewModel.kt`（`TodayViewModel`/`TodayUiState` 仅自我引用；其 `describe()` 与 `AppViewModel.kt:645` 的同包重名版本重复，测试与 `FocusScreen` 导入的都是后者）、`system/BackupSettingsActions.kt`、`ui/settings/BackupSettingsControls.kt`（两者被 `system/BackupTransferControls.kt` 取代，`SettingsScreen.kt:75` 已接线新版）。
- **删除的成员**：`Daos.kt` 的 `observeActive`/`observeAll`（连 `kotlinx.coroutines.flow.Flow` 导入一并删）/`maxSortKey`/`updateSortKey`/`allViews`/`maxLedgerSeq`/`allSettings`；`WorkDomainMappers.acceptRecovery`（连 `RecoveryChoice` 导入）；`CommandExecutor.ownsSession`/`ownedSessionId`；`ArtTodoNavHost.topInsetPadding`（连 `statusBars` 导入；`WindowInsets`/`asPaddingValues` 仍被 `navigationBars` 用到，不能删）；`StudioTheme.rememberPaperDrawable`（连 `BitmapDrawable` 导入；`rememberPaperGrain` 是活的，`StudioArt.kt:269` 在用）与 `contentMaxWidth`；`StudioTypography.shortWidthDp`（`widthDp` 自己内联了同一乘法，`longWidthDpPerSp`/`SHORT_LONG_RATIO` 都还要留）；`AppViewModel.todayRowsOf`/`replayTrace`/`setTaskDailyTotal`/`StopAtTarget`（连 `CompletionChoice` 导入）。`strings.xml` 只留 `app_name`（`@string/app_name` 是 manifest 唯一真正引用的名字；其余 11 条含 6 条 `vertical_slice_*` 全仓零引用）。
- **刻意不删（避免误伤）**：① `DomainBridge.replayDailyTrace` 及其 `UniffiDomainBridge` 实现——`AppViewModel.replayTrace` 删除后 Kotlin 侧暂无调用者，但它是契约 §3.2 的「查询」粒度导出、Rust `replay_daily_trace` 仍被 4 个 Rust 测试覆盖，按 §9 保留在 seam 上；② `DomainStateLoader.initialiseIfEmpty` + `SupportDao.maxEpochSeq`——**不是死代码而是未接线需求**：规格 §3/§51 要求首装创建 `AppZoneEpoch`，现由 `ZoneProvider.ensureInitialised` 只写 `app_setting` 指针，首装实际不落 `zone_epoch` 行；已加注释说明并保留（删掉会让这条需求彻底无实现）；③ `StudioTokens` 的 `Space.huge`、`motion.tapMs/pageMs/tapEasing/pageInEasing/pageOutEasing/stretchSpec`——设计系统 §8/§4.1 明文规定的 token 阶梯（`4/8/12/16/20/24/32/40/56/72`），属规格词汇表而非无用代码；④ `TaskEntities.revisionSeq`/`slotId`、`LedgerEntities.art_asset` 的 5 列——Room 实体字段，列在冻结 schema（`app/schemas/.../2.json`）里，删字段会与迁移断言冲突；⑤ `SessionBootReceiver`（只在 `AndroidManifest.xml:40` 注册，文本扫描看不见）；⑥ `FocusScreen`/`RecordScreen` 等的 `@Composable` 顶层函数——同文件内互相调用，扫描会误报。
- **扫描方法的坑（下次直接照用）**：① 纯文本「零引用」扫描对 Kotlin **必然大量假阳性**——同文件内的函数互调、`@Composable` 私有组件、manifest/XML 里注册的类都看不见文本引用；必须按「声明行之外零出现」判定，并把 `.xml`/`manifest`/`.md` 一起纳入语料；② 扫 `Flow`、`hilt`、`Room` 这类**框架注解**时要看意图而非引用数（DAO 接口是给 Room 生成实现用的，方法删留要看调用方而不是注解）；③ 判断前先查 `memory.md` 与 `docs/设计/` 是否把它写成需求——`initialiseIfEmpty` 就是靠 `memory.md:153` 与规格 §3 才没被误删。
- **清理后门禁（2026-10-06 实跑）**：`gradlew.bat --no-daemon testDebugUnitTest --rerun` → `BUILD SUCCESSFUL`，**25 个套件 / 157 例 / 0 失败 / 0 跳过**（`app/build/test-results/testDebugUnitTest/TEST-*.xml` 复核，确认不是缓存空跑）；`gradlew.bat --no-daemon lintDebug` → `BUILD SUCCESSFUL`；`cargo clippy --all-targets` → `Finished dev profile`，零警告（Rust 侧无死代码，`cargo fmt` 仍是 no-op）。`testDebugUnitTest` 用例数较记录里的 149 例增加，是后续测试类追加所致，不是本轮改动产生。
- **本轮一次性脚本已全部删除**（`scripts/test/_*.py`，30 个），只保留三个真门禁：`verify_design_tokens.py`、`verify_art_assets.py`、`verify_prototype_offline.py`。结论写进本文件，脚本不留库。
- **仍未做**：`.zcode/workflow-{drafts,runs}`（22 个文件 / 0.8 MiB，agent 工作流状态）与 `.kotlin/`（空目录）仍在工作区；它们已被各自的 `.gitignore` 或根 `.gitignore` 覆盖，不在交付范围内，故未删。真机项与 NDK 阻塞同前。

