package com.mobileailocal.spike

/** メイン <-> :runner の Messenger プロトコル */
object Proto {
    const val RUN = 1       // メイン -> runner: 実行要求
    const val RESULT = 2    // runner -> メイン: 実行結果
    const val PING = 3      // メイン -> runner: pid 問い合わせ
    const val PONG = 4      // runner -> メイン

    const val K_ID = "id"
    const val K_LANG = "lang"           // "py" | "js"
    const val K_CODE = "code"
    const val K_TIMEOUT = "timeoutMs"
    const val K_WORKDIR = "workDir"
    const val K_WATCHDOG = "watchdog"   // runner 側の自殺監視を使うか(false なら呼び出し側 kill の検証用)
    const val K_STATUS = "status"       // ok | error | timeout
    const val K_STDOUT = "stdout"
    const val K_STDERR = "stderr"
    const val K_ELAPSED = "elapsedMs"
    const val K_PID = "pid"
    const val K_PSS = "pssKb"
    const val K_NOTE = "note"           // 初回起動時間などの補足

    const val MAX_OUT = 64 * 1024
}
