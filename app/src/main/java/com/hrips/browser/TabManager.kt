package com.hrips.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue

/**
 * UI-observable tab collection and selection state.
 *
 * Browser owns browser behaviour (downloads, permissions, privacy, extensions), while this class
 * only owns tab membership/order/selection. This keeps tab mutations consistent and makes the
 * state boundary close to Mozilla's tab store / tab manager model without introducing a full
 * reducer framework.
 */
class TabManager {
    val tabs = mutableStateListOf<Tab>()
    val groups = mutableStateListOf<TabGroup>()
    val closedTabs = mutableStateListOf<ClosedTab>()

    var currentIndex by mutableIntStateOf(0)
        private set

    val current: Tab?
        get() = tabs.getOrNull(currentIndex)

    fun select(index: Int) {
        if (tabs.isEmpty()) {
            currentIndex = 0
            return
        }
        currentIndex = index.coerceIn(0, tabs.lastIndex)
    }

    fun add(tab: Tab, select: Boolean = true) {
        tabs.add(tab)
        if (select) currentIndex = tabs.lastIndex
    }

    fun removeAt(index: Int): Tab? {
        if (index !in tabs.indices) return null
        val removed = tabs.removeAt(index)
        when {
            tabs.isEmpty() -> currentIndex = 0
            index < currentIndex -> currentIndex--
            index == currentIndex -> currentIndex = currentIndex.coerceAtMost(tabs.lastIndex)
            else -> currentIndex = currentIndex.coerceIn(0, tabs.lastIndex)
        }
        return removed
    }

    fun remove(tab: Tab): Int = tabs.indexOf(tab).also { if (it >= 0) removeAt(it) }

    fun removeAll(predicate: (Tab) -> Boolean): List<Tab> {
        val doomed = tabs.filter(predicate)
        if (doomed.isEmpty()) return emptyList()
        val selected = current
        tabs.removeAll(doomed)
        currentIndex = when {
            tabs.isEmpty() -> 0
            selected != null && selected in tabs -> tabs.indexOf(selected)
            else -> currentIndex.coerceIn(0, tabs.lastIndex)
        }
        return doomed
    }

    fun move(from: Int, to: Int) {
        if (from == to || from !in tabs.indices || to !in tabs.indices) return
        val selected = current
        tabs.add(to, tabs.removeAt(from))
        currentIndex = selected?.let { tabs.indexOf(it) }?.coerceAtLeast(0) ?: 0
    }

    fun normalizeSelection() {
        currentIndex = if (tabs.isEmpty()) 0 else currentIndex.coerceIn(0, tabs.lastIndex)
    }
}
