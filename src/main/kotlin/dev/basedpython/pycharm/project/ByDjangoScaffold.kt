package dev.basedpython.pycharm.project

import java.security.SecureRandom

/**
 * The files of a new django project and of a new app in one, as `django-admin startproject` and
 * `manage.py startapp` write them, laid out for a basedpython project: the project's `pyproject.toml`
 * with django among its dependencies and `basedpython` among its development ones.
 *
 * Written here rather than by running django's commands: a new project has no environment to run
 * them in yet, and an app made by `by run manage startapp` is written into the tree `by run` stages
 * the program in, not the project's. Both are `.py`, as django's are: django finds an app's
 * `models.py` and `admin.py` by importing them, which a `.by` module answers only under `by run`.
 *
 * Every file passes `buff check` and `buff format --check` as written — consecutive assignments
 * aligned, as `buff` aligns them — as the plain scaffold's do ([BasedPythonProjectScaffold]); and
 * django's own `manage.py check` with the app installed. `BasedPythonProjectScaffoldLiveTest` runs
 * all three.
 */
internal object ByDjangoScaffold {

    /** [projectName] as the package the settings live in: `my-site` → `my_site`. */
    fun packageName(projectName: String): String {
        val name = projectName.lowercase().replace(Regex("[^a-z0-9_]"), "_").trim('_').ifEmpty { "site" }
        return if (name.first().isDigit()) "site_$name" else name
    }

    fun files(projectName: String, secretKey: String = secretKey()): Map<String, String> {
        val pkg = packageName(projectName)
        return linkedMapOf(
            "pyproject.toml" to pyproject(projectName),
            "manage.py" to manage(pkg),
            "$pkg/__init__.py" to "",
            "$pkg/settings.py" to settings(pkg, secretKey),
            "$pkg/urls.py" to urls(),
            "$pkg/asgi.py" to server(pkg, "asgi", "ASGI", "get_asgi_application"),
            "$pkg/wsgi.py" to server(pkg, "wsgi", "WSGI", "get_wsgi_application"),
            ".gitignore" to gitignore(),
            "README.md" to readme(projectName, pkg),
        )
    }

    /**
     * A new app named [name], as `manage.py startapp` writes one, by path relative to the directory
     * it is made in.
     */
    fun appFiles(name: String): Map<String, String> {
        val config = name.split('_').joinToString("") { part -> part.replaceFirstChar(Char::uppercaseChar) } + "Config"
        return linkedMapOf(
            "$name/__init__.py" to "",
            "$name/apps.py" to """
from django.apps import AppConfig


class $config(AppConfig):
    default_auto_field = "django.db.models.BigAutoField"
    name               = "$name"
""".trimStart(),
            "$name/models.py" to "from django.db import models  # noqa: F401\n\n# Create your models here.\n",
            "$name/admin.py" to "from django.contrib import admin  # noqa: F401\n\n# Register your models here.\n",
            "$name/views.py" to "from django.shortcuts import render  # noqa: F401\n\n# Create your views here.\n",
            "$name/tests.py" to "from django.test import TestCase  # noqa: F401\n\n# Create your tests here.\n",
            "$name/migrations/__init__.py" to "",
        )
    }

    /**
     * Whether [name] can name an app: a python identifier that is not a keyword. `startapp` also
     * refuses the name of a module the environment can import, which only the environment can say,
     * so a name that shadows one is let through here.
     */
    fun isAppName(name: String): Boolean = Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name) && name !in KEYWORDS

    private val KEYWORDS = setOf(
        "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
        "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal",
        "not", "or", "pass", "raise", "return", "try", "while", "with", "yield",
    )

    /** As django's `get_random_secret_key`: fifty characters of its alphabet, marked insecure as `startproject` marks it. */
    private fun secretKey(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789!@#%^&*(-_=+)"
        val random = SecureRandom()
        return "django-insecure-" + (1..50).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun pyproject(projectName: String) = """
[project]
name = "$projectName"
version = "0.1.0"
description = ""
requires-python = ">=3.10"
dependencies = ["django>=5.2"]

[dependency-groups]
dev = ["basedpython"]

[tool.ruff]
line-length = 88
target-version = "py310"

[tool.ruff.lint]
select = ["E", "F", "W", "I"]
ignore = []

[tool.ruff.format]
quote-style = "double"
indent-style = "space"
""".trimStart()

    private fun manage(pkg: String) = """
#!/usr/bin/env python
""${'"'}Django's command-line utility for administrative tasks.""${'"'}

import os
import sys


def main() -> None:
    ""${'"'}Run administrative tasks.""${'"'}
    os.environ.setdefault("DJANGO_SETTINGS_MODULE", "$pkg.settings")
    from django.core.management import execute_from_command_line

    execute_from_command_line(sys.argv)


if __name__ == "__main__":
    main()
""".trimStart()

    private fun settings(pkg: String, secretKey: String) = """
""${'"'}Django settings for $pkg.""${'"'}

from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent

# SECURITY WARNING: keep the secret key used in production secret!
SECRET_KEY = ${quote(secretKey)}

# SECURITY WARNING: don't run with debug turned on in production!
DEBUG = True

ALLOWED_HOSTS: list[str] = []

INSTALLED_APPS = [
    "django.contrib.admin",
    "django.contrib.auth",
    "django.contrib.contenttypes",
    "django.contrib.sessions",
    "django.contrib.messages",
    "django.contrib.staticfiles",
]

MIDDLEWARE = [
    "django.middleware.security.SecurityMiddleware",
    "django.contrib.sessions.middleware.SessionMiddleware",
    "django.middleware.common.CommonMiddleware",
    "django.middleware.csrf.CsrfViewMiddleware",
    "django.contrib.auth.middleware.AuthenticationMiddleware",
    "django.contrib.messages.middleware.MessageMiddleware",
    "django.middleware.clickjacking.XFrameOptionsMiddleware",
]

ROOT_URLCONF = "$pkg.urls"

TEMPLATES = [
    {
        "BACKEND": "django.template.backends.django.DjangoTemplates",
        "DIRS": [BASE_DIR / "templates"],
        "APP_DIRS": True,
        "OPTIONS": {
            "context_processors": [
                "django.template.context_processors.request",
                "django.contrib.auth.context_processors.auth",
                "django.contrib.messages.context_processors.messages",
            ],
        },
    },
]

WSGI_APPLICATION = "$pkg.wsgi.application"

DATABASES = {
    "default": {
        "ENGINE": "django.db.backends.sqlite3",
        "NAME": BASE_DIR / "db.sqlite3",
    }
}

_VALIDATION              = "django.contrib.auth.password_validation"
AUTH_PASSWORD_VALIDATORS = [
    {"NAME": f"{_VALIDATION}.UserAttributeSimilarityValidator"},
    {"NAME": f"{_VALIDATION}.MinimumLengthValidator"},
    {"NAME": f"{_VALIDATION}.CommonPasswordValidator"},
    {"NAME": f"{_VALIDATION}.NumericPasswordValidator"},
]

LANGUAGE_CODE = "en-us"
TIME_ZONE     = "UTC"
USE_I18N      = True
USE_TZ        = True

STATIC_URL = "static/"

DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"
""".trimStart()

    private fun urls() = """
from django.contrib import admin
from django.urls import path

urlpatterns = [
    path("admin/", admin.site.urls),
]
""".trimStart()

    private fun server(pkg: String, module: String, kind: String, factory: String) = """
""${'"'}$kind config for $pkg, exposing the $kind callable as `application`.""${'"'}

import os

from django.core.$module import $factory

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "$pkg.settings")

application = $factory()
""".trimStart()

    private fun gitignore() = """
# Python
__pycache__/
*.py[cod]

# Virtual environment
.venv/

# basedpython build output
build/

# django
db.sqlite3
/static/
/media/

# IDE
.idea/
.vscode/
""".trimStart()

    private fun readme(projectName: String, pkg: String) = """
# $projectName

A django project in [basedpython](https://github.com/KotlinIsland/basedpython).

## Getting started

```bash
uv sync --dev
by run manage migrate
by run manage runserver
```

`$pkg/settings.py` holds the settings, `$pkg/urls.py` the routes. Make an app with
*New | Django App* in the Project view, and run any command with *Tools | Run manage.py Task…*.
""".trimStart()

    /** [text] as a python string literal: double quotes, `\` and `"` escaped. */
    private fun quote(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
