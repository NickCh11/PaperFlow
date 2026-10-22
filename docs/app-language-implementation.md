# App language and translation contribution support

Date: 2026-10-20.

## Application behavior

- Settings has an **App language** row. The picker uses `app/src/main/res/xml/app_locales.xml` and currently offers **System default** and **English** only.
- System default clears the per-app locale override. Resource selection uses the device's actual language list, including subsequent device-language changes. It does not set the application to English. Missing translations use the source resources.
- Explicit app languages use `AppCompatDelegate.setApplicationLocales`. Android 13+ stores the override through the platform locale APIs; earlier Android versions use AppCompat's `autoStoreLocales` support. The manifest declares the same language registry for Android's app-language settings.
- No additional app-language translations have been added. Existing source UI text was moved into resources rather than translated.

## Translation structure

The default resource catalog has **988 strings**, including **979 translatable entries**. It covers labels, dialogs, notices, user-facing errors, formatting templates and accessibility text. Source comments provide usage locations. Brand names and fixed format labels are marked non-translatable.

`UiText` provides resource access for Activities and background tasks using the selected app locale. `UiTextCatalog` bridges existing canonical tool identifiers to display resources. Menu routing, bottom navigation and tool search keep stable IDs; displayed translations are not used as operation names. Filenames and OCR/user text are not passed through that label lookup.

The public contribution interface is standard Android `values-…/strings.xml` files plus locale registration. [TRANSLATING.md](TRANSLATING.md), `CONTRIBUTING.md` and `tools/localization/new_language.py` document and assist the workflow. The draft helper creates an empty file and does not advertise an untranslated language. Partial translations are allowed with source fallback; strict validation is optional.

## PR checks

`.github/workflows/translations.yml` runs a standard-library Python validator and its regression tests on translation PRs. Checks include XML, resource names/types, duplicates, non-translatable entries, numbered format arguments, naming-template tokens, plurals, string-array sizes, empty values, common Android escapes, damaged Unicode and registration of real translation files. Coverage is reported.

The checker cannot establish semantic or grammatical accuracy. Native-speaker review and device review of layout/font scaling remain part of declaring a language complete. No test translations are shipped in the app; validator examples use temporary marker fixtures.

## Validation performed

- **22/22 Python validator regression tests passed.**
- Default resources/locale registry: **0 validation errors**.
- **4/4 AppLanguageTest instrumentation tests passed**, on the API 37 x86_64 emulator using the debug build, in 23.704 seconds.
- Instrumentation tests exercise the visible picker, exact choice count, actual English/System default selections, persistence through recreation, device-locale resolution, stable navigation/tool IDs and resource formatting. Their registered-locale loops automatically include languages added by future PRs.
- ARM64 and x86_64 release builds completed successfully. APK signing/alignment and source packaging are recorded in `artifacts/app-language/checks.json`.
- The GitHub workflow was added locally; it has not been run by GitHub in this session. The older Android compatibility path and full translated/RTL layouts have not been runtime-tested with additional language packs.

Reference: [Android per-app language preferences](https://developer.android.com/guide/topics/resources/app-languages).
