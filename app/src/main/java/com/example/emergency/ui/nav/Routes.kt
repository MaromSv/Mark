package com.example.emergency.ui.nav

import android.net.Uri
import com.example.emergency.ui.state.DrawerItemId
import com.example.emergency.ui.state.ToolId

sealed class Route(val path: String) {
    // The path used by NavHost for *route registration* (may include
    // {placeholders}). Use [navigatePath] when calling navController.navigate
    // - it strips the placeholder syntax for routes that take optional args.
    open val navigatePath: String get() = path

    data object Home : Route("home")
    data object DataPacks : Route("data_packs")
    data object PersonalInfo : Route("personal_info")
    data object Conversations : Route("conversations")
    data object ChatThread : Route("chat_thread")
    data object Map : Route("map?lat={lat}&lon={lon}&name={name}&category={category}") {
        override val navigatePath: String = "map"
        fun withDestination(lat: Double, lon: Double, name: String, category: String): String =
            "map?lat=$lat&lon=$lon&name=${Uri.encode(name)}&category=${Uri.encode(category)}"
    }
    data object FirstAid : Route("first_aid")
    data object AbcCheck : Route("abc_check")
    data object GetOut : Route("get_out")
    data object Settings : Route("settings")
    data object CprWalkthrough : Route("cpr_walkthrough")
    // Map-region picker + storage manager (plan section 8 step 5). Distinct from
    // DataPacks, which is the medical/first-aid content store.
    data object Regions : Route("regions")
    // Active turn-by-turn navigation (plan section 8 step 7.5). Reads the route
    // payload from PendingNavigation since polyline + steps don't fit a
    // navigation argument string.
    data object Navigation : Route("navigation")
}

fun DrawerItemId.toRoute(): Route = when (this) {
    DrawerItemId.CONVERSATIONS -> Route.Conversations
    DrawerItemId.MAP -> Route.Map
    DrawerItemId.FIRST_AID -> Route.FirstAid
    DrawerItemId.DATA_PACKS -> Route.DataPacks
    DrawerItemId.SETTINGS -> Route.Settings
    DrawerItemId.PERSONAL_INFO -> Route.PersonalInfo
}

fun ToolId.toRoute(): Route = when (this) {
    ToolId.MAP -> Route.Map
    // The "CPR" tile on the home grid skips the First Aid listing screen
    // and jumps straight into the CPR walkthrough - that's the actual tool.
    ToolId.FIRST_AID -> Route.CprWalkthrough
    ToolId.ABC_CHECK -> Route.AbcCheck
    ToolId.GET_OUT -> Route.GetOut
}
