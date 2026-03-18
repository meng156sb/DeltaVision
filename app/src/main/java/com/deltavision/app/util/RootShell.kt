package com.deltavision.app.util

import java.io.ByteArrayOutputStream

object RootShell {
    fun canUseRoot(): Boolean = try {
        val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        process.exitValue() == 0 && output.contains("uid=0")
    } catch (_: Throwable) {
        false
    }

    fun execForBytes(command: String): ByteArray? = try {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = ByteArrayOutputStream()
        process.inputStream.use { it.copyTo(output) }
        process.waitFor()
        if (process.exitValue() == 0) output.toByteArray() else null
    } catch (_: Throwable) {
        null
    }
}
