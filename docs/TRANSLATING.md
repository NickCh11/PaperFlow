# Translating PaperFlow

PaperFlow uses Android string resources. You do not need to edit Java, install language models or provide an OCR/translation pack to translate the interface.

## Files to edit

- **Source text:** `app/src/main/res/values/strings.xml`.
- **Your translation:** `app/src/main/res/values-<language>/strings.xml`.
- **Languages offered by the app and Android:** `app/src/main/res/xml/app_locales.xml`.

Only English is registered initially. No additional language translations are bundled by this change. **System default follows the device's language preferences**, including later device-language changes. It does not force English; English is the resource fallback when a translation is unavailable.

## Start a translation

1. Fork the repository and create a branch.
2. Create a draft file, for example:

   ```sh
   python3 tools/localization/new_language.py fr
   ```

   This creates an empty file; it does not add a fake or untranslated language to the picker. You can also create the locale using Android Studio's **Translations Editor**.
3. Copy the entries you intend to translate from the source file into the new file and translate their **values**. Leave the `name` keys and attributes intact. Do not copy entries marked `translatable="false"`.
4. Once the translation is usable, enable it:

   ```sh
   python3 tools/localization/new_language.py fr --register
   ```

   This adds the language to `app_locales.xml`, which drives both the in-app picker and Android 13+ app-language settings. No Java changes are required.
5. Run the checks below, then submit a PR containing the locale file and registry change. Describe the language/region and whether the translation is complete or partial.

Replace `fr` with your own BCP-47 language tag. Examples: `pt-BR` uses `values-pt-rBR`; `zh-Hant` uses `values-b+zh+Hant`. The helper selects the correct Android directory format.

Partial translations are supported: missing entries fall back to English. Coverage is printed by the checker. Use `--strict` if you want to require a complete registered translation. Do not fill missing entries with copied English text just to increase the percentage.

## Keep these intact

- Keep numbered format placeholders, such as `%1$s` or `%2$d`, including their types. You may reorder numbered placeholders to suit your grammar. Use `%%` for a literal percent in a formatted string.
- Keep naming-template tokens `{prefix}`, `{date}`, `{time}` and `{n}` when they appear in instructions.
- Preserve `formatted="false"` and other resource attributes.
- Escape XML characters (`&amp;`, `&lt;`) and Android string characters (`\'`, `\"`, `\n`) correctly. Save files as UTF-8.
- Keep all items of a string array in the same order. Plurals must include `other`; you may add the plural quantities your language needs.
- Do not translate filenames, recognized document text, locale tags, MIME types, intent keys, tool IDs, model metadata or license texts. Brand names are marked non-translatable.
- Source comments identify where a string is used. Search the resource key in Android Studio when you need more context.

`UiTextCatalog` maps stable internal tool IDs to translated display text. Translators should not edit that Java file. Navigation uses stable IDs rather than translated captions; document names and OCR content remain user data.

## Automated PR checks

Python 3.10+ and its standard library are enough; no Android SDK, downloaded models, credentials or paid service is needed:

```sh
python3 tools/localization/check_translations.py
python3 -m unittest discover -s tools/localization -p 'test_*.py' -v
```

The GitHub Actions workflow `.github/workflows/translations.yml` runs these commands on translation PRs. It checks XML, resource keys/types, duplicates, protected brands, format arguments, naming tokens, plurals, array lengths, empty entries, encoding damage and language registration. Its regression fixtures include deliberately broken PR examples and live only in temporary test directories.

These checks prevent structural problems. They cannot establish that the wording is accurate or natural. Native-speaker review is still needed, especially for OCR, password protection and redaction instructions. The percentage is resource coverage, not a measure of translation quality.

## Android checks

`app/src/androidTest/java/com/example/myapplication/AppLanguageTest.java` exercises every language registered in `app_locales.xml`, so a future PR automatically expands its language loop. It checks Settings, actual picker choices, locale switching/recreation, stable navigation IDs, resource formatting and System default resolving to device languages rather than English.

With an emulator/device connected:

```sh
./gradlew :app:connectedDebugAndroidTest -PpaperflowAbi=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.myapplication.AppLanguageTest
```

Use `arm64-v8a` for an ARM device. Android compilation is also needed to catch platform-specific resource escaping and packaging errors. Review long labels, dialogs, font scaling and RTL layout on a device before declaring a language complete. No test translations are shipped as selectable app languages.
