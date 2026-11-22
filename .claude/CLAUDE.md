always measure rather than guessing

always build robust solutions

always consider if something should be centralised in the LSP

don't suffix commit's with your signature

code the platform can call while painting (file type overriders, icons, tab titles, renderers) must never take a lock: guard it with `withLocksForbidden` in a test, with two projects open where it looks a project up

MCP Steroid scripts run on a pooled thread holding no lock: wrap model reads in `readAction {}`, and read an error with `Script__jonnyzzz` frames as the script's mistake, not the plugin's

DevKit's inspections, threading included, and the Kotlin/JVM probable-bug, dead-code, bundle and i18n ones run in CI through Qodana (qodana.yaml, README's lint section): a new Critical or High finding fails the build, and the baseline is the backlog, not a place to put new findings

a change to another repo (`~/projects/basedpython`, `~/projects/basedpythondebugger`, or any other) follows that repo's guidelines, not these: before the first edit read all of its `.claude/CLAUDE.md`, `CLAUDE.md` and `AGENTS.md` and list its skills, in its main checkout, since `.claude/` can be gitignored and so missing from a worktree, and neither its instructions nor its hooks load in a session started here. its definition of finished is the one that counts, `/verify` (`.claude/skills/verify/SKILL.md`) in `basedpython` with its independent review, commit and `mark-verified.sh`; until then say it is unverified, and never that it is fixed. a subagent or session given work there is told the same
