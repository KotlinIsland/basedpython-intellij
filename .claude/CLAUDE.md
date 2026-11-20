always measure rather than guessing

always build robust solutions

always consider if something should be centralised in the LSP

don't suffix commit's with your signature

code the platform can call while painting (file type overriders, icons, tab titles, renderers) must never take a lock: guard it with `withLocksForbidden` in a test, with two projects open where it looks a project up

MCP Steroid scripts run on a pooled thread holding no lock: wrap model reads in `readAction {}`, and read an error with `Script__jonnyzzz` frames as the script's mistake, not the plugin's

DevKit's inspections, threading included, run in CI through Qodana (qodana.yaml, README's lint section): a new Critical or High finding fails the build, and the baseline is the backlog, not a place to put new findings
