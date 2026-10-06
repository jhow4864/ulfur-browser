package com.jamhowman.beastbrowser

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs the extension's JavaScript unit tests (app/src/test/js, `node --test`). Skipped when node isn't installed. */
class ExtensionJsTest {
    private fun node(): String? = listOf("node", "/usr/bin/node", "/usr/local/bin/node").firstOrNull { cmd ->
        runCatching { ProcessBuilder(cmd, "--version").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)
    }

    @Test fun snifferAndBackgroundJsTests() {
        val node = node()
        assumeTrue("node not installed", node != null)
        val dir = listOf("src/test/js", "app/src/test/js").map(::File).first { it.isDirectory }
        val files = dir.listFiles { f -> f.name.endsWith(".test.js") }!!.sortedBy { it.name }.map { it.absolutePath }
        val p = ProcessBuilder(listOf(node!!, "--test") + files).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(120, TimeUnit.SECONDS)
        assertEquals("node --test failed:\n$out", 0, p.exitValue())
    }
}
