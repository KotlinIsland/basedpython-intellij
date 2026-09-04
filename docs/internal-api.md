# Internal API — what was given up, and what we want back

> **You are on `internal-api-logpoints`.** This branch deliberately puts the internal API back, to
> see what log points look like when nothing is missing: the gutter gap, and the yellow dot in the
> gap rather than a line below it. It is a build to *try*, never one to publish — uploading it to
> Marketplace risks the permanent ban JetBrains warns about below, and `verifyPlugin`'s
> `INTERNAL_API_USAGES` failure level is switched off here, so nothing will stop you. `main` is the
> shippable one, and everything under "Wanted back" describes what `main` is missing.
>
> What this branch adds back, and nothing else:
>
> | | |
> | --- | --- |
> | `XLineBreakpointType.supportsInterLinePlacement()` | one line on `ByLineBreakpointType`; the yellow dot moves into the gap, in both IDEs, and IntelliJ IDEA's own *Add Logpoint* can make a `.by` log point |
> | `XLineBreakpointAdditionalInfo.Builder.setVerticalPlacement` + `XLineBreakpointVerticalPlacement` | every log point is created `INTER_LINE`, which is where it runs |
> | `XLineBreakpoint.getPlacement` | a breakpoint the platform put in the gap is recognised as a log point |
> | the placement-filtered `findBreakpointsAtLine` | `ByLogpoints.breakpointsAt` — the public three-argument overload silently means `ON_LINE`, so undo could not find a log point to take back |
> | `InterLineBreakpointConfigurationProvider` and friends | `ByInterLineLogpointProvider` and `ByInterLineShift`, restored as they were |


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

Both of the open questions this entry used to carry have now been **answered in a running PyCharm**,
and both answers are yes:

1. **A block inlay does get a gutter icon.** Not through `calcGutterIconRenderer` directly — the
   renderer is the platform's `EditorEmbeddedComponentManager.MyRenderer`, which is internal — but
   through the `rendererFactory` argument of `EditorEmbeddedComponentManager.Properties`, which is
   public and is exactly the hook `MyRenderer.calcGutterIconRenderer` delegates to. The icon paints
   in the **inlay's own row**, beside the box rather than beside the code. It needs one
   `EditorGutterComponentEx.revalidateMarkup()` after the inlay is added, or the gutter keeps the
   icon list it already had.
2. **A block inlay can be placed above its line.** `showAbove` on the same `Properties`, which is
   how the `Log:` box has always been positioned.

Hover tracking needs no gutter component either: `EditorEx.addEditorMouseMotionListener` reports
`EditorMouseEvent.getArea() == LINE_NUMBERS_AREA` with the mouse position, which is enough to work
out which line boundary the pointer is nearest. And the gap need not be an inlay per line — one
inlay, added at the boundary under the pointer and removed when it leaves, is the same gesture for a
fraction of the cost, with `Animator` growing its height if the shift should slide rather than jump.

So the discovery gesture is buildable. **What it still could not do is put the log point's icon
where the gap is** — see the next entry: the icon this plugin would add to the inlay is a *second*
icon, and the platform's own would stay on the line below it.

**Better outcome:** the platform makes the inter-line breakpoint API public, and
`ByInterLineLogpointProvider` comes back roughly as it was. Worth asking for in IJPL before building
the replacement.

### The log point's gutter icon sits on the wrong line

**What it should be.** A log point's yellow dot in the gutter, level with its `Log:` box, in the gap
between the two lines — because that is where the log point *is*: it runs after the line above and
before the line below.

**What it is.** One line lower, level with the line the log point is anchored to, in **both** IDEs.

**Why.** The platform draws a line breakpoint's icon at its line unless the breakpoint's
`XLineBreakpointVerticalPlacement` is `INTER_LINE`, and the switch that lets a breakpoint have that
placement at all is `XLineBreakpointType.supportsInterLinePlacement()` — `@ApiStatus.Internal`, one
line, `override fun supportsInterLinePlacement() = true`. Both ends are shut:

- **In PyCharm**, where this plugin draws the box, `Properties.rendererFactory` can put an icon in
  the gap (verified, above) but nothing public can take away the one the platform draws on the line,
  and two icons is worse than one in the wrong place. `XBreakpointUIUtil.calculateIcon` picks
  `type.getSuspendNoneIcon()` for any breakpoint that does not suspend, and that is a property of the
  *type*, not of the breakpoint — blanking it would make every suspend-none `.by` breakpoint
  invisible, including one that is not a log point.
- **In IntelliJ IDEA**, where the IDE draws the box, `XLogpointPromptObserver.ensureLogpointPlacement`
  moves a log point into the gap only `if (breakpoint.type.supportsInterLinePlacement())`. Ours says
  no, so IDEA shows the box (`shouldShowPrompt` asks only `canBeLogpoint`, which `.by` log points
  satisfy) and leaves the icon on the line. The same switch also gates
  `XBreakpointUIUtil.supportsPlacement`, which is what filters breakpoint types out of an
  `INTER_LINE` toggle — so IDEA's own *Add Logpoint* (`Ctrl+Alt+F8`, `ToggleLogpointAction`) cannot
  make a `.by` log point at all. Every `.by` log point in IDEA arrives by one of this plugin's routes
  or by *Add Logging Breakpoint…*.

**What it costs.** The icon reads as belonging to the statement below the box rather than to the box.
And in IDEA, one of the two ways to add a log point is missing.

**How to get it back.** There is no public equivalent to build; this one is an IJPL issue asking for
`supportsInterLinePlacement` (and the placement enum with it) to be made public. Everything else
about `.by` log points already works without it.

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

