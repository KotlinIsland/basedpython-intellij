# Internal API — what was given up, and what we want back

JetBrains Marketplace declined this plugin's first submission (0.0.1, uploaded 1 Sep 2026) for using
`@ApiStatus.Internal` platform API:

> Your plugin uses the Internal API, which is private and must not be used outside the IntelliJ
> Platform itself. […] After that, please upload your plugin to the Marketplace again.

This file is the record of what that cost, so a feature that was deliberately dropped is not later
mistaken for one nobody thought of. **Everything under "Wanted back" is a feature we want and would
restore the moment the platform makes it possible.**

Two things worth knowing before reading further:

- **The count is zero, and the build keeps it there.** `verifyPlugin` lists
  `INTERNAL_API_USAGES` in its `failureLevel`, so a single internal import is a red build here
  rather than a moderator's email weeks later. The Plugin Verifier does *not* do this on its own —
  every report, ours and Marketplace's, says *Compatible* while listing the usages, because internal
  usage is informational to the tool. That line in `build.gradle.kts` is the whole of the
  enforcement. When something has no public equivalent, the answer is an IJPL issue and an entry
  below, not relaxing it.
- **Working around the check is not an option.** JetBrains: *"Any attempt to circumvent our API
  usage policies is a risk of permanent ban for a plugin in the Marketplace."* Reflection, shaded
  packages, or anything that hides a usage from the verifier is off the table. The only routes are a
  public replacement, copying non-internal code out of intellij-community under its licence,
  dropping the feature, or an [IJPL](https://youtrack.jetbrains.com/issues/IJPL) issue asking for
  the API to be made public.

## Wanted back

### Hover-to-reveal for log points — the gutter gap

**What it was.** Hovering between two line numbers in a `.by` file opened a gap under the mouse with
an *Add Log* icon in it; clicking put a log point there. `ByInterLineLogpointProvider` and
`ByInterLineShift` (deleted; see history) registered into the platform's own inter-line breakpoint
machinery, which did the painting, hit-testing and gap reservation.

**Why it went.** The whole mechanism is internal — `InterLineBreakpointConfigurationProvider`,
`InterLineBreakpointConfiguration`, `InterLineBreakpointProperties`, `InterLineShiftAnimator`,
`XLineBreakpointVerticalPlacement`, `XLineBreakpoint.getPlacement`,
`XLineBreakpointType.supportsInterLinePlacement`, and the placement-filtered
`XBreakpointManager.findBreakpointsAtLine` overload. 19 usages, and no public equivalent for any of
it.

**What replaced it.** A log point is now added by <kbd>Ctrl+Alt+F8</kbd>, the gutter menu, or the
`print` quick fix, and marked by this plugin's own `ByBreakpointProperties.isLogpoint` instead of by
the platform's vertical placement. Everything *after* creation is unchanged: the `Log:` field was
already an ordinary block inlay through `EditorEmbeddedComponentManager`, which is not internal, so
editing, undo, persistence and debugging all behave exactly as before.

**What was actually lost.** Only the discovery gesture. There is no way to find log points by
hovering any more — you have to know the shortcut or the menu item. That is a real loss for a
feature whose whole point is being easy to reach.

**How to get it back, if it stays internal.** Rebuild the gap from public API rather than
registering into the platform's. Everything needed exists and is public:

| Need | Public API |
| --- | --- |
| The gap itself | `InlayModel.addBlockElement` + `EditorCustomElementRenderer.calcHeightInPixels` |
| Icon in the gutter beside it | `EditorCustomElementRenderer.calcGutterIconRenderer` |
| Click on that icon | `GutterIconRenderer.getClickAction` / `getPopupMenuActions` |
| Hover tracking | `EditorMouseMotionListener` + `EditorGutterComponentEx` |

The cost is that a block inlay has to *exist* to occupy space, where the platform reserves a hit
area between lines with nothing there. So hover-to-reveal means a zero-height block inlay per line,
grown on hover, plus reimplementing the shift animation — i.e. redoing `EditorGutterComponentImpl`'s
job inside the plugin. That is why it was not done at the time, not because it is impossible.

Two things to check in a running IDE before starting (neither has been verified):

1. whether `calcGutterIconRenderer` is honoured for **block** inlays specifically — it is declared
   on the renderer interface shared with inline inlays;
2. whether a block inlay can be placed *above* its line so the gap lands where the old one did.

`./gradlew runPyCharm` is the place to find out; this path is dead in IntelliJ IDEA, which has its
own log points (see `ByLogpoints.pluginProvidesLogpointUi`).

**Better outcome:** the platform makes the inter-line breakpoint API public, and
`ByInterLineLogpointProvider` comes back roughly as it was. Worth asking for in IJPL before building
the replacement.

## Resolved

Nothing internal ships any more. What each one became, for when the platform changes underneath it:

| Was | Now |
| --- | --- |
| `LspServerManagerListener`, `LspClientManagerListener` (62) | `LspServerListener` via `LspClientDescriptor.lspServerListener`, republished on a project topic — `lsp/ByLspLifecycle.kt` |
| The inter-line breakpoint API (19) | Gone with the gutter gap; a log point is marked by `ByBreakpointProperties.isLogpoint` — see above |
| `SourceFileChangesCollectorImpl`, `SourceFileChangeFilter` (6) | Our own `SourceFileChangesCollector` — `debug/hotswap/ByChangesCollector.kt`. The public interface is three methods, so this deleted more reflection than it added code: the impl's constructor changed between 262 and 263 and had to be looked up at runtime, out of a call that runs while the debug session starts |
| `HotSwapStatusNotificationManager.trackNotification` (5) | The last "not reloaded" balloon is held and expired at the top of `performHotSwap` |
| `LspClientManagerListener.fileOpened`, `DocRenderManager` (17) | `ByLspLifecycleListener.serverInitialized` plus a bounded re-check on file open, and `FileContentUtilCore.reparseFiles` in place of `resetEditorToDefaultState`. **The one swap that is not like-for-like** — see below |
| `DapInitializationException.userVisible` (2) | `ByDebugAdapterDescriptor.hasReportedFailure`, which answers the same question more directly: has the user already been told |
| `ShadowJava2DBorder` (2) | `ByLogpointBoxBorder`, a rounded rect and a few translucent passes |
| `PluginManagerCore.getPlugin` (1) | The plugin's own code source — `<plugin>/lib/<jar>` grandparent. Every descriptor lookup in the platform is internal |
| `AdditionalFenceLanguageSuggester` (2) | **Dropped.** See below |

### Rendered docstrings — needs live verification

`ByRenderedDocsRefresher` lost its exact signal. `LspClientManagerListener.fileOpened` fired the
moment the client told the server about a file; the public `LspServerListener` has no per-file
callback, so the one event is replaced by two occasions — a server becoming ready, and a bounded
re-check 700ms after a file opens. That second one is a delayed look where there used to be an
event.

Two things about it have **not been confirmed in a running IDE**, and should be before anyone
trusts them: that `FileContentUtilCore.reparseFiles` really does re-run `DocRenderPassFactory`
(it bumps the modification count the pass's skip is keyed on, which is the mechanism, but that is
reasoning rather than observation), and that 700ms is actually long enough for the client's
`didOpen` on a cold project. `./gradlew runPyCharm`, open a `.by` file with docstrings, and see
whether they render without touching the keyboard.

An IJPL issue asking for a per-file callback on `LspServerListener` would remove the guesswork
entirely, and is the right thing to file.

### Markdown code fences — dropped

```` ```by ````, ```` ```byi ````, ```` ```bython ```` and ```` ```based-python ```` fences no
longer resolve to basedpython in markdown. `AdditionalFenceLanguageSuggester` was the only way to
register an alias — `CodeFenceLanguageAliases` is read-only and has no registration method — and it
is internal. ```` ```basedpython ```` still works, because the markdown plugin falls back to the ID
of every registered language, which is why this was only ever worth two lines.

Wanted back if the alias table is ever made writable. The optional `org.intellij.plugins.markdown`
dependency went with it, this having been its only use.

