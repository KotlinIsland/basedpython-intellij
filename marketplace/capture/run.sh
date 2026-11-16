#!/bin/bash
# run.sh <script.kts> [KEY=VALUE ...]: replaces each KEY in the script with VALUE and runs it in the
# sandbox's showcase project through MCP Steroid.
set -euo pipefail
D=$(cd "$(dirname "$0")" && pwd)
f=$1; shift
tmp=$(mktemp "$D/.run.XXXXXX")
trap 'rm -f "$tmp"' EXIT
cp "$f" "$tmp"
for kv in "$@"; do
    python3 -I -c 'import sys; p, k, v = sys.argv[1:]; t = open(p).read(); open(p, "w").write(t.replace(k, v))' "$tmp" "${kv%%=*}" "${kv#*=}"
done
project=$(python3 -I "$D/steroid.py" steroid_list_projects | python3 -I -c 'import json, sys; print(next(p["project_name"] for p in json.load(sys.stdin)["projects"] if p["name"] == "showcase"))')
python3 -I "$D/steroid.py" steroid_execute_code "@$tmp" "$project" 2>&1 | grep -v '^\s*at \|^execution_id\|^HINT: TIP' || true
