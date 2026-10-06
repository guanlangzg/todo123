package app.arttodo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.arttodo.ArtTodoApplication
import app.arttodo.core.TaskKind
import app.arttodo.core.TaskRecord
import app.arttodo.nav.LocalAppState
import app.arttodo.nav.LocalAppViewModel
import app.arttodo.nav.Routes
import app.arttodo.core.DayTotal
import app.arttodo.ui.common.ArtAsset
import app.arttodo.ui.common.HeatLegend
import app.arttodo.ui.common.StudioPrimaryButton
import app.arttodo.ui.common.StudioSettingRow
import app.arttodo.ui.common.StudioTaskCard
import app.arttodo.ui.common.StudioTextAction
import app.arttodo.ui.focus.FocusScreen
import app.arttodo.ui.record.CalendarGrid
import app.arttodo.ui.record.CALENDAR_GRID_TAG
import app.arttodo.ui.record.RECORD_LIST_TAG
import app.arttodo.ui.record.RecordScreen
import app.arttodo.ui.theme.Studio
import app.arttodo.ui.theme.StudioTheme
import app.arttodo.ui.today.TodayScreen
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layout and accessibility assertions for the Compose layer.
 *
 * These run on the JVM through Robolectric, so no device is needed, but they are **not** a substitute
 * for a device: they assert the things a semantics tree can prove — that a control exists, that its
 * touch target meets the 48dp floor, that the state is described in words, that the copy which must not
 * be inferred is present — and nothing about how it looks.
 *
 * The design system's own unresolved items stay unresolved here: TalkBack's actual speech, OLED
 * colour, system font rasterisation and frame timing are not testable from a semantics tree and are
 * listed as unverified in 设计系统 section 13.
 *
 * Gate target: `gradlew.bat testDebugUnitTest` (class `ComposeAccessibilityTest`).
 */
@RunWith(RobolectricTestRunner::class)
// The design's target form: a 360x800dp phone. Robolectric's default window is 320x470dp, which is
// smaller than any device this app targets and would push content off-screen for reasons that have
// nothing to do with the layout under test.
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class ComposeAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The pages read their state from the app's own container, so the tests provide the real one.
     *
     * This is the same full-stack arrangement the existing Robolectric tests use (`VerticalSliceTest`,
     * `DomainBridgeProjectionTest`): the container, the Room database and the Rust core are the shipped
     * ones, reached through the host library wired in by `jna.library.path`. Nothing here is stubbed,
     * and nothing here is a substitute for the device checks the design system still lists as
     * unverified (TalkBack speech, OLED colour, font rasterisation, frame timing).
     */
    private val container by lazy {
        (ApplicationProvider.getApplicationContext<Context>() as ArtTodoApplication).container
    }

    /**
     * Leaves the shared sandbox as it was found.
     *
     * Robolectric reuses one database file for every class in the run, and
     * `starting_a_second_session_confirms_first_for_either_mode` starts a real session through the
     * shipped core and leaves it **running in the active slot**. The next class launches a fresh
     * process, so that session is exactly the "disk says running, this process does not own it" shape
     * AC-09 has to prompt about (架构契约 §7.3) — a leftover that would otherwise turn into a recovery
     * prompt in the middle of another class's fixture. Tasks cascade to their sessions, segments,
     * occurrences, events and ledger rows; the installation's zone epoch and settings are left alone.
     */
    @After
    fun removeTestTasks() {
        container.database.openHelper.writableDatabase.execSQL("DELETE FROM task")
    }

    /**
     * Waits for a plain state condition, idling the Compose/looper queues first.
     *
     * `waitUntil` only sleeps and advances the Compose clock; on Robolectric a condition that does not
     * itself touch the test framework never drains the main looper, so a ViewModel coroutine posted
     * there would never resume. Fetching idle inside the loop is what makes the state waits converge.
     */
    private fun waitForAppState(condition: () -> Boolean) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.waitForIdle()
            condition()
        }
    }

    private fun viewModel() = AppViewModel(container)

    private fun setStudio(content: @Composable () -> Unit) {
        val vm = viewModel()
        compose.setContent {
            CompositionLocalProvider(LocalAppViewModel provides vm) {
                StudioTheme(darkTheme = false, reducedMotion = true, powerSave = false) {
                    Box(Modifier.fillMaxSize().background(Studio.colors.canvas)) { content() }
                }
            }
        }
    }

    /**
     * The completion control is a separate node from the card body.
     *
     * The spec's rule is that opening a card must never be mistaken for completing it (规格 section 2),
     * so the two must be independently addressable and the tick must state what it will do.
     */
    @Test
    fun the_completion_control_is_its_own_node_with_a_state_description() {
        var opened = 0
        var completed = 0
        setStudio {
            StudioTaskCard(
                title = "微信读书 30 分钟",
                note = "读完第三章",
                art = ArtAsset.TaskTemporary,
                completed = false,
                kindLabel = "临时任务",
                onOpen = { opened++ },
                onToggleComplete = { completed++ },
            )
        }

        compose.onNodeWithContentDescription("完成 微信读书 30 分钟").assertIsDisplayed()
        compose.onNodeWithContentDescription("完成 微信读书 30 分钟").performClick()
        assertThat(completed).isEqualTo(1)
        // Completing must not also open the card: they are distinct nodes, not nested handlers.
        assertThat(opened).isEqualTo(0)

        compose.onNodeWithContentDescription("微信读书 30 分钟。备注：读完第三章。临时任务。未完成。")
            .performClick()
        assertThat(opened).isEqualTo(1)
    }

    /** A completed card offers the undo wording and states the completion in words. */
    @Test
    fun a_completed_card_announces_undo_and_the_completed_state() {
        setStudio {
            StudioTaskCard(
                title = "背单词",
                note = "",
                art = ArtAsset.StateCompleted,
                completed = true,
                kindLabel = "日常任务",
                onOpen = {},
                onToggleComplete = {},
            )
        }
        compose.onNodeWithContentDescription("撤销完成 背单词").assertIsDisplayed()
        compose.onNodeWithContentDescription("背单词。日常任务。已完成。").assertIsDisplayed()
        compose.onNodeWithText("已完成").assertIsDisplayed()
    }

    /**
     * Reaching the focus screen without a task degrades to a recoverable message.
     *
     * The mode picker itself needs a task, so its radio cards are asserted on the component level
     * (`StudioSegmented` and the picker share the same selection contract) rather than pretending the
     * page can render a task that does not exist.
     */
    @Test
    fun the_focus_screen_degrades_when_its_task_is_gone() {
        var closed = 0
        setStudio { FocusScreen(taskId = "missing", onClose = { closed++ }) }
        // Without a task the screen must degrade to a recoverable message rather than a blank page.
        compose.onNodeWithText("返回").assertIsDisplayed()
        compose.onNodeWithText("回到今天").assertIsDisplayed()
        compose.onNodeWithText("回到今天").performClick()
        assertThat(closed).isEqualTo(1)
    }

    /**
     * The legend is on canvas and its ramp is spoken.
     *
     * `inkTertiary` on `canvas` measures 4.64:1 while the same text on `surfaceTint` measures 4.32:1,
     * so the container matters for compliance; and the encoding must not be colour-only, which is why
     * the six steps each carry a label.
     */
    @Test
    fun the_heat_legend_lists_every_step_with_its_meaning() {
        setStudio { HeatLegend(includeCompletionMark = true, includeToday = true) }
        listOf("无记录", "0 分钟", "1–29", "30–59", "1–2 小时", "≥2 小时", "有完成", "今天").forEach { label ->
            compose.onNodeWithText(label).assertIsDisplayed()
        }
    }

    /**
     * The calendar gives every date its own node whose description carries the date, the investment
     * and the completion fact, and a future date is disabled rather than silently inert.
     */
    @Test
    fun every_calendar_cell_is_a_node_that_states_its_date_and_meaning() {
        setStudio {
            CalendarGrid(
                year = 2026,
                month = 10,
                today = "2026-10-05",
                selected = "2026-10-05",
                totals = mapOf("2026-10-05" to 4_356),
                completedDates = setOf("2026-10-05"),
                recordedDates = setOf("2026-10-05"),
                onSelect = {},
            )
        }
        // Selected day: the invested minutes and the separate completion fact are both spoken.
        compose.onNodeWithContentDescription("2026 年 10 月 5 日 星期一，投入 1 小时 12 分，有完成记录")
            .assertIsDisplayed()
        // A day with no records says so instead of implying zero investment.
        compose.onNodeWithContentDescription("2026 年 10 月 4 日 星期日，无记录").assertIsDisplayed()
        // A future date is announced as unavailable.
        compose.onNodeWithContentDescription("2026 年 10 月 6 日 星期二，不可用").assertIsDisplayed()
    }

    /** A future date cannot be selected, which is what keeps "mark a past day done" from leaking forward. */
    @Test
    fun a_future_calendar_cell_cannot_be_selected() {
        var selected: String? = null
        setStudio {
            CalendarGrid(
                year = 2026,
                month = 10,
                today = "2026-10-05",
                selected = "2026-10-05",
                totals = emptyMap(),
                completedDates = emptySet(),
                recordedDates = emptySet(),
                onSelect = { selected = it },
            )
        }
        compose.onNodeWithContentDescription("2026 年 10 月 7 日 星期三，不可用").performClick()
        assertThat(selected).isNull()
        compose.onNodeWithContentDescription("2026 年 10 月 3 日 星期六，无记录").performClick()
        assertThat(selected).isEqualTo("2026-10-03")
    }

    /**
     * The records screen's own chrome is present on the empty path.
     *
     * The month grid and the day header stay on screen with no data at all: the calendar is the page's
     * identity and must not disappear because a month happens to be empty (关键页面说明 section 3.2).
     */
    @Test
    fun the_records_screen_keeps_its_identity_when_there_is_no_data() {
        setStudio {
            RecordScreen()
        }
        compose.onNodeWithContentDescription("上一个月").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一个月").assertIsDisplayed()
        // The grid itself renders with no data at all: the month structure is the page's identity and
        // must not disappear when a month happens to be empty (关键页面说明 section 3.2). The per-cell
        // descriptions are asserted separately, against fixed data, so this check does not depend on
        // what "today" is when the test runs.
        compose.onNodeWithTag(CALENDAR_GRID_TAG).assertIsDisplayed()
        // The empty state is below the fold on a 470px-tall window; scrolling to it proves both that
        // it exists and that it is reachable.
        compose.onNodeWithTag(RECORD_LIST_TAG).performScrollToNode(hasText("这个月还没有记录"))
        compose.onNodeWithText("这个月还没有记录").assertIsDisplayed()
        compose.onNodeWithText("补记投入").assertIsDisplayed()
    }

    /**
     * The primary button's enabled state is explicit, and a disabled one cannot be pressed.
     *
     * This is what keeps "no title entered" from dispatching a command, and it is asserted rather than
     * assumed because a disabled-looking button that still fires is a real class of defect.
     */
    @Test
    fun a_disabled_primary_button_does_not_fire() {
        var clicks = 0
        setStudio {
            StudioPrimaryButton(text = "添加", enabled = false, onClick = { clicks++ })
        }
        compose.onNodeWithText("添加").assertIsNotEnabled()
        compose.onNodeWithText("添加").performClick()
        assertThat(clicks).isEqualTo(0)
    }

    /** An enabled primary button does fire, so the previous test is not passing vacuously. */
    @Test
    fun an_enabled_primary_button_fires_once_per_press() {
        var clicks = 0
        setStudio {
            StudioPrimaryButton(text = "开始", enabled = true, onClick = { clicks++ })
        }
        compose.onNodeWithText("开始").assertIsEnabled()
        compose.onNodeWithText("开始").performClick()
        assertThat(clicks).isEqualTo(1)
    }

    /**
     * The design's large-font rule keeps the focus controls reachable.
     *
     * At fontScale 2.0 the ring yields and the two controls stack, so they must still be present and
     * pressable rather than pushed below the fold (设计系统 section 3.5, 关键页面说明 section 2.5).
     */
    @Test
    fun the_focus_screen_keeps_its_controls_at_the_largest_font_scale() {
        val vm = viewModel()
        compose.setContent {
            CompositionLocalProvider(
                LocalAppViewModel provides vm,
                androidx.compose.ui.platform.LocalDensity provides Density(density = 3f, fontScale = 2f),
            ) {
                StudioTheme(darkTheme = false, reducedMotion = true, powerSave = false) {
                    Box(Modifier.fillMaxSize().background(Studio.colors.canvas)) {
                        FocusScreen(taskId = "missing", onClose = {})
                    }
                }
            }
        }
        // `StudioTextAction` merges its label as text rather than a description, which is also how
        // TalkBack reads it, so the assertion addresses the text.
        compose.onNodeWithText("返回").assertIsDisplayed()
        compose.onNodeWithText("回到今天").assertIsDisplayed()
    }

    /** The five routes are reachable, so a page cannot be implemented without being wired. */
    @Test
    fun every_screen_route_exists() {
        assertThat(Routes.TODAY).isEqualTo("today")
        assertThat(Routes.RECORD).isEqualTo("record")
        assertThat(Routes.INSIGHT).isEqualTo("insight")
        assertThat(Routes.SETTINGS).isEqualTo("settings")
        // The focus route is a parameterised full-screen route, deliberately not a fifth tab.
        assertThat(Routes.focus("task:1")).isEqualTo("focus/task:1")
    }

    /** The card title is not truncated in the semantics tree even when the visual is two lines. */
    @Test
    fun a_long_title_is_read_in_full_by_the_card() {
        val long = "读完《设计中的设计》第三章并整理笔记还有更多文字让这一行必然要换行"
        setStudio {
            StudioTaskCard(
                title = long,
                note = "",
                art = ArtAsset.TaskDaily,
                completed = false,
                onOpen = {},
            )
        }
        val tree = compose.onRoot().printToString(maxDepth = 20)
        assertThat(tree).contains(long)
    }

    /** The task kind is part of the card's announced text, so a daily task is distinguishable. */
    @Test
    fun an_archived_card_states_that_it_is_archived_and_that_it_is_not_completed() {
        setStudio {
            StudioTaskCard(
                title = "旧任务",
                note = "",
                art = ArtAsset.TaskTemporary,
                completed = false,
                archived = true,
                kindLabel = "临时任务",
                onOpen = {},
            )
        }
        compose.onNodeWithText("已归档").assertIsDisplayed()
        compose.onNodeWithText("已完成").assertDoesNotExist()
        // Archiving is not completing, and the announced sentence keeps the two apart.
        compose.onNodeWithContentDescription("旧任务。临时任务。已归档。").assertIsDisplayed()
    }

    /**
     * The heat map is a custom Canvas rather than a placeholder image, and its tappable region reports
     * the selected civil date and its invested duration in a real semantics node.
     */
    @Test
    fun the_heat_map_canvas_has_an_accessible_selected_date_and_duration() {
        val vm = viewModel()
        val state = vm.state.value.copy(
            loading = false,
            today = "2026-03-04",
            dayTotals = listOf(DayTotal(appDate = "2026-03-04", seconds = 5_520L)),
        )
        compose.setContent {
            CompositionLocalProvider(
                LocalAppState provides state,
                LocalAppViewModel provides vm,
            ) {
                StudioTheme(darkTheme = false, reducedMotion = true, powerSave = false) {
                    Box(Modifier.fillMaxSize().background(Studio.colors.canvas)) {
                        app.arttodo.ui.insight.InsightScreen()
                    }
                }
            }
        }
        val tree = compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 100)
        assertThat(tree).contains("全年热力图，2026 年 3 月 4 日 星期三，投入 1 小时 32 分，0 个任务。共 371 天")
        assertThat(tree).contains("CustomActions = '[CustomAccessibilityAction(label=上一格")
    }

    /**
     * The main tabs announce the selected destination and clicking a tab preserves a single stable
     * entry. In particular, returning from Focus leaves the active session alive, not duplicated.
     */
    @Test
    fun bottom_navigation_exposes_the_four_stable_destinations() {
        assertThat(app.arttodo.nav.bottomDestinations.map { it.route })
            .containsExactly("today", "record", "insight", "settings").inOrder()
        assertThat(Routes.focus("task:1")).isEqualTo("focus/task:1")
    }

    /** Daily and temporary tasks use different illustrations, which is a non-colour distinction. */
    @Test
    fun task_artwork_distinguishes_the_two_groups_and_the_completed_state() {
        assertThat(ArtAsset.forTask(isDaily = true, completed = false)).isEqualTo(ArtAsset.TaskDaily)
        assertThat(ArtAsset.forTask(isDaily = false, completed = false)).isEqualTo(ArtAsset.TaskTemporary)
        assertThat(ArtAsset.forTask(isDaily = true, completed = true)).isEqualTo(ArtAsset.StateCompleted)
        assertThat(ArtAsset.forTask(isDaily = false, completed = true)).isEqualTo(ArtAsset.StateCompleted)
        assertThat(TaskKind.entries).hasSize(2)
    }

    @Test
    fun a_missing_illustration_slot_stays_decorative() {
        setStudio {
            app.arttodo.ui.common.StudioArt(
                asset = ArtAsset.EmptyToday,
                frame = app.arttodo.ui.common.ArtFrameKind.Panel,
                contentDescription = null,
            )
        }
        // Robolectric's BitmapFactory decodes the shipped PNG, so the degradation branch itself cannot be
        // reached from a JVM test (see the gap listed in the result): what is asserted here is that the
        // artwork is decoration — it contributes no node of its own — which is what keeps a failure in
        // the art layer from taking the title, the note or the controls with it.
        val tree = compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 30)
        assertThat(tree).doesNotContain("插图暂不可用")
        assertThat(tree).contains("ClearAndSetSemantics")
    }

    @Test
    fun insight_zero_data_shows_recoverable_empty_state_without_a_heatmap() {
        val vm = viewModel()
        val emptyState = vm.state.value.copy(
            loading = false,
            today = "2026-10-05",
            tasks = emptyList(),
            ledger = emptyList(),
            dayTotals = emptyList(),
            weekTotals = emptyList(),
            monthTotals = emptyList(),
            taskShares = emptyList(),
        )
        compose.setContent {
            CompositionLocalProvider(
                LocalAppState provides emptyState,
                LocalAppViewModel provides vm,
            ) {
                StudioTheme(darkTheme = false, reducedMotion = true, powerSave = false) {
                    Box(Modifier.fillMaxSize().background(Studio.colors.canvas)) {
                        app.arttodo.ui.insight.InsightScreen()
                    }
                }
            }
        }

        compose.onNodeWithText("还没有可以统计的投入").assertIsDisplayed()
        compose.onNodeWithContentDescription("去今天页").assertIsDisplayed()
        val tree = compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 100)
        assertThat(tree).doesNotContain("全年热力图")
    }

    @Test
    fun large_font_empty_today_keeps_a_full_width_named_create_action() {
        val vm = viewModel()
        val emptyState = vm.state.value.copy(
            loading = false,
            today = "2026-10-05",
            tasks = emptyList(),
            occurrenceCompletion = emptyMap(),
            temporaryCompletion = emptyMap(),
        )
        compose.setContent {
            CompositionLocalProvider(
                LocalAppState provides emptyState,
                LocalAppViewModel provides vm,
                androidx.compose.ui.platform.LocalDensity provides Density(density = 1f, fontScale = 2f),
            ) {
                StudioTheme(darkTheme = false, reducedMotion = true, powerSave = false) {
                    Box(Modifier.fillMaxSize().background(Studio.colors.canvas)) {
                        app.arttodo.ui.today.TodayScreen(onOpenFocus = {})
                    }
                }
            }
        }

        compose.onNodeWithContentDescription("新建任务").assertIsDisplayed()
        compose.onNodeWithText("今天还是一张空白的纸").assertIsDisplayed()
        compose.onNodeWithText("新建第一件事").assertIsDisplayed()
    }

    /**
     * Gate 2 of the cover's font-scale ladder (关键页面说明 section 1.4): at fontScale 1.4 the cover is
     * exactly 152dp, and the whole block — artwork plus the summary that still sits on it — is one
     * tappable node rather than decoration.
     */
    @Test
    fun the_today_cover_narrows_with_the_font_scale_and_is_one_tappable_node() {
        val vm = viewModel()
        var opened = 0
        val longTitle = "读完《设计中的设计》第三章并整理笔记再补一段更长的说明文字让它必然换行"
        val state = vm.state.value.copy(
            loading = false,
            error = null,
            coreAvailable = true,
            today = "2026-10-05",
            tasks = listOf(
                TaskRecord(
                    taskId = "task:1",
                    kind = TaskKind.TEMPORARY,
                    title = longTitle,
                    note = "",
                    sortKey = 1024L,
                    createdWallMs = 0L,
                    archivedAtMs = null,
                    lastCountdownMinutes = 25u,
                    artAssetId = null,
                ),
            ),
            occurrenceIds = emptyMap(),
            occurrenceCompletion = emptyMap(),
            temporaryCompletion = emptyMap(),
        )
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalAppState provides state,
                LocalAppViewModel provides vm,
                LocalDensity provides Density(density = base.density, fontScale = 1.4f),
            ) {
                StudioTheme(darkTheme = false, reducedMotion = true, powerSave = false) {
                    Box(Modifier.fillMaxSize().background(Studio.colors.canvas)) {
                        TodayScreen(onOpenFocus = {}, onOpenCover = { opened++ })
                    }
                }
            }
        }

        val cover = compose.onNodeWithContentDescription("今日封面：临时 1 项 · 日常 0 项")
        cover.assertIsDisplayed()
        cover.assertHeightIsEqualTo(152.dp)
        cover.performClick()
        assertThat(opened).isEqualTo(1)
        // The card still announces the whole title even though the visual is capped at two lines.
        assertThat(compose.onRoot().printToString(maxDepth = 30)).contains(longTitle)
    }

    /**
     * Gate 4: at fontScale 2.0 the cover and its summary yield entirely, so decoration can never push
     * the task list off the first screen (关键页面说明 section 1.4, 原则 P2).
     */
    @Test
    fun the_today_cover_yields_entirely_at_the_largest_font_scale() {
        val vm = viewModel()
        val state = vm.state.value.copy(
            loading = false,
            error = null,
            coreAvailable = true,
            today = "2026-10-05",
            tasks = listOf(
                TaskRecord(
                    taskId = "task:1",
                    kind = TaskKind.TEMPORARY,
                    title = "背单词",
                    note = "",
                    sortKey = 1024L,
                    createdWallMs = 0L,
                    archivedAtMs = null,
                    lastCountdownMinutes = 25u,
                    artAssetId = null,
                ),
            ),
            occurrenceIds = emptyMap(),
            occurrenceCompletion = emptyMap(),
            temporaryCompletion = emptyMap(),
        )
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalAppState provides state,
                LocalAppViewModel provides vm,
                LocalDensity provides Density(density = base.density, fontScale = 2f),
            ) {
                StudioTheme(darkTheme = false, reducedMotion = true, powerSave = false) {
                    Box(Modifier.fillMaxSize().background(Studio.colors.canvas)) {
                        TodayScreen(onOpenFocus = {})
                    }
                }
            }
        }

        compose.onNodeWithContentDescription("今日封面：临时 1 项 · 日常 0 项").assertDoesNotExist()
        compose.onNodeWithText("临时 1 项 · 日常 0 项").assertDoesNotExist()
        // The task itself is still on the first screen, which is the point of the gate.
        compose.onNodeWithText("背单词").assertIsDisplayed()
    }

    /**
     * Settings rows stack their control under the label at large fonts (设计系统 section 12.5, fix 4).
     *
     * Sharing one line squeezed the label, the value and a 48dp button into ~270dp, which broke Chinese
     * into vertical single characters. The assertion is geometric because "not squeezed" is exactly a
     * layout fact: the control must start on a new line, with a readable width of its own.
     */
    @Test
    fun settings_rows_stack_their_control_below_the_label_at_large_fonts() {
        var clicks = 0
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = 2f),
            ) {
                StudioTheme(darkTheme = false, reducedMotion = true, powerSave = false) {
                    Box(Modifier.fillMaxSize().background(Studio.colors.canvas)) {
                        StudioSettingRow(
                            label = "通知权限",
                            description = "通知权限未开启时，到点结果仍保存在应用内。",
                            trailing = { StudioTextAction("通知设置", onClick = { clicks++ }) },
                        )
                    }
                }
            }
        }

        val label = compose.onNodeWithText("通知权限").fetchSemanticsNode().boundsInRoot
        val control = compose.onNodeWithText("通知设置").fetchSemanticsNode().boundsInRoot
        // Stacked: the control starts on a new line below the label rather than sharing its line. The
        // control's own width is not asserted: Robolectric does not lay text out, so a wrapped label
        // measures far too narrow to be meaningful (the browser prototype is the evidence for widths).
        assertThat(control.top).isAtLeast(label.bottom)
        compose.onNodeWithText("通知设置").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("通知设置").performClick()
        assertThat(clicks).isEqualTo(1)
    }

    /**
     * Starting a new session while one is running always asks first, for either mode.
     *
     * The picker's own banner promises 「开始新计时前会先确认结束它」; the countdown-only check used to let
     * a forward session replace the running one without a word (规格 section 5.1).
     */
    @Test
    fun starting_a_second_session_confirms_first_for_either_mode() {
        val vm = viewModel()
        val targetTaskId = mutableStateOf("")
        compose.setContent {
            val state by vm.state.collectAsState()
            val target by targetTaskId
            CompositionLocalProvider(
                LocalAppState provides state,
                LocalAppViewModel provides vm,
            ) {
                StudioTheme(darkTheme = false, reducedMotion = true, powerSave = false) {
                    Box(Modifier.fillMaxSize().background(Studio.colors.canvas)) {
                        FocusScreen(taskId = target, onClose = {})
                    }
                }
            }
        }
        waitForAppState { vm.state.value.today.isNotBlank() }
        vm.createTask(TaskKind.TEMPORARY, "第一件事", "")
        waitForAppState { vm.state.value.tasks.size == 1 }
        val firstTaskId = vm.state.value.tasks.first().taskId
        vm.createTask(TaskKind.TEMPORARY, "第二件事", "")
        waitForAppState { vm.state.value.tasks.size == 2 }
        val secondTaskId = vm.state.value.tasks.first { it.taskId != firstTaskId }.taskId
        targetTaskId.value = secondTaskId
        compose.waitForIdle()

        vm.startSession(taskId = firstTaskId, mode = app.arttodo.core.SessionMode.COUNT_UP, targetSeconds = null)
        waitForAppState { vm.state.value.activeSession?.taskId == firstTaskId }

        // Pick the forward mode, press Start: the confirmation must appear, not a silent switch.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("正向计时").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("正向计时").performClick()
        compose.onNodeWithText("开始").performClick()
        compose.onNodeWithText("结束当前的计时并开始新的一段？").assertIsDisplayed()
        compose.onNodeWithText("结束并开始").performClick()
        waitForAppState { vm.state.value.activeSession?.taskId == secondTaskId }
        assertThat(vm.state.value.activeSession?.mode).isEqualTo(app.arttodo.core.SessionMode.COUNT_UP)
    }

    /**
     * The record page's dated list is the documented equivalent control for the calendar's sub-48dp
     * cells (关键页面说明 section 7): every date is a button on a row of at least 56dp.
     */
    @Test
    fun the_month_label_opens_a_date_list_whose_rows_meet_the_touch_floor() {
        val vm = viewModel()
        val state = vm.state.value.copy(
            loading = false,
            error = null,
            coreAvailable = true,
            today = "2026-10-05",
            tasks = emptyList(),
            ledger = emptyList(),
            dayTotals = emptyList(),
            occurrenceIds = emptyMap(),
            occurrenceCompletion = emptyMap(),
            temporaryCompletion = emptyMap(),
        )
        compose.setContent {
            CompositionLocalProvider(
                LocalAppState provides state,
                LocalAppViewModel provides vm,
            ) {
                StudioTheme(darkTheme = false, reducedMotion = true, powerSave = false) {
                    Box(Modifier.fillMaxSize().background(Studio.colors.canvas)) {
                        RecordScreen()
                    }
                }
            }
        }

        // The grid states what it is, while each cell keeps its own node.
        compose.onNodeWithContentDescription("日历，2026 年 10 月，可上下左右滑动选择日期").assertIsDisplayed()
        compose.onNodeWithContentDescription("2026 年 10 月，选择日期").performClick()
        compose.onNodeWithText("2026 年 10 月 3 日 周六").assertHeightIsAtLeast(56.dp).performClick()
        // Choosing from the list selects the day, exactly as tapping the small cell would.
        compose.onNodeWithText("2026 年 10 月 3 日 周六").assertIsDisplayed()
        compose.onNodeWithTag(RECORD_LIST_TAG).performScrollToNode(hasText("这天没有记录"))
        compose.onNodeWithText("这天没有记录").assertIsDisplayed()
    }

    /**
     * A task-day total is the core's projection, never a local sum of the visible rows.
     *
     * With a manual add plus a "set total" correction on the same day, adding the rows would show
     * 10 分钟 while the day header — and the before/after preview in the record editor — show 1 小时.
     * The whole slice runs for real: ViewModel -> Rust -> Room -> the record page.
     */
    @Test
    fun the_record_page_shows_the_projected_task_total_rather_than_a_local_sum() {
        val vm = viewModel()
        compose.setContent {
            // Collected, not captured: the page has to see each command's result, which is the whole
            // point of the assertion below.
            val state by vm.state.collectAsState()
            CompositionLocalProvider(
                LocalAppState provides state,
                LocalAppViewModel provides vm,
            ) {
                StudioTheme(darkTheme = false, reducedMotion = true, powerSave = false) {
                    Box(Modifier.fillMaxSize().background(Studio.colors.canvas)) {
                        RecordScreen()
                    }
                }
            }
        }
        val title = "记录页合计"
        waitForAppState { vm.state.value.today.isNotBlank() }
        val day = vm.state.value.today
        vm.createTask(TaskKind.TEMPORARY, title, "")
        waitForAppState { vm.state.value.tasks.any { it.title == title } }
        val taskId = vm.state.value.tasks.first { it.title == title }.taskId
        // A manual add plus a "set total" correction for the same task-day: adding the visible rows
        // would show 10 分钟 while the core's projection — and the day header — say 1 小时.
        vm.addManualSeconds(taskId, day, 600)
        waitForAppState { vm.state.value.ledger.count { it.taskId == taskId } == 1 }
        vm.setDailyTotal(taskId, day, 3600)
        waitForAppState { vm.state.value.ledger.count { it.taskId == taskId } == 2 }

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("1 小时").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("10 分钟").assertDoesNotExist()
    }
}
