package io.github.traushb.actuali.services

import io.github.traushb.actuali.models.StartTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeScreenShortcutTest {
    @Test
    fun addTransactionShortcutOpensTheAddTab() = assertEquals(StartTab.AddTransaction.tabTag, HomeScreenShortcut.tab(HomeScreenShortcut.ADD_TRANSACTION))

    @Test
    fun otherLaunchesLeaveTheTabAlone() {
        assertNull(HomeScreenShortcut.tab(null))
        assertNull(HomeScreenShortcut.tab("android.intent.action.MAIN"))
    }
}
