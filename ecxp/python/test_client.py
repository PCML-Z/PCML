import json
import socket
import threading
import unittest

from pmcl.api import connect as open_pmcl
from pmcl.client import PmclError, connect


class FakeLauncher(threading.Thread):
    def __init__(self):
        super().__init__(daemon=True)
        self._server = socket.socket()
        self._server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._server.bind(("127.0.0.1", 0))
        self._server.listen(1)
        self.port = self._server.getsockname()[1]

    def run(self):
        conn, _ = self._server.accept()
        with conn, conn.makefile("rwb", buffering=0) as stream:
            while True:
                line = stream.readline()
                if not line:
                    return
                req = json.loads(line)
                if req["method"] == "hello":
                    token = req["params"]["token"]
                    if token != "secret":
                        body = {"id": req["id"], "error": {"code": "unauthorized", "message": "bad token"}}
                    else:
                        body = {"id": req["id"], "result": {"launcher": "PMCL", "apiAvailable": True}}
                elif req["method"] == "versions.listLocalVersions":
                    body = {"id": req["id"], "result": [{"id": "1.21.1", "launchable": True}]}
                elif req["method"] == "ui.showDialog":
                    cb = req["params"][-1]["$callback"]
                    stream.write((json.dumps({"id": req["id"], "result": None}) + "\n").encode())
                    stream.write((json.dumps({"event": "callback", "data": {"id": cb, "args": [True]}}) + "\n").encode())
                    continue
                else:
                    body = {"id": req["id"], "error": {"code": "unknown_method", "message": req["method"]}}
                stream.write((json.dumps(body) + "\n").encode())


class ClientTest(unittest.TestCase):
    def test_lists_versions(self):
        server = FakeLauncher()
        server.start()
        app = connect(f"127.0.0.1:{server.port}", "secret", timeout=3)
        try:
            self.assertEqual("PMCL", app.info["launcher"])
            versions = app.call("versions.listLocalVersions")
            self.assertEqual("1.21.1", versions[0]["id"])
        finally:
            app.close()

    def test_rejects_a_bad_token(self):
        server = FakeLauncher()
        server.start()
        with self.assertRaises(PmclError) as caught:
            connect(f"127.0.0.1:{server.port}", "nope", timeout=3)
        self.assertEqual("unauthorized", caught.exception.code)

    def test_confirm_waits_for_the_callback(self):
        server = FakeLauncher()
        server.start()
        app = open_pmcl(f"127.0.0.1:{server.port}", "secret", timeout=3)
        try:
            self.assertTrue(app.ui.confirm("标题", "继续吗", timeout=3))
        finally:
            app.close()


if __name__ == "__main__":
    unittest.main()
