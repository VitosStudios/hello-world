package com.vitosstudios.suite.pptx

import java.util.Base64

object TestImages {
    /** A valid 1x1 red PNG. */
    val PNG_1X1: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )

    /** A minimal GIF header with the given size (enough for header parsing). */
    fun gif(w: Int, h: Int): ByteArray = byteArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, (w and 0xFF).toByte(), (w shr 8).toByte(), (h and 0xFF).toByte(), (h shr 8).toByte(),
        0, 0, 0,
    )
}
