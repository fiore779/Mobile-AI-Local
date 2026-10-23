package com.mobileailocal.domain

object Prompts {
    /** 会話のシステムプロンプトが空のときに使う既定文(設計書 17.2)。 */
    const val DEFAULT_SYSTEM =
        "You are a helpful assistant running entirely on the user's device. " +
            "Always reply in the language the user writes in. " +
            "When the user writes Chinese, reply in the same script (Simplified or Traditional) unless asked otherwise."
}
