package antiproxy.core.crypto

import antiproxy.core.protocol.SIGNATURE_SIZE

// P-256 curve order n = FFFFFFFFFFFF FFFF FFFFFFFF 00000000 FFFFFFFF FFFFFFFF BCE6FAAD A7179E84 F3B9CAC2 FC632551
private val N = byteArrayOf(
    0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
    0x00, 0x00, 0x00, 0x00,
    0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
    0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
    0xBC.toByte(), 0xE6.toByte(), 0xFA.toByte(), 0xAD.toByte(),
    0xA7.toByte(), 0x17.toByte(), 0x9E.toByte(), 0x84.toByte(),
    0xF3.toByte(), 0xB9.toByte(), 0xCA.toByte(), 0xC2.toByte(),
    0xFC.toByte(), 0x63.toByte(), 0x25.toByte(), 0x51.toByte()
)

// n/2
private val HALF_N = byteArrayOf(
    0x7F.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
    0x80.toByte(), 0x00, 0x00, 0x00,
    0x7F.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
    0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
    0xDE.toByte(), 0x73.toByte(), 0x7D.toByte(), 0x56.toByte(),
    0xD3.toByte(), 0x8B.toByte(), 0xCF.toByte(), 0x42.toByte(),
    0x79.toByte(), 0xDC.toByte(), 0xE5.toByte(), 0x61.toByte(),
    0x7E.toByte(), 0x31.toByte(), 0x92.toByte(), 0xA8.toByte()
)

private val ZERO = ByteArray(32)

private fun compareBigEndian(a: ByteArray, b: ByteArray): Int {
    require(a.size == 32 && b.size == 32)
    for (i in 0 until 32) {
        val va = a[i].toInt() and 0xFF
        val vb = b[i].toInt() and 0xFF
        if (va < vb) return -1
        if (va > vb) return 1
    }
    return 0
}

private fun subtractBigEndian(a: ByteArray, b: ByteArray): ByteArray {
    require(a.size == 32 && b.size == 32)
    val result = ByteArray(32)
    var borrow = 0
    for (i in 31 downTo 0) {
        val va = a[i].toInt() and 0xFF
        val vb = b[i].toInt() and 0xFF
        var diff = va - vb - borrow
        if (diff < 0) {
            diff += 256
            borrow = 1
        } else {
            borrow = 0
        }
        result[i] = diff.toByte()
    }
    return result
}

fun isValidScalar(v: ByteArray): Boolean {
    if (v.size != 32) return false
    if (compareBigEndian(v, ZERO) <= 0) return false
    if (compareBigEndian(v, N) >= 0) return false
    return true
}

fun isLowS(s: ByteArray): Boolean {
    require(s.size == 32)
    return compareBigEndian(s, HALF_N) <= 0 && compareBigEndian(s, ZERO) > 0
}

fun enforceLowS(r: ByteArray, s: ByteArray): Pair<ByteArray, ByteArray> {
    require(r.size == 32 && s.size == 32)
    if (compareBigEndian(s, HALF_N) > 0) {
        return r to subtractBigEndian(N, s)
    }
    return r to s
}

private fun parseInteger(der: ByteArray, offset: Int): Pair<ByteArray, Int> {
    require(offset + 1 < der.size) { "Malformed DER: missing INTEGER" }
    require(der[offset] == 0x02.toByte()) { "Malformed DER: expected INTEGER tag" }
    val length = der[offset + 1].toInt() and 0xFF
    require(offset + 2 + length <= der.size) { "Malformed DER: INTEGER length out of bounds" }
    
    val valueBytes = der.copyOfRange(offset + 2, offset + 2 + length)
    val parsed = ByteArray(32)
    if (valueBytes.size > 32) {
        require(valueBytes.size == 33 && valueBytes[0] == 0x00.toByte()) { "Malformed DER: INTEGER too long" }
        valueBytes.copyInto(parsed, 0, 1, 33)
    } else {
        valueBytes.copyInto(parsed, 32 - valueBytes.size, 0, valueBytes.size)
    }
    return parsed to (offset + 2 + length)
}

fun derToRaw(der: ByteArray): RawSignature {
    require(der.size >= 2) { "Malformed DER: too short" }
    require(der[0] == 0x30.toByte()) { "Malformed DER: expected SEQUENCE tag" }
    val seqLen = der[1].toInt() and 0xFF
    require(seqLen == der.size - 2) { "Malformed DER: SEQUENCE length mismatch" }
    
    val (r, nextOffset) = parseInteger(der, 2)
    val (s, finalOffset) = parseInteger(der, nextOffset)
    require(finalOffset == der.size) { "Malformed DER: trailing bytes" }
    
    require(isValidScalar(r)) { "Invalid r scalar" }
    require(isValidScalar(s)) { "Invalid s scalar" }
    
    val (rNorm, sNorm) = enforceLowS(r, s)
    val raw = ByteArray(SIGNATURE_SIZE)
    rNorm.copyInto(raw, 0)
    sNorm.copyInto(raw, 32)
    return RawSignature(raw)
}

private fun encodeInteger(value: ByteArray): ByteArray {
    var start = 0
    while (start < value.size - 1 && value[start] == 0.toByte()) {
        start++
    }
    val leadingByte = value[start].toInt() and 0xFF
    val needsZero = leadingByte >= 0x80
    val outLen = value.size - start + if (needsZero) 1 else 0
    val result = ByteArray(2 + outLen)
    result[0] = 0x02
    result[1] = outLen.toByte()
    if (needsZero) {
        result[2] = 0x00
        value.copyInto(result, 3, start, value.size)
    } else {
        value.copyInto(result, 2, start, value.size)
    }
    return result
}

fun rawToDer(raw: RawSignature): ByteArray {
    val rDer = encodeInteger(raw.r())
    val sDer = encodeInteger(raw.s())
    val seqLen = rDer.size + sDer.size
    val result = ByteArray(2 + seqLen)
    result[0] = 0x30
    result[1] = seqLen.toByte()
    rDer.copyInto(result, 2)
    sDer.copyInto(result, 2 + rDer.size)
    return result
}
