package dev.basedpython.pycharm.env.manager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Naming a dependency list in the Add Package combo.
 *
 * The combo is editable so a new group can be typed, and an editable combo renders its selected
 * value through its editor — `toString()` — rather than through any renderer set on it, which is how
 * `Group(name=dev)` reached the screen. The model holds these tokens instead, so what is displayed
 * is what this produces, and what the user types comes back through [EnvTargetLabels.parse].
 */
class EnvTargetLabelsTest {

    @Test
    fun `every target has a token a person would type`() {
        assertEquals("dependencies", EnvTargetLabels.format(EnvDependencyTarget.Main))
        assertEquals("dev", EnvTargetLabels.format(EnvDependencyTarget.DEV))
        assertEquals("docs", EnvTargetLabels.format(EnvDependencyTarget.Group("docs")))
        assertEquals("cli (extra)", EnvTargetLabels.format(EnvDependencyTarget.Extra("cli")))
    }

    /** Nothing that leaves the combo may come back as a different list than it went in as. */
    @Test
    fun `every target round-trips`() {
        val targets = listOf(
            EnvDependencyTarget.Main,
            EnvDependencyTarget.DEV,
            EnvDependencyTarget.Group("docs"),
            EnvDependencyTarget.Extra("cli"),
        )
        for (target in targets) {
            assertEquals(target, EnvTargetLabels.parse(EnvTargetLabels.format(target)), target.toString())
        }
    }

    /** The whole point of the combo being editable: a name that does not exist yet. */
    @Test
    fun `an unrecognised name is a dependency group, which is the one thing that can be created`() {
        assertEquals(EnvDependencyTarget.Group("integration"), EnvTargetLabels.parse("integration"))
        assertEquals(EnvDependencyTarget.Group("type-check"), EnvTargetLabels.parse("  type-check  "))
    }

    /**
     * A group and an extra can share a name, and adding to the wrong one edits a different section
     * of `pyproject.toml`, so the suffix has to survive the round trip.
     */
    @Test
    fun `a group and an extra of the same name stay distinct`() {
        assertEquals(EnvDependencyTarget.Group("cli"), EnvTargetLabels.parse("cli"))
        assertEquals(EnvDependencyTarget.Extra("cli"), EnvTargetLabels.parse("cli (extra)"))
    }

    @Test
    fun `the main list is recognised however it is cased`() {
        assertEquals(EnvDependencyTarget.Main, EnvTargetLabels.parse("dependencies"))
        assertEquals(EnvDependencyTarget.Main, EnvTargetLabels.parse("Dependencies"))
        assertEquals(EnvDependencyTarget.Main, EnvTargetLabels.parse("  dependencies "))
    }

    @Test
    fun `nothing typed names nothing`() {
        assertNull(EnvTargetLabels.parse(""))
        assertNull(EnvTargetLabels.parse("   "))
    }

    /**
     * A suffix with nothing before it names no extra.
     *
     * It falls through to being a (nonsensical) group name rather than being rejected, which is
     * deliberate: every unrecognised string is a group, that is what makes typing a new one work,
     * and adding a special case for one absurd input buys nothing — the backend rejects it with a
     * clearer message than this could invent.
     */
    @Test
    fun `a bare extra suffix is not read as an extra`() {
        assertEquals(EnvDependencyTarget.Group("(extra)"), EnvTargetLabels.parse("(extra)"))
        assertNull((EnvTargetLabels.parse("(extra)") as? EnvDependencyTarget.Extra)?.name)
    }

    /**
     * The main list and `dev` are offered whether or not the project has them: they are the answer
     * to almost every add, and a project with neither is the one about to gain its first.
     */
    @Test
    fun `the offered options always lead with the main list and dev`() {
        val options = EnvTargetLabels.options(emptyList(), EnvDependencyTarget.Main)
        assertEquals(listOf("dependencies", "dev"), options)
    }

    @Test
    fun `the project's own groups and extras are offered, without duplicates`() {
        val options = EnvTargetLabels.options(
            listOf(
                EnvDependencyTarget.DEV,
                EnvDependencyTarget.Group("docs"),
                EnvDependencyTarget.Extra("cli"),
            ),
            EnvDependencyTarget.Group("docs"),
        )
        assertEquals(listOf("dependencies", "dev", "docs", "cli (extra)"), options)
    }

    /** Where the user was in the tree has to be selectable, even if it is new to the list. */
    @Test
    fun `the starting target is always among the options`() {
        val options = EnvTargetLabels.options(emptyList(), EnvDependencyTarget.Group("integration"))
        assertEquals(listOf("dependencies", "dev", "integration"), options)
    }
}
