package com.deltavision.app.util

import java.io.ByteArrayOutputStream

object RootShell {
    fun canUseRoot(): Boolean = execForText("id")?.contains("uid=0") == true

    fun execForText(command: String): String? = try {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        if (process.exitValue() == 0) output else null
    } catch (_: Throwable) {
        null
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
