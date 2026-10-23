"""AI が書いた Python を実行するための監査フック式サンドボックス(検証用)。

設計上の要点:
- 監査フックは sys.addaudithook で入れる。外せない。
- フック本体はグローバル変数を一切参照しない。必要な組み込み関数・定数はすべて既定引数/クロージャに束縛する。
  (例外のトレースバックから frame.f_globals / f_builtins を取られても、書き換えでフックの挙動が変わらないようにする)
- パス判定は realpath を使わず自前の字句的な正規化。代わりに symlink/link を作る操作を禁止する。
"""
import io
import os
import sys
import traceback

_installed = False
_cur = None

# 禁止する import のルート名(sys.modules にキャッシュ済みだと import イベントが出ないので、install でキャッシュも消す)
_DENY_IMPORT_ROOTS = ("java", "android", "com", "chaquopy", "org", "ctypes", "_ctypes")

# 先頭一致で即禁止するイベント
_DENY_PREFIX = (
    "socket.", "subprocess.", "ctypes.", "_posixsubprocess.", "pty.",
    "os.exec", "os.spawn", "os.posix_spawn", "os.fork", "os.system", "os.kill", "os.killpg",
    "os.symlink", "os.link", "os.chdir", "os.chroot", "os.mkfifo", "os.mknod", "os.fchdir",
    "os.setxattr", "os.removexattr", "os.startfile", "os.add_dll_directory",
    "urllib.", "http.client.", "ftplib.", "smtplib.", "imaplib.", "poplib.", "telnetlib.", "nntplib.",
    "gc.get_", "sys.settrace", "sys.setprofile", "sys.monitoring", "sys._current_frames",
    "sys._current_exceptions", "threading.settrace", "threading.setprofile",
    "webbrowser.", "mmap.", "resource.", "syslog.", "winreg.",
)

# パス引数(インデックス)を持ち、作業フォルダ内に限るイベント
_PATH_EVENTS = {
    "os.listdir": (0,), "os.scandir": (0,), "os.remove": (0,), "os.rename": (0, 1), "os.mkdir": (0,),
    "os.rmdir": (0,), "os.chmod": (0,), "os.chown": (0,), "os.truncate": (0,), "os.utime": (0,),
    "os.walk": (0,), "os.fwalk": (0,), "glob.glob": (0,), "glob.glob/2": (0,),
    "shutil.copyfile": (0, 1), "shutil.copymode": (0, 1), "shutil.copystat": (0, 1),
    "shutil.copytree": (0, 1), "shutil.move": (0, 1), "shutil.rmtree": (0,),
    "shutil.make_archive": (0, 1), "shutil.unpack_archive": (0, 1), "shutil.chown": (0,),
    "sqlite3.connect": (0,), "os.listxattr": (0,), "os.getxattr": (0,),
}


def _make_hook(ws_roots, read_roots, cwd0, py_roots):

    def norm(p, _isinstance=isinstance, _str=str, _bytes=bytes, _cwd0=cwd0):
        """字句的な正規化(.. と . と // を処理)。str にできなければ None"""
        if _isinstance(p, _bytes):
            p = p.decode("utf-8", "surrogateescape")
        if not _isinstance(p, _str):
            return None
        if not p.startswith("/"):
            p = _cwd0 + "/" + p
        out = []
        for part in p.split("/"):
            if part == "" or part == ".":
                continue
            if part == "..":
                if out:
                    out.pop()
                continue
            out.append(part)
        return "/" + "/".join(out)

    def in_ws(p, _roots=ws_roots):
        for r in _roots:
            if p == r or p.startswith(r + "/"):
                return True
        return False

    def hook(event, args,
             _PE=PermissionError, _IE=ImportError, _isinstance=isinstance, _int=int, _str=str, _len=len,
             _norm=norm, _in_ws=in_ws, _dp=_DENY_PREFIX, _pe=_PATH_EVENTS, _dr=_DENY_IMPORT_ROOTS,
             _read_roots=read_roots, _py_roots=py_roots):
        # 1. 先頭一致で禁止
        if event.startswith(_dp):
            raise _PE("sandbox: '" + event + "' is not allowed") from None
        # 2. open(): 作業フォルダ内は読み書き可。ランタイムのフォルダは読み取りのみ
        if event == "open":
            p = args[0]
            if _isinstance(p, _int):
                return
            q = _norm(p)
            if q is None:
                raise _PE("sandbox: invalid path") from None
            if _in_ws(q):
                return
            flags = args[2] if _len(args) > 2 and _isinstance(args[2], _int) else 0
            writing = (flags & 3) != 0 or (flags & 0o3100) != 0   # O_WRONLY|O_RDWR|O_CREAT|O_TRUNC|O_APPEND
            if not writing:
                for r in _read_roots:
                    if q == r or q.startswith(r + "/"):
                        return
            raise _PE("sandbox: open outside workspace: " + _str(p) + " args=" + _str(args)) from None
        # 3. import
        if event == "import":
            name = args[0]
            root = name.split(".")[0]
            if root in _dr:
                # stdlib(platform/uuid 等)は ImportError を握りつぶして代替処理に進むので、ImportError を使う
                raise _IE("sandbox: import of '" + name + "' is not allowed") from None
            fn = args[1]
            if _isinstance(fn, _str) and fn.endswith(".so"):
                q = _norm(fn)
                ok = False
                for r in _py_roots:
                    if q is not None and q.startswith(r + "/"):
                        ok = True
                if not ok:
                    raise _PE("sandbox: loading native module not allowed: " + fn) from None
            return
        # 4. パスを取る他のファイル操作
        idx = _pe.get(event)
        if idx is not None:
            for i in idx:
                if i < _len(args):
                    p = args[i]
                    if _isinstance(p, _int) or p is None:
                        continue
                    q = _norm(p)
                    if q is None or not _in_ws(q):
                        raise _PE("sandbox: path outside workspace in " + event + ": " + _str(p)) from None
            return

    return hook


_SKIP_WARM = ("ctypes", "_ctypes", "_curses", "_curses_panel", "_tkinter", "_dbm", "_gdbm", "readline", "_testcapi",
              "_ctypes_test", "_testinternalcapi", "_testbuffer", "_testmultiphase", "_xxtestfuzz", "xxlimited",
              "_posixshmem", "_xxsubinterpreters", "_interpreters", "_interpchannels", "_interpqueues")


def _warmup(mod_names):
    """Chaquopy は標準ライブラリの拡張モジュールを import 時に files/chaquopy/AssetFinder へ書き出す(書き込み)。
    フックを入れた後はその書き込みを許可できないので、先にすべて import して展開を済ませておく。
    mod_names は Kotlin 側が stdlib-<abi>.imy(APK の asset)から列挙して "|" 区切りで渡す。"""
    import importlib
    import time
    t0 = time.time()
    ok, bad = [], []
    for mod in mod_names.split("|"):
        if not mod or mod in _SKIP_WARM:
            continue
        try:
            importlib.import_module(mod)
            ok.append(mod)
        except BaseException as e:
            bad.append((mod, repr(e)[:60]))
    return "warm ok=%d bad=%s in %.0fms" % (len(ok), bad, (time.time() - t0) * 1000)


def install(workdir, read_dirs, py_dirs, warm_modules):
    """最初の実行の前に1回だけ呼ぶ(2回目以降は何もしない)。"""
    global _installed
    if _installed:
        return
    _installed = True
    # /data/user/0 と /data/data は symlink で同じ場所。字句比較なので両方の表記を許可側に入れる
    def both(d):
        return tuple(dict.fromkeys((os.path.abspath(d), os.path.realpath(d))))
    ws = os.path.realpath(workdir)
    os.makedirs(ws, exist_ok=True)
    os.chdir(ws)
    ws_roots = both(workdir) + both(ws)
    read_roots = tuple(x for d in read_dirs.split("|") for x in both(d))
    py_roots = tuple(x for d in py_dirs.split("|") for x in both(d))
    info = _warmup(warm_modules)
    # キャッシュ済みの危険なモジュールを sys.modules から外す(import イベントが出ない抜け道を塞ぐ)
    for k in list(sys.modules):
        if k.split(".")[0] in _DENY_IMPORT_ROOTS:
            del sys.modules[k]
    sys.addaudithook(_make_hook(ws_roots, read_roots, ws, py_roots))
    # 実行コードから自分自身(この module)を import できないようにする
    sys.modules.pop("sandbox", None)
    return info


class _Out:
    def __init__(self, limit):
        self.limit = limit
        self.parts = []
        self.n = 0
        self.truncated = False

    def write(self, s):
        if not isinstance(s, str):
            s = str(s)
        room = self.limit - self.n
        if room <= 0:
            self.truncated = True
            return len(s)
        if len(s) > room:
            s = s[:room]
            self.truncated = True
        self.parts.append(s)
        self.n += len(s)
        return len(s)

    def flush(self):
        pass

    def isatty(self):
        return False

    def value(self):
        v = "".join(self.parts)
        if self.truncated:
            v += "\n[output truncated at %d chars]" % self.limit
        return v


def run(code, limit=65536):
    """code を実行して [status, stdout, stderr] を返す。status: ok | error"""
    global _cur
    out, err = _Out(limit), _Out(limit)
    _cur = (out, err)
    old = (sys.stdout, sys.stderr)
    sys.stdout, sys.stderr = out, err
    status = "ok"
    import warnings
    warnings.simplefilter("ignore", ResourceWarning)
    try:
        g = {"__name__": "__main__"}
        exec(compile(code, "<ai_code>", "exec"), g)
    except SystemExit as e:
        if e.code not in (None, 0):
            status = "error"
            err.write("SystemExit: %r\n" % (e.code,))
    except BaseException as e:
        status = "error"
        tb = e.__traceback__
        if tb is not None:
            tb = tb.tb_next  # この run() 自身のフレームを隠す
        err.write("".join(traceback.format_exception(type(e), e, tb)))
    finally:
        sys.stdout, sys.stderr = old
    return [status, out.value(), err.value()]


def partial():
    """実行中(時間切れ時)にそこまでの出力を取り出す"""
    if _cur is None:
        return ["", ""]
    return [_cur[0].value(), _cur[1].value()]


def info():
    return sys.version


def mods():
    return sorted(sys.modules)
