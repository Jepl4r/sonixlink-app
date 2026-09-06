package com.mattiadoronzo.sonixlink

/**
 * Which letter a row belongs under, for the index strip.
 *
 * Not guessed from the title: read from the same `sortkey` the list is ordered
 * by, with the player's own two rules (`library_index_letter_of_key` in
 * src/system/library.c). Guessing in Kotlin meant the strip could say "W" for a
 * row the ordering had put under I, and then the strip lands in the wrong place.
 *
 * The key's shape: the first digit is the writing group, the byte after it is
 * the first character folded to lower case.
 */
object Letters {

    /** SORT_GROUP_LATIN from the player's enum. */
    private const val LATIN_GROUP = 3

    /** LIBRARY_INDEX_PAST_Z: everything the ordering puts after Z. */
    const val PAST_Z = '~'

    fun ofSortKey(key: String): Char {
        if (key.isEmpty()) return '#'
        val group = key[0] - '0'
        if (group > LATIN_GROUP) return PAST_Z
        if (group != LATIN_GROUP) return '#'
        if (key.length < 2) return '#'
        val folded = key[1]
        if (folded in 'a'..'z') return folded.uppercaseChar()
        // A Latin letter with no simple form to fold to sorts after every a-z,
        // which is the answer the player gives too.
        return PAST_Z
    }
}
