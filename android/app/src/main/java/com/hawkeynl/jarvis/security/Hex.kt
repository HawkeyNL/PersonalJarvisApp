package com.hawkeynl.jarvis.security

object Hex {
    fun encode(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte -> append("%02x".format(byte.toInt() and 0xff)) }
    }

    fun decode(value: String): ByteArray {
        require(value.length % 2 == 0) { "Hex value must have an even length" }
        // Character.digit also accepts non-ASCII digits such as fullwidth ones.
        require(value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Invalid hex value" }
        return ByteArray(value.length / 2) { index ->
            val high = Character.digit(value[index * 2], 16)
            val low = Character.digit(value[index * 2 + 1], 16)
            ((high shl 4) or low).toByte()
        }
    }
}
