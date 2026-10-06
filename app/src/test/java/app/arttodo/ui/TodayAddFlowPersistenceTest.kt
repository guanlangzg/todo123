package app.arttodo.ui

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import app.arttodo.ArtTodoApplication
import app.arttodo.data.AppDatabase
import app.arttodo.nav.LocalAppState
import app.arttodo.nav.LocalAppViewModel
import app.arttodo.nav.LocalSnackbarHostState
import app.arttodo.ui.theme.StudioTheme
import app.arttodo.ui.today.TodayScreen
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The vertical slice driven the way a user drives it: an actual on-screen button.
 *
 * `VerticalSliceTest` proves command -> Rust -> Room -> reload, but enters at the dispatcher. This
 * one enters at the UI, so the "a button was pressed" half of the slice is covered too:
 *
 *   FAB + 添加 button -> AppViewModel -> CommandExecutor -> real UniFFI/Rust core
 *                    -> effects -> Room transaction -> a new connection reads it back
 *
 * Nothing is stubbed: the ViewModel is the shipped one, the native library is the real host build
 * wired in through `jna.library.path`, and the database is the production `AppDatabase.build`.
 *
 * `@Config`'s window matches the design target, because the create sheet sits at the bottom of the
 * page and a smaller window would push its confirm button out of the composition rather than out of
 * the layout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class TodayAddFlowPersistenceTest {

    @get:Rule
    val compose = createComposeRule()

    private val application =
        ApplicationProvider.getApplicationContext<Context>() as ArtTodoApplication

    /** A title that cannot collide with a fixture and is easy to find in the semantics tree. */
    private val title = "界面新增·重启仍可读"

    private fun renderTodayWithTheShippedViewModel() {
        val viewModel = AppViewModel(application.container)
        compose.setContent {
            val state by viewModel.state.collectAsState()
            val snackbar = remember { SnackbarHostState() }
            CompositionLocalProvider(
                LocalAppState provides state,
                LocalAppViewModel provides viewModel,
                LocalSnackbarHostState provides snackbar,
            ) {
                StudioTheme(darkTheme = false, reducedMotion = true, powerSave = false) {
                    TodayScreen(onOpenFocus = {})
                }
            }
        }
    }

    @Test
    fun pressing_add_on_the_today_screen_writes_through_rust_and_survives_a_reopen(): Unit =
        runBlocking {
            renderTodayWithTheShippedViewModel()

            // 1. The on-screen control that opens the create sheet.
            compose.onNodeWithContentDescription("新建任务").performClick()
            compose.waitForIdle()

            // 2. Fill the title field and confirm with the sheet's primary button. The title is
            //    chosen by Kotlin; the id the row ends up with is minted by the domain core.
            compose.onAllNodes(hasSetTextAction())[0].performTextInput(title)
            compose.waitForIdle()
            compose.onNodeWithText("添加").performClick()

            // 3. The task only reaches the screen after Rust applied the command and Room committed.
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
            }

            // 4. The committed row carries an id the domain core minted, not one Kotlin invented.
            val database = application.container.database
            val created = database.taskDao().all().singleOrNull { it.title == title }
            assertThat(created).isNotNull()
            assertThat(created!!.taskId).startsWith("task:")

            // 5. "Restart": read the same database file back through a brand-new handle, so the
            //    assertion cannot be satisfied by anything still in the writing connection's memory.
            //
            //    The container's handle is deliberately left open and the file is found from it
            //    rather than rebuilt by name: Robolectric reuses one sandbox across test classes, so
            //    the process-wide `AppDatabase` singleton can belong to an earlier class and point at
            //    a different directory than `getDatabasePath(DATABASE_NAME)` resolves to now.
            val path = requireNotNull(database.openHelper.writableDatabase.path)
            SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { file ->
                file.query(
                    "task",
                    arrayOf("task_id", "title"),
                    "title = ?",
                    arrayOf(title),
                    null,
                    null,
                    null,
                ).use { cursor ->
                    assertThat(cursor.count).isEqualTo(1)
                    cursor.moveToFirst()
                    assertThat(cursor.getString(0)).isEqualTo(created.taskId)
                }
            }
        }
}
