package app.arttodo.nav

/**
 * The route table. Owner: ui-navigation (工程布局与版本锁定.md §3) — pages only implement their own
 * composable and never add edges here themselves.
 *
 * Four bottom destinations plus the full-screen focus route, which deliberately does not occupy a
 * fifth slot (架构契约 §9).
 */
object Routes {
    const val TODAY = "today"
    const val RECORD = "record"
    const val INSIGHT = "insight"
    const val SETTINGS = "settings"

    /** Full-screen modal route, reached from a task card rather than the bottom bar. */
    const val FOCUS = "focus/{taskId}"

    fun focus(taskId: String): String = "focus/$taskId"

    const val ARG_TASK_ID = "taskId"
}

/** The bottom-bar targets, in display order. */
enum class BottomDestination(val route: String) {
    Today(Routes.TODAY),
    Record(Routes.RECORD),
    Insight(Routes.INSIGHT),
    Settings(Routes.SETTINGS),
}

/** Start destination of the graph. */
const val START_DESTINATION: String = Routes.TODAY
