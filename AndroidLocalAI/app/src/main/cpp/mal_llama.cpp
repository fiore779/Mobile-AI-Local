// llama.cpp を Kotlin から使うための薄い JNI 層。
// 状態(モデル・コンテキスト)は1組だけ持つ。呼び出しは Kotlin 側で直列化する前提。
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <deque>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "mal_llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

llama_model *g_model = nullptr;
llama_context *g_ctx = nullptr;
std::atomic<bool> g_stop{false};
std::vector<llama_token> g_cache;  // KV キャッシュに入っているトークン列(次回の再利用判定用)

std::string jstr(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

// バイト配列(Kotlin 側で UTF-8 にしたもの)を std::string にする。
// GetStringUTFChars は絵文字を CESU-8 にして壊れるので、本文はバイト列で受ける。
std::string jbytes(JNIEnv *env, jbyteArray a) {
    jsize n = env->GetArrayLength(a);
    std::string r(n, '\0');
    if (n > 0) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(&r[0]));
    return r;
}

// 先頭から数えて、完全な UTF-8 になっている最長のバイト数を返す(末尾の途中切れ文字を除く)。
size_t valid_utf8_prefix(const std::string &s) {
    size_t i = 0, n = s.size();
    while (i < n) {
        unsigned char c = s[i];
        size_t len = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 0;
        if (len == 0) { i++; continue; }              // 不正なバイトは読み飛ばす(下で除去される)
        if (i + len > n) break;                        // 末尾が途中切れ
        i += len;
    }
    return i;
}

// 不正なバイトを取り除いて、必ず正しい UTF-8 にする(NewStringUTF の異常終了を避ける)。
std::string sanitize_utf8(const std::string &s) {
    std::string out;
    size_t i = 0, n = s.size();
    while (i < n) {
        unsigned char c = s[i];
        size_t len = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 0;
        bool ok = len != 0 && i + len <= n;
        for (size_t k = 1; ok && k < len; k++) ok = (static_cast<unsigned char>(s[i + k]) & 0xC0) == 0x80;
        if (ok) { out.append(s, i, len); i += len; } else { i++; }
    }
    return out;
}

jstring make_jstring(JNIEnv *env, const std::string &s) {
    return env->NewStringUTF(sanitize_utf8(s).c_str());
}

// llama.cpp が出した ERROR 行(失敗理由として Kotlin へ返す)。直近 8 行・1 行 300 バイトまで。
// logcat には出さず、メモリ上にだけ持つ。モデルのパスは Kotlin 側で伏せる。
std::mutex g_err_mu;
std::deque<std::string> g_errs;

void capture_log(ggml_log_level level, const char *text, void *) {
    if (level != GGML_LOG_LEVEL_ERROR || !text) return;
    std::string s(text);
    while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back();
    if (s.empty()) return;
    if (s.size() > 300) s.resize(300);
    std::lock_guard<std::mutex> lk(g_err_mu);
    g_errs.push_back(s);
    if (g_errs.size() > 8) g_errs.pop_front();
}

void clear_errs() {
    std::lock_guard<std::mutex> lk(g_err_mu);
    g_errs.clear();
}

// "CODE" または "CODE:<行>(改行区切り)" を作る(extra があれば先頭の行にする)。
std::string with_detail(const std::string &code, const std::string &extra = "") {
    std::lock_guard<std::mutex> lk(g_err_mu);
    std::string d = extra;
    for (const auto &l : g_errs) { if (!d.empty()) d += "\n"; d += l; }
    g_errs.clear();
    return d.empty() ? code : code + ":" + d;
}

void free_all() {
    g_cache.clear();
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

}  // namespace

extern "C" {

// 戻り値: 成功なら空文字、失敗ならエラーメッセージ。
JNIEXPORT jstring JNICALL
Java_com_mobileailocal_domain_LlamaCppEngine_nativeLoad(JNIEnv *env, jobject, jstring path, jint nCtx, jint nThreads) {
    free_all();
    llama_backend_init();
    clear_errs();
    llama_log_set(capture_log, nullptr);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(jstr(env, path).c_str(), mp);
    if (!g_model) return make_jstring(env, with_detail("E_LOAD_FILE"));

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(nCtx);
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) { std::string e = with_detail("E_CONTEXT"); free_all(); return make_jstring(env, e); }
    LOGI("loaded: n_ctx=%u threads=%d", llama_n_ctx(g_ctx), nThreads);
    return make_jstring(env, "");
}

JNIEXPORT void JNICALL
Java_com_mobileailocal_domain_LlamaCppEngine_nativeUnload(JNIEnv *, jobject) { free_all(); }

JNIEXPORT void JNICALL
Java_com_mobileailocal_domain_LlamaCppEngine_nativeStop(JNIEnv *, jobject) { g_stop = true; }

// 会話全体からプロンプトを作って生成し、断片ごとに sink.accept(piece) を呼ぶ。
// - 長すぎる会話は、古い発言から落として文脈に収める(先頭の system と最新の発言は残す)。
// - 前回の続きの会話なら、共通する先頭部分の KV キャッシュを再利用して、読み直しを省く。
// sink が false を返す、または nativeStop が呼ばれたら止める。戻り値: 成功なら空文字、失敗ならメッセージ。
JNIEXPORT jstring JNICALL
Java_com_mobileailocal_domain_LlamaCppEngine_nativeGenerate(
        JNIEnv *env, jobject, jobjectArray roles, jobjectArray contents,
        jfloat temperature, jfloat topP, jint maxTokens, jint seed, jobject sink) {
    if (!g_model || !g_ctx) return make_jstring(env, "E_NO_MODEL");
    g_stop = false;

    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    const int n_ctx = (int) llama_n_ctx(g_ctx);

    // 1) 会話を読み出す。
    const jsize n = env->GetArrayLength(roles);
    std::vector<std::pair<std::string, std::string>> conv(n);  // (role, content)
    for (jsize i = 0; i < n; i++) {
        jobject r = env->GetObjectArrayElement(roles, i);
        conv[i].first = jstr(env, (jstring) r);
        env->DeleteLocalRef(r);
        jobject c = env->GetObjectArrayElement(contents, i);
        conv[i].second = jbytes(env, (jbyteArray) c);
        env->DeleteLocalRef(c);
    }
    if (conv.empty()) return make_jstring(env, "E_EMPTY");

    // 2) テンプレート適用 → トークン化。収まらなければ古い発言から落として繰り返す。
    const char *tmpl = llama_model_chat_template(g_model, nullptr);
    const int reserve = std::max(64, std::min<int>(maxTokens, n_ctx / 2));  // 生成用に空けておく枠
    std::vector<llama_token> tokens;
    while (true) {
        std::vector<llama_chat_message> msgs(conv.size());
        for (size_t i = 0; i < conv.size(); i++) msgs[i] = {conv[i].first.c_str(), conv[i].second.c_str()};
        std::vector<char> buf(1 << 14);
        int32_t len = llama_chat_apply_template(tmpl ? tmpl : "chatml", msgs.data(), msgs.size(), true, buf.data(), buf.size());
        // モデルのテンプレートを認識できないときは chatml でやり直す
        if (len < 0 && tmpl) {
            tmpl = nullptr;
            len = llama_chat_apply_template("chatml", msgs.data(), msgs.size(), true, buf.data(), buf.size());
        }
        if (len > (int32_t) buf.size()) {
            buf.resize(len);
            len = llama_chat_apply_template(tmpl ? tmpl : "chatml", msgs.data(), msgs.size(), true, buf.data(), buf.size());
        }
        if (len < 0) return make_jstring(env, "E_TEMPLATE");
        std::string prompt(buf.data(), len);

        int32_t nt = -llama_tokenize(vocab, prompt.c_str(), prompt.size(), nullptr, 0, true, true);
        tokens.assign(nt, 0);
        if (llama_tokenize(vocab, prompt.c_str(), prompt.size(), tokens.data(), tokens.size(), true, true) < 0)
            return make_jstring(env, "E_TOKENIZE");
        if ((int) tokens.size() <= n_ctx - reserve) break;

        // 先頭の system 以外で最も古い発言を落とす(最新の発言だけは残す)。
        size_t first = (conv[0].first == "system") ? 1 : 0;
        if (conv.size() <= first + 1) return make_jstring(env, "E_TOO_LONG");
        conv.erase(conv.begin() + first);
        // 先頭が assistant の発言だけ残ってしまう場合も落とす。
        if (conv.size() > first + 1 && conv[first].first == "assistant") conv.erase(conv.begin() + first);
    }
    const int budget = std::min<int>(maxTokens, n_ctx - (int) tokens.size());

    // 3) サンプラー(top_p → 温度 → 乱数)。温度 0 以下は貪欲法。
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist((uint32_t) seed));
    }

    // 4) 前回と共通する先頭部分は KV キャッシュを再利用し、残りだけ流し込む。
    llama_memory_t mem = llama_get_memory(g_ctx);
    size_t common = 0;
    while (common < g_cache.size() && common < tokens.size() && g_cache[common] == tokens[common]) common++;
    if (common >= tokens.size()) common = tokens.size() - 1;  // 最後の1つは必ず計算して、次の出力の元にする
    if (common == 0 || !llama_memory_seq_rm(mem, 0, (llama_pos) common, -1)) {
        llama_memory_clear(mem, true);
        common = 0;
    }
    g_cache.resize(common);
    clear_errs();
    LOGI("prompt=%zu tokens, reused=%zu", tokens.size(), common);

    const int n_batch = (int) llama_n_batch(g_ctx);
    for (size_t i = common; i < tokens.size(); i += n_batch) {
        if (g_stop) { llama_sampler_free(smpl); g_cache.clear(); llama_memory_clear(mem, true); return make_jstring(env, ""); }
        int cnt = std::min<int>(n_batch, tokens.size() - i);
        llama_batch b = llama_batch_get_one(tokens.data() + i, cnt);
        int rc = llama_decode(g_ctx, b);
        if (rc != 0) {
            llama_sampler_free(smpl);
            g_cache.clear(); llama_memory_clear(mem, true);
            return make_jstring(env, with_detail("E_DECODE_PROMPT", "llama_decode returned " + std::to_string(rc)));
        }
        g_cache.insert(g_cache.end(), tokens.begin() + i, tokens.begin() + i + cnt);
    }

    // 5) 1トークンずつ生成して Kotlin に渡す。
    jclass cls = env->GetObjectClass(sink);
    jmethodID accept = env->GetMethodID(cls, "accept", "(Ljava/lang/String;)Z");
    std::string pending;  // UTF-8 の途中で切れた断片を次に持ち越す
    std::string error;
    for (int i = 0; i < budget && !g_stop; i++) {
        llama_token tok = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        char piece[256];
        int pl = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (pl > 0) pending.append(piece, pl);
        size_t ok = valid_utf8_prefix(pending);
        if (ok > 0) {
            jstring js = make_jstring(env, pending.substr(0, ok));
            jboolean cont = env->CallBooleanMethod(sink, accept, js);
            env->DeleteLocalRef(js);
            pending.erase(0, ok);
            if (env->ExceptionCheck()) { env->ExceptionClear(); break; }
            if (!cont) break;
        }

        llama_batch b = llama_batch_get_one(&tok, 1);
        int rc = llama_decode(g_ctx, b);
        if (rc != 0) { error = with_detail("E_DECODE_GEN", "llama_decode returned " + std::to_string(rc)); break; }
        g_cache.push_back(tok);
    }
    llama_sampler_free(smpl);
    if (!error.empty()) { g_cache.clear(); llama_memory_clear(mem, true); }
    return make_jstring(env, error);
}

}  // extern "C"
