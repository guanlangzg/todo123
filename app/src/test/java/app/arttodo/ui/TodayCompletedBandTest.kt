package app.arttodo.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.arttodo.ArtTodoApplication
import app.arttodo.core.TaskKind
import app.arttodo.data.DomainCommands
import app.arttodo.nav.LocalAppState
import app.arttodo.nav.LocalAppViewModel
import app.arttodo.ui.today.TodayScreen
import app.arttodo.ui.theme.StudioTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class TodayCompletedBandTest {

    @get:Rule
    val compose = createComposeRule()

    private val application =
        ApplicationProvider.getApplicationContext<Context>() as ArtTodoApplication

    private val container get() = application.container

    @After
    fun removeTestTasks() {
        container.database.openHelper.writableDatabase.execSQL("DELETE FROM task")
    }

    @Test
    fun expanding_the_completed_band_displays_completed_tasks(): Unit = runBlocking {
        container.database.openHelper.writableDatabase.execSQL("DELETE FROM task")
        val executor = container.commandExecutor
        executor.dispatch(DomainCommands.createTask(TaskKind.TEMPORARY, "完成区展开回归", ""))
        val taskId = executor.readState().tasks.single { it.title == "完成区展开回归" }.taskId
        executor.dispatch(DomainCommands.completeTemporary(taskId))

        val viewModel = AppViewModel(container)
        compose.setContent {
            val state by viewModel.state.collectAsState()
            CompositionLocalProvider(
                LocalAppState provides state,
                LocalAppViewModel provides viewModel,
            ) {
                StudioTheme(darkTheme = false, reducedMotion = true, powerSave = false) {
                    TodayScreen(onOpenFocus = {})
                }
            }
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithContentDescription("已完成 1 项，已收起")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.onNodeWithContentDescription("已完成 1 项，已收起").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("完成区展开回归").assertIsDisplayed()
    }
}
