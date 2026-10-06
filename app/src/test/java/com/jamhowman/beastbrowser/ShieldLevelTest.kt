package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.browser.ShieldLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/** Roadmap 10: page shield level thresholds (0 = none, 1-9 = some, 10+ = many). */
class ShieldLevelTest {
    @Test fun nothingBlockedIsNone() {
        assertEquals(ShieldLevel.NONE, ShieldLevel.of(0))
        assertEquals(ShieldLevel.NONE, ShieldLevel.of(-3)) // defensive: never negative in practice
    }

    @Test fun oneToNineIsSome() {
        for (n in 1..9) assertEquals("$n", ShieldLevel.SOME, ShieldLevel.of(n))
    }

    @Test fun tenAndUpIsMany() {
        assertEquals(10, ShieldLevel.MANY_FROM)
        for (n in listOf(10, 11, 99, 100, 10_000, Int.MAX_VALUE)) assertEquals("$n", ShieldLevel.MANY, ShieldLevel.of(n))
    }

    @Test fun boundaryFollowsTheConstant() {
        assertEquals(ShieldLevel.SOME, ShieldLevel.of(ShieldLevel.MANY_FROM - 1))
        assertEquals(ShieldLevel.MANY, ShieldLevel.of(ShieldLevel.MANY_FROM))
    }
}
