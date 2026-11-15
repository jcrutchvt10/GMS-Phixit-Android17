package ua.polodarb.gmsphixit.utils

import java.util.zip.Deflater
import java.util.zip.Inflater

object ZipUtils {
    fun decompress(compressedData: ByteArray): ByteArray {
        val inflater = Inflater(true).apply {
            setInput(compressedData)
        }

        val buffer = ByteArray(8192)
        val output = java.io.ByteArrayOutputStream()
        try {
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && inflater.finished()) break
                if (count == 0) {
                    if (inflater.needsDictionary()) throw java.util.zip.DataFormatException("Deflate dictionary required")
                    if (inflater.needsInput()) throw java.util.zip.DataFormatException("Truncated deflate stream")
                    throw java.util.zip.DataFormatException("Deflate stream made no progress")
                }
                if (output.size().toLong() + count > 64L * 1024 * 1024) {
                    throw java.util.zip.DataFormatException("Decompressed partition exceeds 64 MiB")
                }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally {
            inflater.end()
        }
    }

    fun compress(data: ByteArray): ByteArray {
        val deflater = Deflater(1, true).apply {
            setInput(data)
            finish()
        }

        val buffer = ByteArray(1024)
        val output = mutableListOf<Byte>()

        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            output.addAll(buffer.take(count))
        }
        deflater.end()

        return output.toByteArray()
    }
}