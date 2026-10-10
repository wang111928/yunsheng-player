package com.litemusic.app.util

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Keep the last complete snapshot when writing or replacing a newer snapshot fails. */
internal fun writeAtomicText(file: File, text: String) {
    check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true) { "无法创建保存目录" }
    val temporary = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
    try {
        temporary.outputStream().use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally { temporary.delete() }
}
