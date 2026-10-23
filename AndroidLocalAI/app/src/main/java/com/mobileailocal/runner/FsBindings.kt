package com.mobileailocal.runner

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.mobileailocal.data.JsWorkspace
import com.mobileailocal.domain.JsFsException

/**
 * JS に見せる `fs` オブジェクト。検証・上限はすべて JsWorkspace に任せる薄い層。
 * 失敗は JsFsException("<code>: <説明>") で、JS では Error として try/catch できる(メッセージは同じ)。
 */
fun QuickJs.installFs(ws: JsWorkspace) {
    define("fs") {
        function("writeFile") { a ->
            val text = a.getOrNull(1) as? String ?: throw JsFsException("EINVAL", "text must be a string")
            ws.write(a.getOrNull(0) as? String ?: throw JsFsException("EINVAL", "path must be a string"), text)
            null
        }
        function("readFile") { a ->
            ws.read(a.getOrNull(0) as? String ?: throw JsFsException("EINVAL", "path must be a string"))
        }
        function("list") { a ->
            // 引数なし・undefined は "."。それ以外の非文字列は JsPathPolicy が EINVAL にする
            val p = if (a.isEmpty() || a[0] == null) "." else a[0] as? String ?: throw JsFsException("EINVAL", "path must be a string")
            ws.list(p)
        }
        function("exists") { a ->
            ws.exists(a.getOrNull(0) as? String ?: throw JsFsException("EINVAL", "path must be a string"))
        }
        function("remove") { a ->
            ws.remove(a.getOrNull(0) as? String ?: throw JsFsException("EINVAL", "path must be a string"))
        }
    }
}
