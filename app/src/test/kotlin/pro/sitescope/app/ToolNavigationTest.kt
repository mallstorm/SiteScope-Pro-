package pro.sitescope.app

import org.junit.Assert.*
import org.junit.Test
import pro.sitescope.app.ui.AppState
import pro.sitescope.app.ui.Tab
import pro.sitescope.app.ui.ToolCategory
import pro.sitescope.app.ui.ToolRegistry
import pro.sitescope.app.ui.whenText
import java.time.Instant
import java.time.ZoneId

class ToolNavigationTest {
    @Test fun backRestoresParentRouteAndItsArguments() {
        val state = AppState()
        state.selectTab(Tab.TOOLS)
        state.openRoute("network", 4, "saved target")
        val parent = state.routeNonce
        state.openRoute("settings")
        assertNotEquals(parent, state.routeNonce)
        state.closeRoute()
        assertEquals("network", state.route)
        assertEquals(4, state.routeTab)
        assertEquals("saved target", state.routeTargets)
        assertEquals(parent, state.routeNonce)
        state.closeRoute()
        assertNull(state.route)
        assertEquals(Tab.TOOLS, state.tab)
    }

    @Test fun repeatRouteAndRootTabDiscardObsoleteRouteEntries() {
        val state = AppState()
        state.openRoute("tv")
        state.openRoute("tv", 1)
        assertEquals(1, state.routeKeys.size)
        state.openRoute("satellite")
        state.openRoute("data", 2)
        assertTrue(state.routeKeys.isEmpty())
        assertNull(state.route)
        assertEquals(Tab.DATA, state.tab)
        assertEquals(2, state.dataTab)
    }

    @Test fun searchesTitleGroupAndCategoryAndPreparesDirectEntries() {
        assertTrue(ToolRegistry.find("скорость сети", ToolCategory.NET).flatMap { it.tools }.any { it.id == "speed" })
        assertTrue(ToolRegistry.find("поиск оснастки", ToolCategory.TV).isEmpty())
        assertTrue(ToolRegistry.find("vpn", ToolCategory.TV).isEmpty())
        val state = AppState()
        val tools = ToolRegistry.groups.flatMap { it.tools }.associateBy { it.id }
        tools.getValue("crawl").prepare?.invoke(state)
        assertEquals(true, state.siteCrawlRequest)
        tools.getValue("audit").prepare?.invoke(state)
        assertEquals(false, state.siteCrawlRequest)
        tools.getValue("sources").prepare?.invoke(state)
        tools.getValue("streamcheck").prepare?.invoke(state)
        assertTrue(state.tvSourcesRequest)
        assertTrue(state.tvCheckRequest)
    }

    @Test fun yesterdayIncludesLastDayOfPreviousYear() {
        val zone = ZoneId.of("UTC")
        fun epoch(iso: String) = Instant.parse(iso).toEpochMilli()
        assertEquals("Вчера, 20:15", whenText(epoch("2026-12-31T20:15:00Z"), epoch("2027-01-01T01:00:00Z"), zone))
        assertTrue(whenText(epoch("2025-01-01T20:15:00Z"), epoch("2027-01-01T01:00:00Z"), zone).contains("2025"))
    }
}
