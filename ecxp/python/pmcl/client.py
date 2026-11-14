"""和启动器之间的连接。只连 PMCL 给的本机地址。"""

import json
import os
import socket
import threading


class PmclError(Exception):
    def __init__(self, code, message=""):
        self.code = code or "failed"
        self.message = message or ""
        text = self.code if not self.message else f"{self.code}: {self.message}"
        super().__init__(text)


def hosted():
    """当前进程是不是启动器拉起来的。"""
    return bool(os.environ.get("PMCL_ECXP_TOKEN") and os.environ.get("PMCL_ECXP_ENDPOINT"))


def connect(endpoint=None, token=None, timeout=10):
    """连回启动器。不传参数时用环境变量。"""
    endpoint = endpoint or os.environ.get("PMCL_ECXP_ENDPOINT")
    token = token or os.environ.get("PMCL_ECXP_TOKEN")
    if not endpoint or not token:
        raise PmclError("not_hosted", "This process was not started by PMCL.")
    host, sep, port = endpoint.rpartition(":")
    if not sep or not port.isdigit():
        raise PmclError("bad_endpoint", endpoint)
    sock = socket.create_connection((host, int(port)), timeout)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    app = _Connection(sock)
    try:
        app.info = app._request("hello", {"token": token}, timeout)
    except Exception:
        app.close()
        raise
    return app


class _Connection:
    def __init__(self, sock):
        self._sock = sock
        self._reader_file = sock.makefile("rb", buffering=0)
        self._write = threading.Lock()
        self._pending = {}
        self._callbacks = {}
        self._next = 0
        self._ids = threading.Lock()
        self.info = None
        self.on_event = None
        self.on_activate = None
        self.on_shutdown = None
        self.on_disconnect = None
        self._closed = False
        self._reader = threading.Thread(target=self._read_loop, name="pmcl-python", daemon=True)
        self._reader.start()

    def call(self, method, args=()):
        return self._request(method, list(args), None)

    def methods(self):
        return self.call("host.methods")

    def log(self, message, level="info"):
        self.call("host.log", (level, message))

    def callback(self, fn, once=True):
        with self._ids:
            self._next += 1
            cid = f"cb{self._next}"
        self._callbacks[cid] = (fn, once)
        return {"$callback": cid}

    def close(self):
        self._closed = True
        try:
            self._reader_file.close()
        except OSError:
            pass
        try:
            self._sock.close()
        except OSError:
            pass

    def _request(self, method, params, timeout):
        with self._ids:
            self._next += 1
            call_id = self._next
        box = threading.Event()
        holder = {}
        self._pending[call_id] = (box, holder)
        frame = json.dumps(
            {"id": call_id, "method": method, "params": params},
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8") + b"\n"
        with self._write:
            try:
                self._sock.sendall(frame)
            except OSError as e:
                self._pending.pop(call_id, None)
                raise PmclError("disconnected", str(e)) from e
        if not box.wait(timeout):
            self._pending.pop(call_id, None)
            raise PmclError("failed", "timed out")
        if "error" in holder:
            code, message = holder["error"]
            raise PmclError(code, message)
        return holder.get("result")

    def _read_loop(self):
        try:
            while not self._closed:
                line = self._reader_file.readline()
                if not line:
                    break
                self._handle(line.decode("utf-8"))
        except OSError:
            pass
        finally:
            for box, holder in list(self._pending.values()):
                holder["error"] = ("disconnected", "PMCL closed the connection.")
                box.set()
            self._pending.clear()
            if self.on_disconnect:
                self.on_disconnect()

    def _handle(self, line):
        try:
            root = json.loads(line)
        except json.JSONDecodeError:
            return
        if not isinstance(root, dict):
            return
        event = root.get("event")
        if isinstance(event, str):
            data = root.get("data")
            if event == "activate" and self.on_activate:
                self.on_activate()
            elif event == "shutdown" and self.on_shutdown:
                self.on_shutdown()
            elif event == "callback":
                self._run_callback(data)
            elif event == "pmcl" and self.on_event:
                kind = ""
                payload = None
                if isinstance(data, dict):
                    kind = data.get("type") or ""
                    payload = data.get("data")
                self.on_event(kind, payload)
            return
        call_id = root.get("id")
        pending = self._pending.pop(call_id, None)
        if pending is None:
            return
        box, holder = pending
        error = root.get("error")
        if isinstance(error, dict):
            holder["error"] = (error.get("code") or "failed", error.get("message") or "")
        else:
            holder["result"] = root.get("result")
        box.set()

    def _run_callback(self, data):
        if not isinstance(data, dict):
            return
        cid = data.get("id")
        entry = self._callbacks.get(cid)
        if entry is None:
            return
        fn, once = entry
        if once:
            self._callbacks.pop(cid, None)
        args = data.get("args")
        fn(args if isinstance(args, list) else [])
