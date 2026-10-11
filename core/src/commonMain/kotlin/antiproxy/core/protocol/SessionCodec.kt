package antiproxy.core.protocol

fun encodeSessionBody(session: Session): ByteArray {
    val data = ByteArray(SESSION_BODY_SIZE)
    data[0] = PROTOCOL_VERSION
    var offset = 1
    putBytes(data, offset, session.authorityKeyId)
    offset += KEY_ID_SIZE
    putBytes(data, offset, session.sessionId)
    offset += SESSION_ID_SIZE
    putBytes(data, offset, session.nonce)
    offset += NONCE_SIZE
    putUInt64BE(data, offset, session.issuedAt)
    offset += 8
    putUInt64BE(data, offset, session.expiresAt)
    offset += 8
    putUInt32BE(data, offset, session.policyId)
    return data
}

fun encodeSessionSigned(session: SessionSigned): ByteArray {
    val data = ByteArray(SESSION_SIGNED_SIZE)
    val body = encodeSessionBody(session.session)
    putBytes(data, 0, body)
    putBytes(data, SESSION_BODY_SIZE, session.authoritySignature)
    return data
}

fun decodeSessionSigned(data: ByteArray): SessionSigned {
    require(data.size == SESSION_SIGNED_SIZE) { "SessionSigned must be exactly $SESSION_SIGNED_SIZE bytes" }
    require(data[0] == PROTOCOL_VERSION) { "You and your teacher are on different app versions. Update the app." }
    
    var offset = 1
    val authorityKeyId = getBytes(data, offset, KEY_ID_SIZE)
    offset += KEY_ID_SIZE
    val sessionId = getBytes(data, offset, SESSION_ID_SIZE)
    offset += SESSION_ID_SIZE
    val nonce = getBytes(data, offset, NONCE_SIZE)
    offset += NONCE_SIZE
    val issuedAt = getUInt64BE(data, offset)
    offset += 8
    val expiresAt = getUInt64BE(data, offset)
    offset += 8
    val policyId = getUInt32BE(data, offset)
    offset += 4
    
    val signature = getBytes(data, offset, SIGNATURE_SIZE)
    
    val session = Session(
        authorityKeyId = authorityKeyId,
        sessionId = sessionId,
        nonce = nonce,
        issuedAt = issuedAt,
        expiresAt = expiresAt,
        policyId = policyId
    )
    
    return SessionSigned(session, signature)
}
