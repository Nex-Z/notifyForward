package com.notifyforward.app.model

/** 一条被记录的通知。 */
data class NotifRecord(
    val id: Long = 0L,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val bigText: String = "",
    val subText: String = "",
    val channelId: String = "",
    val category: String = "",
    val groupKey: String = "",
    val postTime: Long = System.currentTimeMillis(),
    val ongoing: Boolean = false,
    val clearable: Boolean = true,
    val conversation: Boolean = false,
    val media: Boolean = false,
    /** 发通知的 uid，分身/多用户场景下与主应用不同。 */
    val uid: Int = -1,
    /** Android 用户 id，分身空间 / 工作资料通常不是 0。 */
    val userId: Int = 0,
    /** 同包名但 uid 与主应用不一致，判定为分身。 */
    val clone: Boolean = false,
    /** 处理结果，见 [Action] */
    val action: Int = Action.NONE,
    val ruleName: String = "",
    val aiScore: Float = 0f,
    /** 命中来源，见 [Source] */
    val source: Int = Source.NONE,
    val feedback: Int = Feedback.NONE,
    val hidden: Boolean = false
) {
    object Action {
        const val NONE = 0
        const val DISMISS = 1
        const val MUTE = 2
        const val WEBHOOK = 4
        const val READ = 8
        const val BARK = 16
    }

    object Source {
        const val NONE = 0
        const val RULE = 1
        const val AI = 2
    }

    object Feedback {
        const val NONE = 0
        const val NORMAL = 1
        const val SPAM = 2
    }

    val isFiltered: Boolean get() = (action and (Action.DISMISS or Action.MUTE)) != 0
    val displayText: String get() = bigText.ifBlank { text }
    /** 展示用应用名，分身会带后缀，避免和主应用混淆。 */
    val displayAppName: String get() = if (clone) "$appName（分身）" else appName
    val searchable: String get() = "$appName $title $text $bigText".lowercase()
}
