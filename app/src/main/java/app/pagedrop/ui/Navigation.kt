/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.pagedrop.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import app.pagedrop.ui.connection.ConnectionScreen
import app.pagedrop.ui.onboarding.KindleReadyScreen
import app.pagedrop.ui.onboarding.SetupScreen
import app.pagedrop.ui.settings.BookSourcesScreen
import app.pagedrop.ui.settings.SettingsScreen
import app.pagedrop.ui.shell.MainShell
import app.pagedrop.ui.shell.ShellViewModel
import app.pagedrop.ui.tools.articles.ArticlesScreen
import app.pagedrop.ui.tools.dictionaries.DictionariesScreen
import app.pagedrop.ui.tools.fonts.FontsScreen
import app.pagedrop.ui.tools.screensavers.ScreensaverScreen
import app.pagedrop.ui.tools.sync.SyncScreen

private val fadeOnly: ContentTransform =
    fadeIn(tween(120)) togetherWith fadeOut(tween(120))

/**
 * Navigation map:
 *  - first run (no Kindle configured): Setup -> Main
 *  - Main = tab shell (Library | Kindle | Extras) with the bottom bar
 *  - Settings, Setup, Connection, Sync, Screensaver, Fonts, Dictionaries, Articles push full-screen
 *    over Main (bottom bar hidden) with a back arrow.
 */
@Composable
fun MainNavigation(shellViewModel: ShellViewModel = hiltViewModel()) {
    val start: NavKey = remember { if (shellViewModel.needsFirstRunSetup) Setup else Main }
    val backStack = rememberNavBackStack(start)
    val pop: () -> Unit = { backStack.removeLastOrNull() }
    val push: (NavKey) -> Unit = { backStack.add(it) }

    NavDisplay(
        backStack = backStack,
        onBack = { pop() },
        // Plain, quick cross-fade everywhere (e-ink friendly): no slide, and no scale-down on back.
        transitionSpec = { fadeOnly },
        popTransitionSpec = { fadeOnly },
        predictivePopTransitionSpec = { fadeOnly },
        entryProvider = entryProvider {
            entry<Main> {
                MainShell(
                    onOpenSettings = { push(Settings) },
                    onOpenSetup = { push(Setup) },
                    onOpenConnection = { push(Connection) },
                    onOpenSync = { push(Sync) },
                    onOpenArticle = { push(Articles) },
                    onOpenScreensaver = { push(Screensaver) },
                    onOpenFonts = { push(Fonts) },
                    onOpenDictionaries = { push(Dictionaries) },
                    onOpenBookSources = { push(BookSources) },
                    onOpenKindleReady = { push(KindleReady) }
                )
            }
            entry<Setup> {
                // Computed once at entry creation: the back stack changes during the exit animation.
                val firstRun = remember { Main !in backStack }
                val finish: () -> Unit = {
                    if (firstRun) {
                        backStack.add(Main)
                        backStack.remove(Setup)
                    } else {
                        pop()
                    }
                }
                SetupScreen(
                    canGoBack = !firstRun,
                    onBack = pop,
                    onDone = finish,
                    onSkip = finish,
                    onOpenKindleReady = { push(KindleReady) }
                )
            }
            entry<Settings> {
                SettingsScreen(
                    modifier = Modifier.safeDrawingPadding(),
                    onBack = pop,
                    onOpenConnection = { push(Connection) },
                    onOpenBookSources = { push(BookSources) },
                    onOpenKindleReady = { push(KindleReady) }
                )
            }
            entry<KindleReady> {
                KindleReadyScreen(
                    modifier = Modifier.safeDrawingPadding(),
                    onBack = pop,
                    onConnect = {
                        // Reuse Setup if it is already underneath (first run); otherwise open it.
                        if (Setup in backStack) pop() else push(Setup)
                    }
                )
            }
            entry<BookSources> { BookSourcesScreen(Modifier.safeDrawingPadding(), onBack = pop) }
            entry<Screensaver> { ScreensaverScreen(Modifier.safeDrawingPadding(), onBack = pop) }
            entry<Fonts> { FontsScreen(Modifier.safeDrawingPadding(), onBack = pop) }
            entry<Dictionaries> { DictionariesScreen(Modifier.safeDrawingPadding(), onBack = pop) }
            entry<Sync> { SyncScreen(Modifier.safeDrawingPadding(), onBack = pop) }
            entry<Articles> { ArticlesScreen(Modifier.safeDrawingPadding(), onBack = pop) }
            entry<Connection> { ConnectionScreen(Modifier.safeDrawingPadding(), onBack = pop) }
        }
    )
}
