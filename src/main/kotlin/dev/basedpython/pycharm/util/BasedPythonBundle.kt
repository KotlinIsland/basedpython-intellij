package dev.basedpython.pycharm.util

import com.intellij.DynamicBundle
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

/**
 * Localisation bundle for all user-visible strings in the BasedPython plugin.
 *
 * Usage:
 * ```kotlin
 * BasedPythonBundle.message("notification.binaryMissing")
 * BasedPythonBundle.message("notification.transpileFailed", errorMessage)
 * ```
 *
 * The backing .properties file lives at
 * `src/main/resources/messages/BasedPythonBundle.properties`.
 *
 * To activate i18n in the platform register the bundle in plugin.xml:
 * ```xml
 * <resource-bundle>messages.BasedPythonBundle</resource-bundle>
 * ```
 */
@NonNls
private const val BUNDLE = "messages.BasedPythonBundle"

object BasedPythonBundle : DynamicBundle(BUNDLE) {

    /**
     * Returns the localised message for [key], substituting [params] for any
     * `{0}`, `{1}`, … placeholders in the pattern.
     */
    fun message(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ): String = getMessage(key, *params)

    /**
     * Returns a lazy supplier for the localised message. Useful when passing
     * a string into IntelliJ APIs that accept `Supplier<String>`.
     */
    fun messagePointer(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ): java.util.function.Supplier<String> = getLazyMessage(key, *params)
}
