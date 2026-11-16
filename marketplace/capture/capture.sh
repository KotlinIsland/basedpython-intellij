#!/bin/bash
# Recaptures ../captures/*.png from a sandbox IDE with the showcase open and MCP Steroid in it:
#
#   ./gradlew runIde -PideAgent --args="$PWD/marketplace/showcase"
#   marketplace/capture/capture.sh [scene ...]     # all scenes when none are named
#
# Each scene puts the IDE into a known state, then paints the frame and its popups into a 2x PNG
# from inside the JVM (kts/capture.kts), so nothing needs screen recording or focus. Line and column
# numbers below point into the showcase's sources: move them when those files change.
set -euo pipefail
D=$(cd "$(dirname "$0")" && pwd)
M=$(cd "$D/.." && pwd)
SHOWCASE=$M/showcase
OUT=$M/captures
run() { "$D/run.sh" "$D/kts/$1" "${@:2}"; }
shot() { run capture.kts "OUT=$OUT/$1.png"; }

setup() {
    run setup.kts
    run cleanup.kts >/dev/null
}

transpiled() {
    run scene-open.kts FILE=src/shop/receipts.by LINE=6 COL=40 TOOLWINDOW=
    run action.kts ACTION=dev.basedpython.pycharm.transpile.ShowTranspiledDiff
    sleep 5
    # the generated side opens with the runtime prelude; put receipt_email at the top of both sides
    run diff-scroll.kts SIDE=py LINE=18 CARET=23
    sleep 2
    shot transpiled
}

pydantic() {
    run cleanup.kts >/dev/null
    # column 18 is inside the warning's range, which starts at the call's parenthesis
    run scene-open.kts FILE=src/shop/models.by LINE=18 COL=18 TOOLWINDOW=
    sleep 6
    run action.kts ACTION=ShowErrorDescription
    sleep 3
    shot pydantic
}

completion() {
    local backup
    backup=$(mktemp)
    cp "$SHOWCASE/tests/test_orders.by" "$backup"
    run scene-open.kts FILE=tests/test_orders.by LINE=1 COL=1 TOOLWINDOW=
    # completion straight after typing, as a person would, before highlighting marks the line
    run complete.kts 'TEXT=def test_voucher_describe():\n    Payment.|\n\n\n' AT=21 DELAY=150
    sleep 3
    shot completion
    run revert.kts
    # the IDE may have saved the typed text already
    cp "$backup" "$SHOWCASE/tests/test_orders.by"
    rm -f "$backup"
}

debugger() {
    run cleanup.kts >/dev/null
    run scene-open.kts FILE=tests/test_orders.by LINE=1 COL=1 TOOLWINDOW=
    run debug.kts BP_FILE=src/shop/orders.by BP_LINE=29
    for _ in $(seq 1 60); do
        run debug-state.kts | grep -q 'suspended=true' && break
        sleep 2
    done
    run debug-layout.kts RATIO=0.5 TOP=23
    run expand-var.kts NAME=self
    run expand-var.kts NAME=customer
    run balloons.kts
    sleep 1
    shot debugger
    # resuming to the end, rather than stopping, keeps bpd from leaving an error balloon behind
    run finish-debug.kts
}

tests() {
    run cleanup.kts >/dev/null
    run scene-open.kts FILE=tests/test_orders.by LINE=1 COL=1 TOOLWINDOW=
    run run-context.kts
    sleep 20
    run tests-tree.kts SELECT=SPRING
    sleep 2
    run tests-tree.kts SELECT=SPRING
    run tests-layout.kts RATIO=0.42 PROP=0.36f TOP=22
    sleep 1
    shot tests
}

scenes=("$@")
[ ${#scenes[@]} -eq 0 ] && scenes=(transpiled pydantic completion debugger tests)
setup
for s in "${scenes[@]}"; do
    echo "== $s"
    "$s"
done
