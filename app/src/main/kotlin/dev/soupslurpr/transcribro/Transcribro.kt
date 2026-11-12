package dev.soupslurpr.transcribro

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.dp
import dev.soupslurpr.transcribro.ui.theme.VerbaloTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import dev.soupslurpr.transcribro.ui.theme.VerbaloMotion
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import dev.soupslurpr.transcribro.ui.action_recognize_speech.ActionRecognizeSpeechScreen
import dev.soupslurpr.transcribro.ui.settings.CreditsScreen
import dev.soupslurpr.transcribro.ui.settings.LicenseScreen
import dev.soupslurpr.transcribro.ui.settings.PrivacyPolicyScreen
import dev.soupslurpr.transcribro.ui.settings.SettingsStartScreen
import dev.soupslurpr.transcribro.ui.start.StartScreen
import dev.soupslurpr.transcribro.ui.history.HistoryScreen
import kotlinx.coroutines.launch

enum class TranscribroAppScreens(@StringRes val title: Int) {
    ActionRecognizeSpeech(title = R.string.app_name),

    Start(title = R.string.start),
    StartStart(title = R.string.start),
    History(title = R.string.history),
    HistoryStart(title = R.string.history),
    Settings(title = R.string.settings),
    SettingsStart(title = R.string.settings),
    SettingsLicense(title = R.string.license),
    SettingsPrivacyPolicy(title = R.string.privacy_policy),
    SettingsCredits(title = R.string.credits),
}

/** The first screen of each tab: no back arrow there, the tabs are not a stack. */
private val tabRoots = setOf(
    TranscribroAppScreens.StartStart,
    TranscribroAppScreens.HistoryStart,
    TranscribroAppScreens.SettingsStart,
)

val navBarScreens = listOf(
    TranscribroAppScreens.Start,
    TranscribroAppScreens.History,
    TranscribroAppScreens.Settings,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscribroApp(
    actionApplicationPreferences: Boolean,
    actionRecognizeSpeech: Boolean,
) {
    val navController = rememberNavController()

    val backStackEntry by navController.currentBackStackEntryAsState()

    val currentScreen =
        TranscribroAppScreens.valueOf(backStackEntry?.destination?.route ?: TranscribroAppScreens.Settings.name)

    val snackbarHostState = remember { SnackbarHostState() }

    val snackbarCoroutine = rememberCoroutineScope()

    val navBarSelected = navBarScreens.find {
        currentScreen.name.startsWith(it.name)
    }

    val topAppBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.run {
            var modifier = this
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                .animateContentSize()

            if (actionRecognizeSpeech) {
                modifier = if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT) {
                    modifier.fillMaxHeight(0.4f)
                } else {
                    modifier
                        .fillMaxHeight(0.9f)
                }
            }

            modifier
        },
        topBar = {
            // Home has its own header (mascot and name); a "Home" bar above it says nothing.
            if (currentScreen != TranscribroAppScreens.StartStart) TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = VerbaloTheme.colors.background,
                    scrolledContainerColor = VerbaloTheme.colors.background,
                    titleContentColor = VerbaloTheme.colors.textPrimary,
                    navigationIconContentColor = VerbaloTheme.colors.textPrimary,
                ),
                title = {
                    Text(
                        text = stringResource(id = currentScreen.title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (navController.previousBackStackEntry != null && currentScreen !in tabRoots) {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Navigate up"
                            )
                        }
                    }
                },
                scrollBehavior = topAppBarScrollBehavior
            )
        },
        bottomBar = {
            if (navBarSelected != null) Column {
                HorizontalDivider(color = VerbaloTheme.colors.border)
                NavigationBar(containerColor = VerbaloTheme.colors.surface, tonalElevation = 0.dp) {
                    navBarScreens.forEach { navBarScreen ->
                        NavigationBarItem(
                            selected = navBarScreen == navBarSelected,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = VerbaloTheme.colors.primarySoft,
                                selectedTextColor = VerbaloTheme.colors.primarySoft,
                                indicatorColor = VerbaloTheme.colors.surfaceRaised,
                                unselectedIconColor = VerbaloTheme.colors.textMuted,
                                unselectedTextColor = VerbaloTheme.colors.textMuted,
                            ),
                            onClick = {
                                navController.navigate(navBarScreen.name) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                when (navBarScreen) {
                                    TranscribroAppScreens.Start -> Icon(
                                        imageVector = if (navBarSelected == navBarScreen) {
                                            Icons.Filled.Home
                                        } else {
                                            Icons.Outlined.Home
                                        },
                                        contentDescription = null
                                    )

                                    TranscribroAppScreens.History -> Icon(
                                        imageVector = Icons.Filled.History,
                                        contentDescription = null
                                    )

                                    TranscribroAppScreens.Settings -> Icon(
                                        imageVector = if (navBarSelected == navBarScreen) {
                                            Icons.Filled.Settings
                                        } else {
                                            Icons.Outlined.Settings
                                        },
                                        contentDescription = null
                                    )

                                    else -> {}
                                }
                            },
                            label = { Text(text = stringResource(id = navBarScreen.title)) }
                        )
                    }
                }
            }
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState)
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (actionApplicationPreferences) {
                TranscribroAppScreens.Settings.name
            } else if (actionRecognizeSpeech) {
                TranscribroAppScreens.ActionRecognizeSpeech.name
            } else {
                TranscribroAppScreens.Start.name
            },
            modifier = Modifier.padding(innerPadding),
            enterTransition = { VerbaloMotion.screenEnter(enterRisePx) },
            exitTransition = { VerbaloMotion.screenExit() },
            popEnterTransition = { VerbaloMotion.screenEnter(enterRisePx) },
            popExitTransition = { VerbaloMotion.screenExit() }
        ) {
            composableWithDefaultSlideTransitions(
                route = TranscribroAppScreens.ActionRecognizeSpeech
            ) {
                ActionRecognizeSpeechScreen(
                    showSnackbarError = { message, actionLabel, withDismissAction, duration ->
                        snackbarCoroutine.launch {
                            snackbarHostState.showSnackbar(
                                message,
                                actionLabel,
                                withDismissAction,
                                duration,
                            )
                        }
                    }
                )
            }
            navigationWithDefaultSlideTransitions(
                route = TranscribroAppScreens.Start,
                startDestination = TranscribroAppScreens.StartStart.name,
            ) {
                composableWithDefaultSlideTransitions(
                    route = TranscribroAppScreens.StartStart
                ) {
                    StartScreen()
                }
            }
            navigationWithDefaultSlideTransitions(
                route = TranscribroAppScreens.History,
                startDestination = TranscribroAppScreens.HistoryStart.name,
            ) {
                composableWithDefaultSlideTransitions(
                    route = TranscribroAppScreens.HistoryStart
                ) {
                    HistoryScreen()
                }
            }
            navigationWithDefaultSlideTransitions(
                route = TranscribroAppScreens.Settings,
                startDestination = TranscribroAppScreens.SettingsStart.name,
            ) {
                composableWithDefaultSlideTransitions(
                    route = TranscribroAppScreens.SettingsStart
                ) {
                    SettingsStartScreen(
                        onClickLicense = {
                            navController.navigate(TranscribroAppScreens.SettingsLicense.name)
                        },
                        onClickPrivacyPolicy = {
                            navController.navigate(TranscribroAppScreens.SettingsPrivacyPolicy.name)
                        },
                        onClickCredits = {
                            navController.navigate(TranscribroAppScreens.SettingsCredits.name)
                        }
                    )
                }
                composableWithDefaultSlideTransitions(
                    route = TranscribroAppScreens.SettingsLicense
                ) {
                    LicenseScreen()
                }
                composableWithDefaultSlideTransitions(
                    route = TranscribroAppScreens.SettingsPrivacyPolicy
                ) {
                    PrivacyPolicyScreen()
                }
                composableWithDefaultSlideTransitions(
                    route = TranscribroAppScreens.SettingsCredits
                ) {
                    CreditsScreen {
                        {} // No Rust?
                    }
                }
            }
        }
    }
}

fun getStateNavBarRoute(state: NavBackStackEntry): TranscribroAppScreens? {
    state.destination.route?.let { return TranscribroAppScreens.valueOf(it) }
    return null
}

/** 8 dp in pixels, for the rise of an entering screen (docs/design-language.md, Motion). */
private val enterRisePx: Int get() = (8 * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

/** Every screen enters the same way, tabs included: a fade and an 8 dp rise, no sideways slide. */
@Suppress("UNUSED_PARAMETER")
fun getEnterTransition(initialState: NavBackStackEntry, targetState: NavBackStackEntry): EnterTransition =
    VerbaloMotion.screenEnter(enterRisePx)

@Suppress("UNUSED_PARAMETER")
fun getExitTransition(initialState: NavBackStackEntry, targetState: NavBackStackEntry): ExitTransition =
    VerbaloMotion.screenExit()

fun NavGraphBuilder.composableWithDefaultSlideTransitions(
    route: TranscribroAppScreens,
    arguments: List<NamedNavArgument> = emptyList(),
    deepLinks: List<NavDeepLink> = emptyList(),
    enterTransition: (@JvmSuppressWildcards AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition?)? = null,
    exitTransition: (@JvmSuppressWildcards AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition?)? = null,
    popEnterTransition: (@JvmSuppressWildcards AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition?)? = enterTransition,
    popExitTransition: (@JvmSuppressWildcards AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition?)? = exitTransition,
    sizeTransform: (@JvmSuppressWildcards AnimatedContentTransitionScope<NavBackStackEntry>.() -> SizeTransform?)? = null,
    content: @Composable (AnimatedContentScope.(NavBackStackEntry) -> Unit),
) {
    composable(route.name, arguments, deepLinks, if (enterTransition == null) {
        {
            getEnterTransition(initialState, targetState)
        }
    } else {
        null
    }, if (exitTransition == null) {
        {
            getExitTransition(initialState, targetState)
        }
    } else {
        null
    }, if (popEnterTransition == null) {
        {
            getEnterTransition(initialState, targetState)
        }
    } else {
        null
    }, if (popExitTransition == null) {
        {
            getExitTransition(initialState, targetState)
        }
    } else {
        null
    }, sizeTransform, content)
}

fun NavGraphBuilder.navigationWithDefaultSlideTransitions(
    startDestination: String,
    route: TranscribroAppScreens,
    arguments: List<NamedNavArgument> = emptyList(),
    deepLinks: List<NavDeepLink> = emptyList(),
    enterTransition: (@JvmSuppressWildcards AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition?)? = null,
    exitTransition: (@JvmSuppressWildcards AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition?)? = null,
    popEnterTransition: (@JvmSuppressWildcards AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition?)? = enterTransition,
    popExitTransition: (@JvmSuppressWildcards AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition?)? = exitTransition,
    sizeTransform: (@JvmSuppressWildcards AnimatedContentTransitionScope<NavBackStackEntry>.() -> SizeTransform?)? = null,
    builder: NavGraphBuilder.() -> Unit,
) {
    navigation(startDestination, route.name, arguments, deepLinks, if (enterTransition == null) {
        {
            getEnterTransition(initialState, targetState)
        }
    } else {
        null
    }, if (exitTransition == null) {
        {
            getExitTransition(initialState, targetState)
        }
    } else {
        null
    }, if (popEnterTransition == null) {
        {
            getEnterTransition(initialState, targetState)
        }
    } else {
        null
    }, if (popExitTransition == null) {
        {
            getExitTransition(initialState, targetState)
        }
    } else {
        null
    }, sizeTransform, builder)
}