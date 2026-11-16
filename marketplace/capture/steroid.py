"""Minimal MCP Steroid client: steroid.py <tool> [json-args | @file.kts (for steroid_execute_code)]

Reads the newest ~/.mcp-steroid/markers/*.mcp-steroid for the URL and bearer header, keeps the
MCP session id in a file beside this script so repeated calls reuse one session.
"""
import glob, json, os, sys, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
SESSION = os.path.join(HERE, ".steroid-session")


def marker():
    files = sorted(glob.glob(os.path.expanduser("~/.mcp-steroid/markers/*.mcp-steroid")), key=os.path.getmtime)
    if not files:
        sys.exit("no marker")
    raw = open(files[-1]).read()
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        sys.exit("marker is not JSON:\n" + raw[:300])
    srv = data.get("mcpSteroidServer", data)
    url = srv.get("mcpUrl")
    headers = {}
    for k, v in (srv.get("headers") or {}).items():
        headers[k] = v
    for key in ("Authorization", "authorization", "authHeader"):
        if key in srv:
            headers["Authorization"] = srv[key]
    if "token" in srv and "Authorization" not in headers:
        headers["Authorization"] = "Bearer " + srv["token"]
    return url, headers


def post(url, headers, body, sid=None):
    h = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream", **headers}
    if sid:
        h["Mcp-Session-Id"] = sid
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers=h, method="POST")
    with urllib.request.urlopen(req, timeout=600) as r:
        new_sid = r.headers.get("Mcp-Session-Id")
        text = r.read().decode()
    if text.startswith("event:") or "\ndata:" in text or text.startswith("data:"):
        datas = [l[5:].strip() for l in text.splitlines() if l.startswith("data:")]
        text = datas[-1] if datas else ""
    return new_sid, (json.loads(text) if text.strip() else None)


def session(url, headers):
    if os.path.exists(SESSION):
        sid, u = open(SESSION).read().split("\n", 1)
        if u == url:
            return sid
    sid, _ = post(url, headers, {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
        "protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "cli", "version": "0"}}})
    post(url, headers, {"jsonrpc": "2.0", "method": "notifications/initialized"}, sid)
    open(SESSION, "w").write(f"{sid}\n{url}")
    return sid


def main():
    url, headers = marker()
    sid = session(url, headers)
    tool = sys.argv[1]
    if tool == "list":
        _, res = post(url, headers, {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}, sid)
        for t in res["result"]["tools"]:
            print(t["name"], "-", (t.get("description") or "")[:150].replace("\n", " "))
        return
    arg = sys.argv[2] if len(sys.argv) > 2 else "{}"
    if arg.startswith("@"):
        code = open(arg[1:]).read()
        args = {"project_name": sys.argv[3], "code": code, "task_id": "shots", "reason": "marketplace screenshots"}
    else:
        args = json.loads(arg)
    _, res = post(url, headers, {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": tool, "arguments": args}}, sid)
    if "error" in res:
        print(json.dumps(res["error"], indent=1)); sys.exit(1)
    for c in res["result"].get("content", []):
        print(c.get("text", c))
    if res["result"].get("isError"):
        sys.exit(1)


main()
