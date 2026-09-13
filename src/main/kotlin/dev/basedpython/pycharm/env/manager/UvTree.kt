package dev.basedpython.pycharm.env.manager

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Path

/**
 * Turning `uv tree --format json` into the grouped dependency tree the view shows.
 *
 * ### The shape uv gives
 *
 * Not a tree — a graph plus a list of entry points, which is the honest representation and leaves
 * the flattening to whoever displays it:
 *
 * - `resolution` maps an opaque package id to `{name, version, kind, dependencies: [{id}]}`.
 * - `kind` is the string `"package"` for an ordinary one, the string `"workspace"` for the container,
 *   or an object — `{"group": "dev"}`, `{"extra": "cli"}` — for the *synthetic* node that stands for
 *   "this project's dev group". That synthetic node's dependencies are exactly the requirements
 *   declared under that group, which is what makes the grouping possible at all.
 * - `roots` names the entry points: one per group and extra, plus the project itself.
 * - `members` names the workspace's modules — `{name, path, id}` each — and each member's own
 *   `resolution` entry carries `dependency_groups` and `optional_dependencies` back-links naming
 *   the synthetic nodes that belong to it.
 *
 * So a group is a root whose kind carries a name, and the main dependency list is the root whose
 * kind is a plain package. Everything under them comes from following ids through `resolution`.
 *
 * Each entry also carries a `source` — `{"registry": …}`, `{"editable": path}`, `{"directory": path}`,
 * `{"git": url}` and so on — which is how a path dependency is told apart from a download. An
 * editable path dependency and a workspace member carry the *same* shape of source, so which one a
 * package is comes from `members`, never from the source alone.
 *
 * ### Projects
 *
 * The lists are handed back under the projects that declare them — the root, each member, and every
 * path dependency, which uv resolves into the same graph but lists as neither a root nor a member.
 * A path dependency's own requirements are its entry's `dependencies`; its groups are not resolved
 * into the workspace's lock at all, so its main list is the only one there is to show.
 *
 * ### Roots are per module, not per project
 *
 * A workspace has a manifest per module and uv emits a root for every list of every one of them, so
 * a two-module workspace produces *two* roots of kind `package` and can produce two `dev` groups.
 * Reading only `kind` therefore collapses them: two headings both called `dependencies`, and — far
 * worse — two groups that compare equal, so a removal from one is sent as a removal from the other.
 * `members` is what tells them apart, and [EnvDependencyList.module] is where the answer is kept.
 *
 * ### The schema says `preview`
 *
 * uv labels this schema as unstable, and this parser is written to survive it changing: every field
 * is read defensively and anything unrecognised yields an empty result rather than an exception. An
 * empty result is a supported outcome — [EnvPanel] falls back to the flat installed list — so a uv
 * that reshapes this JSON costs the grouping, not the window.
 */
object UvTree {

    /**
     * How deep the walk will go before giving up.
     *
     * The dedupe below already makes infinite recursion impossible — a package is expanded at most
     * once per group, so a cycle terminates the second time round. This is the guard for the case
     * that reasoning does not cover: a `resolution` map malformed in a way that makes the walk
     * generate fresh work forever. Far deeper than any real dependency chain.
     */
    private const val MAX_DEPTH = 100

    /** One entry in `resolution`, reduced to what the view needs. */
    private data class Entry(
        val name: String,
        val version: String,
        val kind: Kind,
        val dependencyIds: List<String>,
        /** As the entry states it. A member's reads as [EnvSource.Local]; see [sourceOf]. */
        val source: EnvSource,
    )

    private sealed interface Kind {
        data object Package : Kind
        data object Workspace : Kind
        data class Group(val name: String) : Kind
        data class Extra(val name: String) : Kind
    }

    /**
     * Parses the JSON, or returns an empty list.
     *
     * Empty is the honest answer for every failure here — malformed JSON, a schema that moved, a uv
     * that printed a warning instead of a graph — because a partial dependency tree is worse than
     * none: it would silently claim a project has fewer dependencies than it does.
     */
    fun parse(stdout: String): EnvDependencyGraph = try {
        parseOrThrow(stdout)
    } catch (_: Exception) {
        EnvDependencyGraph.EMPTY
    }

    /**
     * The main list and `dev` are kept even when empty; every other group is dropped when it is.
     *
     * See [ALWAYS_SHOWN]. An empty `docs` heading is noise; an empty `dependencies` or `dev` is a
     * place the project has and you can add to.
     */
    private fun parseOrThrow(stdout: String): EnvDependencyGraph {
        val root = JsonParser.parseString(stdout.trim().ifEmpty { "{}" })
        if (!root.isJsonObject) return EnvDependencyGraph.EMPTY

        val resolution = root.asJsonObject.getAsJsonObject("resolution") ?: return EnvDependencyGraph.EMPTY
        val entries = LinkedHashMap<String, Entry>()
        for ((id, value) in resolution.entrySet()) {
            entry(value)?.let { entries[id] = it }
        }

        val rootIds = root.asJsonObject.getAsJsonArray("roots")
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.string("id") }
            ?.toList()
            .orEmpty()

        val modules = modules(root.asJsonObject, resolution)
        val groups = rootIds.mapNotNull { id -> group(id, entries, modules) }
            .filter { it.target in ALWAYS_SHOWN || it.roots.isNotEmpty() }
            .sortedWith(order(modules))
        return EnvDependencyGraph(projects(groups, entries, modules))
    }

    /**
     * The root first, then the members, then the path dependencies, each holding its own lists.
     *
     * A list whose module is null belongs to the root: that is a single-package project, whose lists
     * carry no module at all, or a virtual root, which has no name to be called by. Either way there
     * is exactly one project those lists can be from.
     */
    private fun projects(
        groups: List<EnvDependencyGroup>,
        entries: Map<String, Entry>,
        modules: Modules,
    ): List<EnvProject> {
        val byOwner = groups.groupBy { it.module ?: modules.rootModule }
        val rootMember = modules.members.firstOrNull { it.name == modules.rootModule }
        val projects = mutableListOf<EnvProject>()

        if (rootMember != null) {
            projects += memberProject(rootMember, EnvProjectRole.ROOT, entries, byOwner)
        } else {
            byOwner[null]?.let {
                projects += EnvProject(null, null, path(modules.workspaceRoot), EnvProjectRole.ROOT, it)
            }
        }
        modules.members
            .filter { it != rootMember }
            .sortedBy { it.name.lowercase() }
            .mapTo(projects) { memberProject(it, EnvProjectRole.MEMBER, entries, byOwner) }
        projects += localProjects(entries, modules)
        return projects
    }

    private fun memberProject(
        member: Member,
        role: EnvProjectRole,
        entries: Map<String, Entry>,
        byOwner: Map<String?, List<EnvDependencyGroup>>,
    ): EnvProject = EnvProject(
        name = member.name,
        version = entries[member.id]?.version?.takeIf { it.isNotEmpty() },
        path = path(member.path),
        role = role,
        groups = byOwner[member.name].orEmpty(),
    )

    /**
     * Every package installed from a directory that is not a member: the path dependencies.
     *
     * One per directory, with the extras it is installed with as lists of their own. Required as
     * `copy[x]`, a path dependency is two entries with the same directory — `copy`, a plain package,
     * and `copy[x]`, whose `kind` names the extra (uv 0.12.13) — and they are one project: its main
     * list from the first, the `x` list from the second.
     */
    private fun localProjects(entries: Map<String, Entry>, modules: Modules): List<EnvProject> {
        val byDirectory = LinkedHashMap<Path, MutableList<Pair<String, Entry>>>()
        for ((id, entry) in entries) {
            val source = entry.source as? EnvSource.Local ?: continue
            if (id in modules.memberIds) continue
            byDirectory.getOrPut(source.path) { mutableListOf() } += id to entry
        }
        return byDirectory.mapNotNull { (path, found) ->
            val (_, main) = found.firstOrNull { (_, entry) -> entry.kind == Kind.Package } ?: return@mapNotNull null
            val source = main.source as EnvSource.Local
            fun list(target: EnvDependencyTarget, ids: List<String>): EnvDependencyGroup {
                val expanded = HashSet<String>()
                val roots = ids.mapNotNull { walk(it, entries, modules, expanded, depth = 0) }
                return EnvDependencyGroup(EnvDependencyList(target, main.name), roots, writable = false)
            }
            val extras = found
                .mapNotNull { (_, entry) ->
                    val extra = (entry.kind as? Kind.Extra)?.name ?: return@mapNotNull null
                    val base = baseOf(entry, entries)
                    list(EnvDependencyTarget.Extra(extra), entry.dependencyIds.filter { it != base })
                }
                .sortedBy { it.target.label.lowercase() }
            EnvProject(
                name = main.name,
                version = main.version.takeIf { it.isNotEmpty() },
                path = path,
                role = EnvProjectRole.LOCAL,
                groups = listOf(list(EnvDependencyTarget.Main, main.dependencyIds)) + extras,
                editable = source.editable,
            )
        }.sortedBy { it.name.orEmpty().lowercase() }
    }

    /** [raw] as a normalised path, or null when there is none or it is not one. */
    private fun path(raw: String?): Path? =
        raw?.let { runCatching { Path.of(it).normalize() }.getOrNull() }

    /** One `members` entry. */
    private data class Member(val name: String, val id: String, val path: String?)

    /**
     * Which module owns each root, and which package each synthetic node belongs to.
     *
     * Both answers come from the same back-links, so they are read in one pass. Everything here
     * degrades to the pre-workspace behaviour when `members` is absent or reshaped: no module on any
     * group, and the self-edge found by name instead — see [selfEdge].
     */
    private class Modules(
        /** Member name by root id. Empty for a project that is not a workspace. */
        val ownerOf: Map<String, String>,
        /**
         * The member a synthetic node belongs to, by that node's id.
         *
         * Filled for groups as well as extras, since both are read from the same two arrays. Only
         * an extra has an edge back to it to drop — see [selfEdge].
         */
        val baseOf: Map<String, String>,
        /** The member that *is* the workspace root, whose lists sort first. Null when unknown. */
        val rootModule: String?,
        /** Every member, the root included even when [ownerOf] leaves it out. */
        val members: List<Member>,
        val workspaceRoot: String?,
    ) {
        /** Every member's id — what makes an editable source a member rather than a path dependency. */
        val memberIds: Set<String> = members.mapTo(HashSet()) { it.id }
    }

    private fun modules(root: JsonObject, resolution: JsonObject): Modules {
        val members = root.getAsJsonArray("members")
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            ?.mapNotNull { obj ->
                val name = obj.string("name") ?: return@mapNotNull null
                val id = obj.string("id") ?: return@mapNotNull null
                Member(name, id, obj.string("path"))
            }
            .orEmpty()

        val ownerOf = LinkedHashMap<String, String>()
        val baseOf = LinkedHashMap<String, String>()
        for (member in members) {
            ownerOf[member.id] = member.name
            val entry = resolution.get(member.id)?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            for (key in OWNED_LISTS) {
                val owned = entry.getAsJsonArray(key) ?: continue
                for (element in owned) {
                    val id = element.takeIf { it.isJsonObject }?.asJsonObject?.string("id") ?: continue
                    ownerOf[id] = member.name
                    baseOf[id] = member.id
                }
            }
        }

        val workspaceRoot = root.string("workspace_root")
        val rootModule = members.firstOrNull { samePath(it.path, workspaceRoot) }?.name
        // A project that is not a workspace lists *itself* as the single member. Naming its module
        // would put a qualifier on every heading of a project with nothing to qualify against — and
        // would start passing `--package` to commands that have always managed without it.
        //
        // The test is that the one member *is* the root, not that there is one member. A manifest
        // holding only `[tool.uv.workspace]` — a virtual root, with no `[project]` of its own — and
        // one member also has a single entry, and that entry is the member, not the root. Counting
        // alone therefore called that project unmanaged and dropped `--package` from every command
        // it issued: `uv remove idna` answers "could not be found in `project.dependencies`" and
        // `uv add httpx` answers "Project is missing a `[project]` table". Verified on uv 0.12.10.
        val singlePackageProject = members.size == 1 && rootModule != null
        return Modules(
            ownerOf = if (singlePackageProject) emptyMap() else ownerOf,
            baseOf = baseOf,
            rootModule = rootModule,
            members = members,
            workspaceRoot = workspaceRoot,
        )
    }

    /** The member back-link arrays, both of which name synthetic root nodes. */
    private val OWNED_LISTS = listOf("dependency_groups", "optional_dependencies")

    /** uv writes a member's path with a trailing separator and the workspace root's without one. */
    private fun samePath(one: String?, other: String?): Boolean =
        one != null && other != null && one.trimEnd('/', '\\') == other.trimEnd('/', '\\')

    /**
     * Groups worth a heading even with nothing in them.
     *
     * The main list because it is where dependencies go by default, and `dev` because it is where
     * development ones go — both are places a project *has* rather than lists it happens to have
     * filled. Removing the last entry from either should leave an empty heading you can add to, not
     * make the group disappear and have to be conjured back by typing its name into a dialog.
     *
     * Every other group is dropped when empty: a `docs` heading with nothing under it is noise, and
     * unlike these two it is not somewhere a person expects to find.
     */
    private val ALWAYS_SHOWN = setOf(EnvDependencyTarget.Main, EnvDependencyTarget.DEV)

    /** One root's group, or null when the root is not something the view shows. */
    private fun group(
        rootId: String,
        entries: Map<String, Entry>,
        modules: Modules,
    ): EnvDependencyGroup? {
        val entry = entries[rootId] ?: return null
        val target = when (val kind = entry.kind) {
            is Kind.Group -> EnvDependencyTarget.Group(kind.name)
            is Kind.Extra -> EnvDependencyTarget.Extra(kind.name)
            Kind.Package -> EnvDependencyTarget.Main
            // The workspace container is a bookkeeping node with no requirements of its own.
            Kind.Workspace -> return null
        }

        // Dedupe is per group rather than across the whole tree, which is where this deliberately
        // differs from uv's own text output. Globally deduping means a package that happens to
        // appear under `dev` first is shown as an unexpanded leaf under `dependencies` — the group
        // the user actually cares about — for no better reason than iteration order. Per group,
        // every group reads as a complete tree of its own, and the duplication is bounded by the
        // number of groups, which is a handful.
        val expanded = HashSet<String>()
        val self = selfEdge(rootId, entry, entries, modules)
        val roots = entry.dependencyIds
            .filter { it != self }
            .mapNotNull { walk(it, entries, modules, expanded, depth = 0) }
        return EnvDependencyGroup(EnvDependencyList(target, modules.ownerOf[rootId]), roots)
    }

    /**
     * The edge from an extra's synthetic node back to the package the extra belongs to.
     *
     * `sub[cli]` depends on `certifi` *and* on `sub` — that second edge is what "installing an extra
     * installs the package too" means in the graph. Following it would nest the whole main tree
     * under every extra, so it is dropped.
     *
     * Only that one edge, and only for a synthetic node. Dropping every edge that happens to land on
     * a root — which is what this used to do — is indistinguishable from it in a single-package
     * project and wrong in a workspace: a module that depends on a sibling depends on something that
     * is itself a root, and the sibling would vanish from the list that declares it.
     */
    private fun selfEdge(
        rootId: String,
        entry: Entry,
        entries: Map<String, Entry>,
        modules: Modules,
    ): String? {
        if (entry.kind !is Kind.Extra) return null
        modules.baseOf[rootId]?.let { return it }
        // No back-links to read: the edge is still the one to a package of the same name as the
        // node that carries the extra. Only when there is a name to match on — a nameless node would
        // otherwise match the next nameless one and drop an edge for the resemblance.
        if (entry.name.isEmpty()) return null
        return entry.dependencyIds.firstOrNull { entries[it]?.name == entry.name }
    }

    /** The node for [id] and everything under it. */
    private fun walk(
        id: String,
        entries: Map<String, Entry>,
        modules: Modules,
        expanded: MutableSet<String>,
        depth: Int,
    ): EnvDependencyNode? {
        val entry = entries[id] ?: return null
        if (entry.kind == Kind.Workspace) return null
        val extra = (entry.kind as? Kind.Extra)?.name
        val base = if (extra != null) baseOf(entry, entries) else null
        // A member's extra is a member: the extra's own id is not the one `members` names.
        val source = sourceOf(base ?: id, base?.let(entries::get) ?: entry, modules)
        val dependencyIds = if (base != null) extraDependencies(base, entry, entries) else entry.dependencyIds

        // Already shown in full somewhere in this group: repeat the row, not the subtree. This is
        // also what makes a dependency cycle terminate.
        if (id in expanded || depth >= MAX_DEPTH) {
            return EnvDependencyNode(
                name = entry.name,
                version = entry.version,
                expandedElsewhere = dependencyIds.isNotEmpty(),
                source = source,
                extra = extra,
            )
        }
        expanded += id

        val children = dependencyIds
            .mapNotNull { walk(it, entries, modules, expanded, depth + 1) }
            .sortedBy { it.name.lowercase() }
        return EnvDependencyNode(entry.name, entry.version, children, source = source, extra = extra)
    }

    /**
     * The package an extra's node belongs to — the entry of the same name that is a plain package.
     *
     * A requirement with an extra, `copy[x]`, is an entry of its own whose `kind` names the extra
     * and whose dependencies are the package itself and the extra's requirements; that first edge is
     * what "installing an extra installs the package too" means in the graph.
     */
    private fun baseOf(entry: Entry, entries: Map<String, Entry>): String? =
        entry.dependencyIds.firstOrNull { id ->
            entries[id]?.let { it.kind == Kind.Package && it.name == entry.name } == true
        }

    /**
     * What an extra's row holds: the package's own requirements and then the extra's, as uv's own
     * tree shows it — `copy[x]` over `tool` and `gitdep`, where the JSON says `copy[x]` depends on
     * `copy` and `gitdep` and `copy` on `tool` (uv 0.12.13). Following the edge to the package
     * instead would put a `copy` row under `copy[x]`, holding what the row above it should.
     */
    private fun extraDependencies(base: String, entry: Entry, entries: Map<String, Entry>): List<String> =
        (entries[base]?.dependencyIds.orEmpty() + entry.dependencyIds.filter { it != base }).distinct()

    /** Where [entry] comes from, with a member told apart from a path dependency by `members`. */
    private fun sourceOf(id: String, entry: Entry, modules: Modules): EnvSource =
        if (id in modules.memberIds) EnvSource.Member else entry.source

    /**
     * `source` as uv writes it: a single-key object naming how the package is obtained.
     *
     * Anything unrecognised — or absent, as it is in output from before uv wrote sources — is an
     * index, which is drawn as nothing. A uv that adds a new kind of source costs that source its
     * marker, not the row.
     *
     * `virtual` is a project that is not built and installs nothing of its own, so there is no copy
     * of it in the environment to go stale; for the one claim [EnvSource.Local.editable] makes —
     * that edits reach the environment without a reinstall — it is editable.
     */
    private fun source(value: JsonElement?): EnvSource {
        val obj = value?.takeIf { it.isJsonObject }?.asJsonObject ?: return EnvSource.Index
        obj.string("editable")?.let { return local(it, editable = true) }
        obj.string("virtual")?.let { return local(it, editable = true) }
        obj.string("directory")?.let { return local(it, editable = false) }
        obj.string("git")?.let { return EnvSource.Git(it) }
        obj.string("url")?.let { return EnvSource.Archive(it) }
        obj.string("path")?.let { return EnvSource.Archive(it) }
        return EnvSource.Index
    }

    private fun local(raw: String, editable: Boolean): EnvSource =
        path(raw)?.let { EnvSource.Local(it, editable) } ?: EnvSource.Index

    /** One `resolution` entry, or null when it is not shaped like one. */
    private fun entry(value: JsonElement): Entry? {
        val obj = value.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val kind = kind(obj.get("kind")) ?: return null
        // A row is drawn with its name, so an ordinary package must have one to be shown. The nodes
        // that head a *group* need none: the heading is the target's label, and the name was only
        // ever used to say which package the group belongs to.
        //
        // Which matters because uv does omit it. A virtual workspace root — a manifest holding only
        // `[tool.uv.workspace]` — has no distribution name, so its own `[dependency-groups]` arrive
        // as `workspace+/path:dev` with a `kind` and no `name`. Requiring one dropped those lists
        // from the tree entirely: the project's `dev` group was not shown as empty, it was not shown.
        val name = obj.string("name") ?: if (kind is Kind.Package) {
            return null
        } else {
            ""
        }
        val dependencies = obj.getAsJsonArray("dependencies")
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.string("id") }
            ?.toList()
            .orEmpty()
        return Entry(name, obj.string("version").orEmpty(), kind, dependencies, source(obj.get("source")))
    }

    /**
     * `kind` is either a string or a single-key object naming a group or an extra.
     *
     * An unrecognised string is treated as an ordinary package rather than rejected: a uv that adds
     * a new kind should cost the label on those rows, not the whole tree.
     */
    private fun kind(value: JsonElement?): Kind? = when {
        value == null -> null
        value.isJsonPrimitive -> if (value.asString == "workspace") Kind.Workspace else Kind.Package
        value.isJsonObject -> value.asJsonObject.let { obj ->
            obj.string("group")?.let(Kind::Group)
                ?: obj.string("extra")?.let(Kind::Extra)
                ?: Kind.Package
        }
        else -> null
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }

    /**
     * The root module first and the rest alphabetically; within a module, the main list, then
     * extras, then groups.
     *
     * The main list leads because it is the project's actual dependencies and the answer to almost
     * every question the window is opened with. Extras come next as the other thing a *consumer*
     * can install, and named groups — a development concern that never ships — last. `dev` sorts
     * first among the groups, being the one that is nearly always there and nearly always meant.
     *
     * Module before target, rather than after, because a module's lists are what a person reads
     * together: interleaving them would put the root's `dev` between a member's `dependencies` and
     * its `dev`, and the qualifier on each heading would be the only thing holding the tree together.
     */
    private fun order(modules: Modules): Comparator<EnvDependencyGroup> =
        compareBy<EnvDependencyGroup> { if (it.module == null || it.module == modules.rootModule) 0 else 1 }
            .thenBy { it.module.orEmpty().lowercase() }
            .thenBy {
                when (it.target) {
                    EnvDependencyTarget.Main -> 0
                    is EnvDependencyTarget.Extra -> 1
                    is EnvDependencyTarget.Group -> 2
                }
            }
            .thenBy { it.target != EnvDependencyTarget.DEV }
            .thenBy { it.target.label.lowercase() }
}
