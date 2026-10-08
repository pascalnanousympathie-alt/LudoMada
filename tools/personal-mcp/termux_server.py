#!/usr/bin/env python3
import base64
import json
import os
import queue
import signal
import subprocess
import sys
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
"
        pdf += f"trailer\n<< /Size {len(objects)+1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n"
        p.write_bytes(pdf.encode("latin-1", "replace"))
        return text_result(f"WROTE_PDF {p}")

    if name == "mcp_stats":
        with _lock:
            rows = list(_processes.items())
        return text_result(json.dumps({
            "server": NAME,
            "version": VERSION,
            "project": str(DEFAULT_PROJECT),
            "processes": len(rows),
            "running_processes": sum(1 for _, state in rows if state.proc.poll() is None),
            "pid_list": [state.proc.pid for _, state in rows],
        }, ensure_ascii=False))

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
