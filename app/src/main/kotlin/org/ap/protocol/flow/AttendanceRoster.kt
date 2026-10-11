package org.ap.protocol.flow

/**
 * Device key ids of the members of the class whose attendance is running on this phone.
 * The key cache holds every class this teacher has, so without this a student from another of
 * their classes would pass and be told "marked" while the record is dropped.
 * Same process as AuthorityGattService, so a plain volatile field is enough.
 */
object AttendanceRoster {
    @Volatile
    private var keyIds: Set<List<Byte>> = emptySet()

    fun set(ids: Collection<ByteArray>) {
        keyIds = ids.mapTo(HashSet()) { it.toList() }
    }

    fun allows(keyId: ByteArray): Boolean = keyId.toList() in keyIds
}
