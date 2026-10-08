#!/usr/bin/env python3
import base64
import json
import os
import queue
import signal
import subprocess
import threading
import time
import uuid
from pathlib import Path

NAME = "ludomada-personal-mcp"
VERSION = "1.0.0"
PROTOCOL = "2025-06-18"
MAX_TEXT = 200_000
MAX_BYTES = 8 * 1024 * 1024
DEFAULT_PROJECT = Path(os.environ.get("LUDOMADA_PROJECT_DIR", str(Path.home() / "LudoMada"))).expanduser().resolve()

_processes = {}
_lock = threading.Lock()


def clip(value, limit=MAX_TEXT):
    s = "" if value is None else str(value)
    return s if len(s) <= limit else s[:limit] + "\n[TRUNCATED]"


def resolve_dir(value=None):
    p = Path(os.path.expanduser(str(value or DEFAULT_PROJECT))).resolve()
    if not p.exists() or not p.is_dir():
        raise RuntimeError(f"directory not found: {p}")
    return p


def resolve_path(value):
    return Path(os.path.expanduser(str(value))).resolve()


def shell(command, cwd=None, timeout=120):
    timeout = max(1, min(int(timeout), 1800))
    cp = subprocess.run(
        command,
        shell=True,
        cwd=str(resolve_dir(cwd)),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=timeout,
    )
    return {"exit_code": cp.returncode, "output": clip(cp.stdout)}


class Process:
    def __init__(self, command, cwd):
        self.command = command
        self.cwd = str(cwd)
        self.proc = subprocess.Popen(
            command,
            shell=True,
            cwd=str(cwd),
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
        self.q = queue.Queue()
        self.started_at = time.time()
        self.reader = threading.Thread(target=self._reader, daemon=True)
        self.reader.start()

    def _reader(self):
        try:
            for line in self.proc.stdout:
                self.q.put(line)
        except Exception as exc:
            self.q.put(f"[reader-error] {exc}\n")
        finally:
            self.q.put(None)

    def read(self, limit=MAX_TEXT):
        out = []
        size = 0
        while size < limit:
            try:
                item = self.q.get_nowait()
            except queue.Empty:
                break
            if item is None:
                continue
            out.append(item)
            size += len(item)
        return clip("".join(out), limit)


def text_result(value):
    return {"content": [{"type": "text", "text": clip(value)}], "isError": False}


def error_result(message):
    return {"content": [{"type": "text", "text": clip(message)}], "isError": True}


def t(name, description, properties=None, required=None, readonly=False, destructive=False):
    schema = {
        "type": "object",
        "properties": properties or {},
        "additionalProperties": False,
    }
    if required:
        schema["required"] = required
    return {
        "name": name,
        "description": description,
        "inputSchema": schema,
        "annotations": {
            "readOnlyHint": readonly,
            "destructiveHint": destructive,
            "idempotentHint": readonly,
            "openWorldHint": False,
        },
    }


def tools_list():
    return [
        t("device_info", "Return device, Android, Termux and project runtime information.", readonly=True),
        t("shell_exec", "Execute a shell command in the Termux environment.", {
            "command": {"type": "string"},
            "cwd": {"type": "string"},
            "timeout": {"type": "integer", "minimum": 1, "maximum": 1800},
        }, ["command"]),
        t("file_read", "Read a UTF-8 text file.", {
            "path": {"type": "string"},
            "max_chars": {"type": "integer", "minimum": 1, "maximum": MAX_TEXT},
        }, ["path"], readonly=True),
        t("file_read_base64", "Read a binary file as base64, capped at 8 MiB.", {
            "path": {"type": "string"},
        }, ["path"], readonly=True),
        t("file_write", "Write UTF-8 text or base64 bytes.", {
            "path": {"type": "string"},
            "content": {"type": "string"},
            "encoding": {"type": "string", "enum": ["utf-8", "base64"]},
        }, ["path", "content"], destructive=True),
        t("file_list", "List directory entries.", {
            "path": {"type": "string"},
        }, ["path"], readonly=True),
        t("file_stat", "Return file metadata.", {
            "path": {"type": "string"},
        }, ["path"], readonly=True),
        t("file_move", "Move or rename a file or directory.", {
            "source": {"type": "string"},
            "destination": {"type": "string"},
        }, ["source", "destination"], destructive=True),
        t("directory_create", "Create a directory tree.", {
            "path": {"type": "string"},
        }, ["path"], destructive=True),
        t("file_search", "Search source trees using ripgrep, falling back to grep.", {
            "query": {"type": "string"},
            "path": {"type": "string"},
            "max_chars": {"type": "integer", "minimum": 1, "maximum": MAX_TEXT},
        }, ["query"], readonly=True),
        t("process_start", "Start a persistent shell process and return a session id.", {
            "command": {"type": "string"},
            "cwd": {"type": "string"},
        }, ["command"], destructive=True),
        t("process_read_output", "Read buffered output from a process session.", {
            "session_id": {"type": "string"},
            "max_chars": {"type": "integer", "minimum": 1, "maximum": MAX_TEXT},
        }, ["session_id"], readonly=True),
        t("process_send_input", "Send stdin to a process session.", {
            "session_id": {"type": "string"},
            "input": {"type": "string"},
        }, ["session_id", "input"], destructive=True),
        t("process_wait", "Wait for a process session and return its exit code/output.", {
            "session_id": {"type": "string"},
            "timeout": {"type": "integer", "minimum": 0, "maximum": 1800},
        }, ["session_id"], readonly=True),
        t("process_kill", "Terminate a process session.", {
            "session_id": {"type": "string"},
            "signal": {"type": "integer", "minimum": 1, "maximum": 64},
        }, ["session_id"], destructive=True),
        t("process_list", "List managed persistent process sessions.", readonly=True),
        t("screenshot", "Capture the current Android screen as PNG when screencap is permitted.", readonly=True),
        t("app_launch", "Launch an Android package or explicit component.", {
            "package": {"type": "string"},
            "activity": {"type": "string"},
        }, ["package"], destructive=True),
        t("apk_install", "Install an APK using pm/root when possible; otherwise open the Android installer.", {
            "path": {"type": "string"},
        }, ["path"], destructive=True),
        t("mcp_self_test", "Run a local health check of this MCP server and the official tunnel client.", readonly=True),
    ]


def call(name, a):
    if name == "device_info":
        cmds = [
            "uname -a",
            "getprop ro.product.model 2>/dev/null || true",
            "getprop ro.build.version.release 2>/dev/null || true",
            "printf 'TERMUX_PREFIX=%s\\n' \"$PREFIX\"",
            "printf 'HOME=%s\\n' \"$HOME\"",
        ]
        lines = []
        for c in cmds:
            cp = subprocess.run(c, shell=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            if cp.stdout.strip():
                lines.append(cp.stdout.strip())
        lines.append(f"PROJECT={DEFAULT_PROJECT}")
        lines.append(f"PYTHON={os.sys.executable}")
        lines.append(f"TUNNEL_CLIENT={subprocess.getoutput('command -v tunnel-client || true').strip() or 'NOT_FOUND'}")
        return text_result("\n".join(lines))

    if name == "shell_exec":
        try:
            return text_result(json.dumps(shell(a["command"], a.get("cwd"), a.get("timeout", 120)), ensure_ascii=False))
        except subprocess.TimeoutExpired:
            return error_result("command timed out")
    
    if name == "file_read":
        p = resolve_path(a["path"])
        max_chars = int(a.get("max_chars", MAX_TEXT))
        return text_result(p.read_text(encoding="utf-8")[:max_chars])

    if name == "file_read_base64":
        p = resolve_path(a["path"])
        raw = p.read_bytes()
        if len(raw) > MAX_BYTES:
            raise RuntimeError(f"file too large: {len(raw)} > {MAX_BYTES} bytes")
        return text_result(base64.b64encode(raw).decode("ascii"))

    if name == "file_write":
        p = resolve_path(a["path"])
        p.parent.mkdir(parents=True, exist_ok=True)
        if a.get("encoding", "utf-8") == "base64":
            p.write_bytes(base64.b64decode(a["content"], validate=True))
        else:
            p.write_text(a["content"], encoding="utf-8")
        return text_result(f"WROTE {p}")

    if name == "file_list":
        p = resolve_path(a["path"])
        rows = []
        for child in sorted(p.iterdir(), key=lambda x: (not x.is_dir(), x.name.lower())):
            rows.append({
                "name": child.name,
                "type": "dir" if child.is_dir() else "file",
                "size": child.stat().st_size if child.is_file() else None,
            })
        return text_result(json.dumps(rows, ensure_ascii=False))

    if name == "file_stat":
        p = resolve_path(a["path"])
        st = p.stat()
        return text_result(json.dumps({
            "path": str(p),
            "exists": p.exists(),
            "is_file": p.is_file(),
            "is_dir": p.is_dir(),
            "size": st.st_size,
            "mode": oct(st.st_mode),
            "mtime": st.st_mtime,
        }, ensure_ascii=False))

    if name == "file_move":
        src, dst = resolve_path(a["source"]), resolve_path(a["destination"])
        dst.parent.mkdir(parents=True, exist_ok=True)
        src.rename(dst)
        return text_result(f"MOVED {src} -> {dst}")

    if name == "directory_create":
        p = resolve_path(a["path"])
        p.mkdir(parents=True, exist_ok=True)
        return text_result(f"CREATED {p}")

    if name == "file_search":
        q = a["query"]
        root = resolve_path(a.get("path") or str(DEFAULT_PROJECT))
        out = subprocess.run(
            ["rg", "-n", "--hidden", "--glob", "!.git", q, str(root)],
            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        )
        if out.returncode not in (0, 1):
            out = subprocess.run(
                ["grep", "-RIn", "--exclude-dir=.git", q, str(root)],
                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            )
        return text_result(out.stdout[:int(a.get("max_chars", MAX_TEXT))])

    if name == "process_start":
        cwd = resolve_dir(a.get("cwd"))
        state = Process(a["command"], cwd)
        sid = uuid.uuid4().hex[:16]
        with _lock:
            _processes[sid] = state
        return text_result(json.dumps({
            "session_id": sid, "pid": state.proc.pid,
            "command": state.command, "cwd": state.cwd,
        }, ensure_ascii=False))

    if name == "process_read_output":
        sid = a["session_id"]
        with _lock:
            state = _processes.get(sid)
        if not state:
            raise RuntimeError("unknown session_id")
        return text_result(state.read(int(a.get("max_chars", MAX_TEXT))))

    if name == "process_send_input":
        sid = a["session_id"]
        with _lock:
            state = _processes.get(sid)
        if not state:
            raise RuntimeError("unknown session_id")
        if state.proc.poll() is not None:
            return error_result("process already exited")
        state.proc.stdin.write(a["input"])
        state.proc.stdin.flush()
        return text_result("INPUT_SENT")

    if name == "process_wait":
        sid = a["session_id"]
        with _lock:
            state = _processes.get(sid)
        if not state:
            raise RuntimeError("unknown session_id")
        timeout = int(a.get("timeout", 0))
        try:
            code = state.proc.wait(timeout=timeout if timeout else None)
        except subprocess.TimeoutExpired:
            code = None
        return text_result(json.dumps({
            "session_id": sid, "exit_code": code, "output": state.read()
        }, ensure_ascii=False))

    if name == "process_kill":
        sid = a["session_id"]
        with _lock:
            state = _processes.get(sid)
        if not state:
            raise RuntimeError("unknown session_id")
        sig = int(a.get("signal", signal.SIGTERM))
        try:
            state.proc.send_signal(sig)
        except ProcessLookupError:
            pass
        return text_result(json.dumps({"session_id": sid, "signal": sig, "exit_code": state.proc.poll()}))

    if name == "process_list":
        with _lock:
            items = list(_processes.items())
        rows = []
        for sid, state in items:
            rows.append({
                "session_id": sid,
                "pid": state.proc.pid,
                "running": state.proc.poll() is None,
                "exit_code": state.proc.poll(),
                "command": state.command,
                "cwd": state.cwd,
                "started_at": state.started_at,
            })
        return text_result(json.dumps(rows, ensure_ascii=False))

    if name == "screenshot":
        cp = subprocess.run(["screencap", "-p"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if cp.returncode != 0 or not cp.stdout:
            return error_result("screencap unavailable or permission denied: " + cp.stderr.decode("utf-8", "replace"))
        if len(cp.stdout) > MAX_BYTES:
            return error_result("screenshot exceeds response cap")
        return {
            "content": [{
                "type": "image",
                "data": base64.b64encode(cp.stdout).decode("ascii"),
                "mimeType": "image/png",
            }],
            "isError": False,
        }

    if name == "app_launch":
        pkg = a["package"]
        activity = a.get("activity")
        cmd = ["/system/bin/am", "start", "-n", activity] if activity else ["/system/bin/monkey", "-p", pkg, "1"]
        cp = subprocess.run(cmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        return text_result(json.dumps({"exit_code": cp.returncode, "output": clip(cp.stdout)}, ensure_ascii=False))

    if name == "apk_install":
        p = resolve_path(a["path"])
        if not p.exists():
            raise RuntimeError(f"APK not found: {p}")
        root = subprocess.run(
            ["su", "-c", f"/system/bin/pm install -r {str(p)!r}"],
            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        )
        if root.returncode == 0:
            return text_result(root.stdout.strip() or "APK_INSTALLED")
        opener = subprocess.run(
            ["termux-open", str(p)], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT
        )
        return text_result(json.dumps({
            "root_install_output": root.stdout,
            "installer_ui_exit_code": opener.returncode,
            "installer_ui_output": opener.stdout,
        }, ensure_ascii=False))

    if name == "mcp_self_test":
        tunnel = subprocess.run(
            ["sh", "-lc", "command -v tunnel-client && tunnel-client --version"],
            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        )
        project_ok = DEFAULT_PROJECT.exists() and DEFAULT_PROJECT.is_dir()
        return text_result(json.dumps({
            "mcp": f"{NAME}@{VERSION}",
            "project_exists": project_ok,
            "project": str(DEFAULT_PROJECT),
            "tunnel_client": clip(tunnel.stdout),
            "tunnel_client_ok": tunnel.returncode == 0,
        }, ensure_ascii=False))

    raise RuntimeError(f"unknown tool: {name}")


def handle(msg):
    method = msg.get("method")
    mid = msg.get("id")
    if method == "initialize":
        return {"jsonrpc": "2.0", "id": mid, "result": {
            "protocolVersion": PROTOCOL,
            "capabilities": {"tools": {}},
            "serverInfo": {"name": NAME, "version": VERSION},
        }}
    if method == "notifications/initialized":
        return None
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": mid, "result": {"tools": tools_list()}}
    if method == "tools/call":
        try:
            args = msg.get("params", {}).get("arguments", {}) or {}
            return {"jsonrpc": "2.0", "id": mid,
                    "result": call(msg.get("params", {}).get("name"), args)}
        except subprocess.TimeoutExpired:
            return {"jsonrpc": "2.0", "id": mid, "result": error_result("command timed out")}
        except Exception as exc:
            return {"jsonrpc": "2.0", "id": mid, "result": error_result(f"{type(exc).__name__}: {exc}")}
    return {"jsonrpc": "2.0", "id": mid,
            "error": {"code": -32601, "message": f"method not found: {method}"}}


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
            response = handle(msg)
            if response is not None:
                print(json.dumps(response, ensure_ascii=False), flush=True)
        except Exception as exc:
            print(json.dumps({
                "jsonrpc": "2.0", "id": None,
                "error": {"code": -32700, "message": str(exc)},
            }), flush=True)


if __name__ == "__main__":
    main()
