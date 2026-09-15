package dev.basedpython.pycharm.settings.app

/**
 * Pure resolution helpers that layer project-level values over application-level
 * defaults from [BasedPythonAppSettings].
 *
 * Semantics: a project value *wins* when it is present — non-null, and for strings non-blank.
 * Otherwise the application default is used. For string paths a null/blank project value means
 * "inherit the global default"; for the global default itself a null/blank value means
 * "autodetect" and is propagated as-is. A toggle has no blank, so a project that has never chosen
 * holds null rather than the default it would otherwise have to copy, and freeze, at creation.
 *
 * Each resolver comes in two flavours:
 *  - a pure overload taking the explicit default (trivially unit-testable), and
 *  - a convenience overload reading [BasedPythonAppSettings.getInstance].
 */
object BasedPythonDefaults {

  /** True when a project string value should be considered "set". */
  private fun isSet(value: String?): Boolean = !value.isNullOrBlank()

  // --- Pure overloads (explicit defaults) ---------------------------------

  fun effectiveByPath(projectValue: String?, default: String?): String? =
    if (isSet(projectValue)) projectValue else default

  fun effectiveBuffPath(projectValue: String?, default: String?): String? =
    if (isSet(projectValue)) projectValue else default

  /**
   * Extra-args resolution. An empty project value is treated as "inherit"; a
   * non-blank project value overrides. Returns "" rather than null so callers
   * can use it directly as a CLI args string.
   */
  fun effectiveExtraArgs(projectValue: String?, default: String): String =
    if (isSet(projectValue)) projectValue!! else default

  /** A project's own on/off choice, or [default] when it has not made one. */
  fun effectiveEnabled(projectValue: Boolean?, default: Boolean): Boolean =
    projectValue ?: default

  // --- Convenience overloads (read app service) ---------------------------

  fun effectiveByPath(projectValue: String?): String? =
    effectiveByPath(projectValue, BasedPythonAppSettings.getInstance().defaultByPath)

  fun effectiveBuffPath(projectValue: String?): String? =
    effectiveBuffPath(projectValue, BasedPythonAppSettings.getInstance().defaultBuffPath)

  fun effectiveByExtraArgs(projectValue: String?): String =
    effectiveExtraArgs(projectValue, BasedPythonAppSettings.getInstance().defaultByExtraArgs)

  fun effectiveBuffExtraArgs(projectValue: String?): String =
    effectiveExtraArgs(projectValue, BasedPythonAppSettings.getInstance().defaultBuffExtraArgs)

  fun effectiveByEnabled(projectValue: Boolean?): Boolean =
    effectiveEnabled(projectValue, BasedPythonAppSettings.getInstance().defaultByEnabled)

  fun effectiveBuffEnabled(projectValue: Boolean?): Boolean =
    effectiveEnabled(projectValue, BasedPythonAppSettings.getInstance().defaultBuffEnabled)
}
