#!/usr/bin/env python3
"""Mock OpenAI-compatible provider for NOVA part 1B. Behaviour is chosen by the URL path.
It also VALIDATES the request shape that FreeProviders.buildBody produces (HTTP 400 if wrong), and records what it received."""
import json, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SEEN = []  # (path, authorization header or None, parsed body or None)


def shape_error(b):
    if not isinstance(b, dict): return "body not an object"
    if not isinstance(b.get("model"), str) or not b["model"]: return "model"
    m = b.get("messages")
    if not (isinstance(m, list) and len(m) == 2): return "messages"
    if m[0].get("role") != "system" or m[1].get("role") != "user": return "roles"
    if not isinstance(m[0].get("content"), str) or not isinstance(m[1].get("content"), str): return "content"
    if not isinstance(b.get("max_tokens"), int): return "max_tokens"
    if not isinstance(b.get("temperature"), (int, float)): return "temperature"
    return None


class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(n).decode("utf-8")
        try: body = json.loads(raw)
        except ValueError: body = None
        SEEN.append((self.path, self.headers.get("Authorization"), body))
        err = shape_error(body)
        if err: return self.send(400, {"error": "bad shape: " + err})
        p = self.path
        if p == "/ok": return self.send(200, {"choices": [{"message": {"role": "assistant", "content": "**Aasman** blue hota hai.\n<think>x</think>"}}]})
        if p == "/rate": return self.send(429, {"error": {"message": "rate limit"}})
        if p == "/badkey": return self.send(401, {"error": {"message": "invalid api key"}})
        if p == "/down": return self.send(503, {"error": "overloaded"})
        if p == "/nomodel": return self.send(404, {"error": "no such model"})
        if p == "/garbage": return self.send_raw(200, "<html>proxy page</html>")
        if p == "/errbody": return self.send(200, {"error": {"message": "provider error inside 200"}})
        if p == "/slow":
            time.sleep(2.0)
            return self.send(200, {"choices": [{"message": {"content": "too late"}}]})
        return self.send(404, {"error": "unknown path"})

    def send(self, code, obj): self.send_raw(code, json.dumps(obj))

    def send_raw(self, code, text):
        data = text.encode("utf-8")
        try:
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError): pass


def start():
    srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, srv.server_address[1]
