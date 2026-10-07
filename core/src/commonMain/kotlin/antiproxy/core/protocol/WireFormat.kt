package antiproxy.core.protocol

fun checkBounds(array: ByteArray, offset: Int, length: Int) {
    require(offset >= 0 && length >= 0 && offset + length <= array.size) {
        "Out of bounds: array.size=${array.size}, offset=$offset, length=$length"
    }
}

fun putUInt64BE(dst: ByteArray, offset: Int, value: Long) {
    checkBounds(dst, offset, 8)
    dst[offset] = (value ushr 56).toByte()
    dst[offset + 1] = (value ushr 48).toByte()
    dst[offset + 2] = (value ushr 40).toByte()
    dst[offset + 3] = (value ushr 32).toByte()
    dst[offset + 4] = (value ushr 24).toByte()
    dst[offset + 5] = (value ushr 16).toByte()
    dst[offset + 6] = (value ushr 8).toByte()
    dst[offset + 7] = value.toByte()
}

fun getUInt64BE(src: ByteArray, offset: Int): Long {
    checkBounds(src, offset, 8)
    return ((src[offset].toLong() and 0xFF) shl 56) or
        ((src[offset + 1].toLong() and 0xFF) shl 48) or
        ((src[offset + 2].toLong() and 0xFF) shl 40) or
        ((src[offset + 3].toLong() and 0xFF) shl 32) or
        ((src[offset + 4].toLong() and 0xFF) shl 24) or
        ((src[offset + 5].toLong() and 0xFF) shl 16) or
        ((src[offset + 6].toLong() and 0xFF) shl 8) or
        (src[offset + 7].toLong() and 0xFF)
}

fun putUInt32BE(dst: ByteArray, offset: Int, value: Int) {
    checkBounds(dst, offset, 4)
    dst[offset] = (value ushr 24).toByte()
    dst[offset + 1] = (value ushr 16).toByte()
    dst[offset + 2] = (value ushr 8).toByte()
    dst[offset + 3] = value.toByte()
}

fun getUInt32BE(src: ByteArray, offset: Int): Int {
    checkBounds(src, offset, 4)
    return ((src[offset].toInt() and 0xFF) shl 24) or
        ((src[offset + 1].toInt() and 0xFF) shl 16) or
        ((src[offset + 2].toInt() and 0xFF) shl 8) or
        (src[offset + 3].toInt() and 0xFF)
}

fun putUInt16BE(dst: ByteArray, offset: Int, value: UShort) {
    checkBounds(dst, offset, 2)
    val v = value.toInt()
    dst[offset] = (v ushr 8).toByte()
    dst[offset + 1] = v.toByte()
}

fun getUInt16BE(src: ByteArray, offset: Int): UShort {
    checkBounds(src, offset, 2)
    return (((src[offset].toInt() and 0xFF) shl 8) or
        (src[offset + 1].toInt() and 0xFF)).toUShort()
}

fun putBytes(dst: ByteArray, offset: Int, src: ByteArray) {
    checkBounds(dst, offset, src.size)
    src.copyInto(dst, offset)
}

fun getBytes(src: ByteArray, offset: Int, length: Int): ByteArray {
    checkBounds(src, offset, length)
    return src.copyOfRange(offset, offset + length)
}
