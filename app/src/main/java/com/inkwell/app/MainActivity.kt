package com.inkwell.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember

sealed interface Screen {
    data object Library : Screen
    data object Options : Screen
    data class Sheets(val filter: Filter) : Screen
    data class Editor(val id: String) : Screen
    data class Preview(val ids: List<String>, val title: String) : Screen
}

class MainActivity : ComponentActivity() {
    private lateinit var store: Store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = Store(this)
        val settings = Settings(this)
        setContent { AppTheme(settings) { App(store, settings) } }
    }

    override fun onStop() {
        super.onStop()
        if (::store.isInitialized) store.save()
    }
}

@Composable
fun App(store: Store, settings: Settings) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Library) }
    val go: (Screen) -> Unit = { stack.add(it) }
    val back: () -> Unit = {
        store.save()
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
    UpdateCheck(store)
    BackHandler(stack.size > 1, back)
    AnimatedContent(
        targetState = stack.last(),
        transitionSpec = { (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 12 }) togetherWith fadeOut(tween(120)) },
        label = "nav"
    ) { s ->
        when (s) {
            Screen.Library -> LibraryScreen(store, go)
            Screen.Options -> SettingsScreen(settings, back)
            is Screen.Sheets -> SheetsScreen(store, settings, s.filter, back, go)
            is Screen.Editor -> EditorScreen(store, settings, s.id, back, go)
            is Screen.Preview -> PreviewScreen(store, settings, s.ids, s.title, back)
        }
    }
}
