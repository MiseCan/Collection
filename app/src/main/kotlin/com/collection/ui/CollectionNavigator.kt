package com.collection.ui

import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import org.json.JSONObject
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import java.util.UUID

data class RouteDestination(val route: String)

data class CollectionRoute(val path: String, val id: String = UUID.randomUUID().toString()) : NavKey {
    val destination: RouteDestination get() = RouteDestination(when {
        path.startsWith("detail/") -> AppRoutes.DETAIL
        path.startsWith("editor") -> AppRoutes.EDITOR
        path.startsWith("original/") -> AppRoutes.ORIGINAL
        path.startsWith("text/") -> AppRoutes.TEXT
        else -> path
    })
    val arguments: Bundle get() = Bundle().apply {
        when (destination.route) {
            AppRoutes.DETAIL, AppRoutes.TEXT -> putString("entryId", Uri.decode(path.substringAfter('/')))
            AppRoutes.EDITOR -> putString("entryId", Uri.parse(path).getQueryParameter("entryId"))
            AppRoutes.ORIGINAL -> putString("attachmentId", Uri.decode(path.substringAfter('/')))
        }
    }
}

/** Route data and persistence only. Miuix owns rendering, gesture arbitration and animation. */
class CollectionNavigator(val backStack: NavBackStack = navBackStackOf(CollectionRoute(AppRoutes.HOME))) {
    val currentBackStackEntry: CollectionRoute? get() = backStack.lastOrNull() as? CollectionRoute
    val currentDestination get() = currentBackStackEntry?.destination
    fun navigate(path: String) {
        if (currentBackStackEntry?.path != path) backStack.add(CollectionRoute(path))
    }
    fun popBackStack(): Boolean = if (backStack.size > 1) { backStack.removeAt(backStack.lastIndex); true } else false
    fun popToHome() { while (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
}

@Composable
fun rememberCollectionNavigator(): CollectionNavigator = rememberSaveable(saver = Saver(
    save = { nav: CollectionNavigator -> ArrayList(nav.backStack.map { key ->
        key as CollectionRoute
        JSONObject().put("path", key.path).put("id", key.id).toString()
    }) },
    restore = { saved: ArrayList<String> -> CollectionNavigator(navBackStackOf(*saved.map {
        val json = JSONObject(it); CollectionRoute(json.getString("path"), json.getString("id"))
    }.toTypedArray())) },
)) { CollectionNavigator() }

@Composable
fun CollectionNavigator.currentBackStackEntryAsState(): State<CollectionRoute?> =
    remember(this) { derivedStateOf { currentBackStackEntry } }
