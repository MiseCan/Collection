package com.collection

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.collection.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NavigationRestoreTest {
    @get:Rule val compose = createComposeRule()

    @Test fun routeIdentityAndArgumentsSurviveRestoreAndDuplicatePushIsIgnored() {
        val restoration = StateRestorationTester(compose)
        lateinit var navigator: CollectionNavigator
        restoration.setContent {
            navigator = rememberCollectionNavigator()
            Text(navigator.currentDestination?.route.orEmpty())
        }
        compose.runOnIdle {
            navigator.navigate(AppRoutes.SETTINGS)
            navigator.navigate(AppRoutes.THEME)
            navigator.navigate("detail/entry%2Fwith%20spaces")
            navigator.navigate("detail/entry%2Fwith%20spaces")
            assertEquals(4, navigator.backStack.size)
        }
        val originalId = navigator.currentBackStackEntry!!.id
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertEquals(4, navigator.backStack.size)
            assertEquals(originalId, navigator.currentBackStackEntry!!.id)
            assertEquals("entry/with spaces", navigator.currentBackStackEntry!!.arguments.getString("entryId"))
            navigator.popBackStack()
            assertEquals(AppRoutes.THEME, navigator.currentDestination!!.route)
            navigator.popToHome()
            assertEquals(AppRoutes.HOME, navigator.currentDestination!!.route)
            assertFalse(navigator.popBackStack())
        }
    }
}
