package com.hrips.browser

/**
 * Что удалять при выходе через «Выйти» в меню (как «Удалять данные при выходе» в Firefox и IronFox).
 * Биты хранятся в настройках, поэтому их значения менять нельзя.
 */
object ExitClear {
    const val TABS = 1
    const val HISTORY = 2
    const val COOKIES = 4
    const val CACHE = 8
    const val ALL = TABS or HISTORY or COOKIES or CACHE

    /** По умолчанию при выходе ничего не удаляется: пользователь сам отмечает, что стирать. */
    const val DEFAULT = 0

    fun sanitize(mask: Int): Int = mask and ALL

    fun has(mask: Int, bit: Int): Boolean = mask and bit != 0

    fun describe(mask: Int): String {
        val m = sanitize(mask)
        if (m == 0) return "ничего"
        return buildList {
            if (has(m, TABS)) add("вкладки")
            if (has(m, HISTORY)) add("история")
            if (has(m, COOKIES)) add("cookies и данные сайтов")
            if (has(m, CACHE)) add("кэш")
        }.joinToString(", ")
    }
}
