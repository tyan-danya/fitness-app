package com.dtyan.fitdiary.export

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun InputStream.readBytesBounded(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= limit) { "Файл превышает допустимый размер" }
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}
