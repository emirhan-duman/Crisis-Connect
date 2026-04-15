package com.auralis.crisisconnect.navigation

object ChatSharedElements {
    private const val AVATAR_KEY_PREFIX = "chat_avatar:"
    private const val TITLE_KEY_PREFIX = "chat_title:"

    fun avatar(sessionCode: String): String = AVATAR_KEY_PREFIX + sessionCode.trim()

    fun title(sessionCode: String): String = TITLE_KEY_PREFIX + sessionCode.trim()
}
