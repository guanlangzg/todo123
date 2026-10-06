package app.arttodo.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.arttodo.ArtTodoApplication
import app.arttodo.ui.AppViewModel
import app.arttodo.ui.UiState
import app.arttodo.ui.insight.InsightScreen
import app.arttodo.ui.record.RecordScreen
import app.arttodo.ui.settings.SettingsScreen
import app.arttodo.ui.today.TodayScreen
import app.arttodo.ui.focus.FocusScreen
import app.arttodo.ui.theme.Radius
import app.arttodo.ui.theme.Studio
import app.arttodo.ui.theme.StudioIcon
import app.arttodo.ui.theme.StudioIconView
import app.arttodo.ui.theme.Space
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * The shared state holder, reachable from every destination without prop-drilling.
 *
 * The default is an empty state rather than an error: a screen rendered outside the graph (a preview,
 * a component test) must degrade to its empty presentation instead of crashing.
 */
val LocalAppState = staticCompositionLocalOf { UiState(loading = false) }

/** The one ViewModel instance the graph runs on; the focus route reads the same active session. */
val LocalAppViewModel = staticCompositionLocalOf<AppViewModel> { error("AppViewModel not provided") }

/** Snackbars carry the undo affordance, so every screen needs the host state. */
val LocalSnackbarHostState = staticCompositionLocalOf<SnackbarHostState> { SnackbarHostState() }
val LocalSnackbarHeight = staticCompositionLocalOf { 0.dp }
val LocalNavigateToToday = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun ArtTodoNavHost(
    navController: NavHostController = rememberNavController(),
    viewModel: AppViewModel = rememberDefaultViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Coming back to the app is how a background countdown becomes visible: the timer's service
    // finishes the session by writing to the core directly, so this snapshot stays stale until it is
    // read again. A notification tap reuses this activity — it resumes rather than launches — which is
    // why the re-read belongs to the graph's resume and not to a fresh start (AC-07 / AC-17).
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    // Commands that fail explain themselves in the Snackbar and leave the screen untouched. Reduced
    // motion keeps the message but drops the transition.
    LaunchedEffect(state.error) {
        state.error?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.dismissError()
        }
    }
    LaunchedEffect(state.notices) {
        state.notices.forEach { notice ->
            val affected = notice.affectedAppDate
            val total = notice.resultingTotalSeconds
            val text = if (affected != null && total != null) {
                "$affected 的总投入变为 ${app.arttodo.ui.common.Format.minutes(total)}"
            } else {
                notice.detail.ifBlank { notice.code }
            }
            snackbarHostState.showSnackbar(text)
        }
        if (state.notices.isNotEmpty()) viewModel.consumeNotices()
    }

    val navigateToToday = {
        navController.navigate(Routes.TODAY) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    // One tab, one entry, state kept. The bottom bar, the rail and today's cover all go through here.
    val navigateToTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    CompositionLocalProvider(
        LocalAppState provides state,
        LocalAppViewModel provides viewModel,
        LocalSnackbarHostState provides snackbarHostState,
        LocalNavigateToToday provides navigateToToday,
    ) {
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = backStackEntry?.destination
        val focusRoute = currentDestination?.route?.startsWith("focus") == true
        var snackbarHeight by remember { mutableStateOf(0.dp) }
        val density = LocalDensity.current

        Box(Modifier.fillMaxSize()) {
            // The focus screen is full-bleed; the four tab routes share the bottom navigation and the
            // 640dp reading column (设计系统 section 4.2).
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth > Space.maxContentWidth
                CompositionLocalProvider(LocalSnackbarHeight provides snackbarHeight) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .widthIn(max = Space.maxContentWidth)
                        .align(Alignment.TopCenter),
                ) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        if (!focusRoute && wide) {
                            SideNavigation(
                                currentRoute = currentDestination?.route,
                                onSelect = { destination -> navigateToTab(destination.route) },
                            )
                        }
                        NavHost(
                            navController = navController,
                            startDestination = START_DESTINATION,
                            modifier = Modifier.weight(1f),
                        ) {
                        composable(Routes.TODAY) {
                            TodayScreen(
                                onOpenFocus = { navController.navigate(Routes.focus(it)) },
                                onOpenCover = { navigateToTab(Routes.RECORD) },
                            )
                        }
                        composable(Routes.RECORD) { RecordScreen() }
                        composable(Routes.INSIGHT) { InsightScreen() }
                        composable(Routes.SETTINGS) { SettingsScreen() }
                        composable(Routes.FOCUS) { entry ->
                            FocusScreen(
                                taskId = entry.arguments?.getString(Routes.ARG_TASK_ID).orEmpty(),
                                onClose = { navController.popBackStack() },
                            )
                        }
                        }
                    }
                    if (!focusRoute && !wide) {
                        BottomNavigation(
                            currentRoute = currentDestination?.route,
                            onSelect = { destination -> navigateToTab(destination.route) },
                        )
                    }
                }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                            if (focusRoute) Space.xxl else Space.minTouch + Space.xxl,
                    )
                    .padding(horizontal = Space.l)
                    .onSizeChanged {
                        snackbarHeight = if (snackbarHostState.currentSnackbarData != null) {
                            with(density) { it.height.toDp() }
                        } else {
                            0.dp
                        }
                    },
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = Studio.colors.ink,
                    contentColor = Studio.colors.canvas,
                    shape = RoundedCornerShape(Radius.md),
                )
            }
        }

        // The system back gesture must never drop data, and it must not leave a screen silently: on a
        // sub-state (a sheet, a confirm dialog) the screen itself handles back; here the graph only
        // guarantees the focus route pops instead of finishing the activity.
        BackHandler(enabled = focusRoute) { navController.popBackStack() }
    }
}

@Composable
private fun rememberDefaultViewModel(): AppViewModel {
    val context = LocalContext.current
    val application = context.applicationContext as ArtTodoApplication
    val factory = remember(application) { AppViewModel.factory(application.container) }
    return viewModel(factory = factory)
}

/** 80dp bar plus the navigation-bar inset; every item is far above the 48dp minimum. */
@Composable
private fun SideNavigation(
    currentRoute: String?,
    onSelect: (BottomDestination) -> Unit,
) {
    val colors = Studio.colors
    NavigationRail(
        modifier = Modifier.width(96.dp).fillMaxSize().background(colors.surfaceRaised),
        containerColor = colors.surfaceRaised,
    ) {
        BottomDestination.entries.forEach { destination ->
            val selected = currentRoute?.startsWith(destination.route) == true
            val label = labelFor(destination)
            NavigationRailItem(
                selected = selected,
                onClick = { onSelect(destination) },
                icon = {
                    StudioIconView(
                        icon = iconFor(destination),
                        tint = if (selected) colors.accent else colors.inkTertiary,
                        size = 28.dp,
                        filled = selected,
                    )
                },
                label = { Text(label, style = Studio.text.labelM, color = if (selected) colors.accent else colors.inkTertiary) },
                modifier = Modifier.heightIn(min = 80.dp).semantics {
                    role = Role.Tab
                    this.selected = selected
                    contentDescription = if (selected) "$label，已选中" else label
                },
                alwaysShowLabel = true,
            )
        }
    }
}

@Composable
private fun BottomNavigation(
    currentRoute: String?,
    onSelect: (BottomDestination) -> Unit,
) {
    val colors = Studio.colors
    // 横屏与大屏用 NavigationRail（关键页面说明 section 0.1）。竖屏手机是目标形态，先做栏；
    // the rail branch is kept explicit rather than silently ignored.
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceRaised),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(80.dp)
                .padding(
                    bottom = 0.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomDestination.entries.forEach { destination ->
                val selected = currentRoute?.startsWith(destination.route) == true
                val label = labelFor(destination)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(80.dp)
                        .clickable(role = Role.Tab, onClick = { onSelect(destination) })
                        .semantics {
                            role = Role.Tab
                            this.selected = selected
                            contentDescription = if (selected) "$label，已选中" else label
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    StudioIconView(
                        icon = iconFor(destination),
                        tint = if (selected) colors.accent else colors.inkTertiary,
                        size = 28.dp,
                        filled = selected,
                    )
                    Text(
                        text = label,
                        style = Studio.text.labelM,
                        color = if (selected) colors.accent else colors.inkTertiary,
                        modifier = Modifier.padding(top = Space.xs),
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        )
    }
}

private fun labelFor(destination: BottomDestination): String = when (destination) {
    BottomDestination.Today -> "今天"
    BottomDestination.Record -> "记录"
    BottomDestination.Insight -> "洞察"
    BottomDestination.Settings -> "设置"
}

private fun iconFor(destination: BottomDestination): StudioIcon = when (destination) {
    BottomDestination.Today -> StudioIcon.Today
    BottomDestination.Record -> StudioIcon.Records
    BottomDestination.Insight -> StudioIcon.Insights
    BottomDestination.Settings -> StudioIcon.Settings
}

/** Kept for tests that assert the route contract rather than the pixels. */
val bottomDestinations: List<BottomDestination> = BottomDestination.entries.toList()
