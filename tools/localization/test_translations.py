"""PR validator regression tests. Fixtures are temporary markers, not app translations."""
import pathlib
import tempfile
import unittest
from check_translations import directory_tag, placeholders, validate

SOURCE = """<resources>
<string name="welcome">Hello %1$s, page %2$d</string>
<string name="naming" formatted="false">Use {prefix} and {n}.</string>
<string name="brand" translatable="false" formatted="false">PaperFlow</string>
<plurals name="pages"><item quantity="one">%1$d page</item><item quantity="other">%1$d pages</item></plurals>
<string-array name="sizes"><item>A4</item><item>A5</item></string-array>
</resources>"""


class TranslationPrTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.res = pathlib.Path(self.temp.name)
        (self.res / "values").mkdir()
        (self.res / "xml").mkdir()
        (self.res / "values/strings.xml").write_text(SOURCE, encoding="utf-8")
        self.registry("en")

    def registry(self, *tags):
        (self.res / "xml/app_locales.xml").write_text('<locale-config xmlns:android="http://schemas.android.com/apk/res/android">' + ''.join(f'<locale android:name="{tag}"/>' for tag in tags) + '</locale-config>', encoding="utf-8")

    def draft(self, value, folder="values-fr"):
        target = self.res / folder
        target.mkdir(exist_ok=True)
        (target / "strings.xml").write_text(value, encoding="utf-8")

    def assert_error(self, fragment):
        self.assertTrue(any(fragment in error for error in validate(self.res)[0]), validate(self.res))

    def test_source_only_is_valid(self):
        self.assertEqual([], validate(self.res)[0])

    def test_positional_arguments_can_reorder(self):
        self.draft('<resources><string name="welcome">⟦%2$d — %1$s⟧</string></resources>')
        self.registry("en", "fr")
        self.assertEqual([], validate(self.res)[0])

    def test_missing_placeholder_fails(self):
        self.draft('<resources><string name="welcome">⟦%1$s⟧</string></resources>')
        self.assert_error("placeholders changed")

    def test_wrong_placeholder_type_fails(self):
        self.draft('<resources><string name="welcome">⟦%1$s %2$s⟧</string></resources>')
        self.assert_error("placeholders changed")

    def test_duplicate_key_fails(self):
        self.draft('<resources><string name="welcome">%1$s %2$d</string><string name="welcome">%1$s %2$d</string></resources>')
        self.assert_error("duplicate resource")

    def test_unknown_key_fails(self):
        self.draft('<resources><string name="welcom">⟦⟧</string></resources>')
        self.assert_error("unknown resource")

    def test_malformed_xml_fails(self):
        self.draft('<resources><string name="welcome">A & B</string></resources>')
        self.assert_error("invalid XML")

    def test_brand_cannot_be_translated(self):
        self.draft('<resources><string name="brand" formatted="false">⟦⟧</string></resources>')
        self.assert_error("translatable=false")

    def test_naming_tokens_are_protected(self):
        self.draft('<resources><string name="naming" formatted="false">⟦{prefix}⟧</string></resources>')
        self.assert_error("naming tokens changed")

    def test_plural_other_required(self):
        self.draft('<resources><plurals name="pages"><item quantity="one">%1$d</item></plurals></resources>')
        self.assert_error("quantity=other")

    def test_locale_can_add_plural_categories(self):
        self.draft('<resources><plurals name="pages"><item quantity="few">%1$d</item><item quantity="many">%1$d</item><item quantity="other">%1$d</item></plurals></resources>')
        self.assertEqual([], validate(self.res)[0])

    def test_array_count_is_fixed(self):
        self.draft('<resources><string-array name="sizes"><item>A4</item></string-array></resources>')
        self.assert_error("array length changed")

    def test_empty_translation_fails(self):
        self.draft('<resources><string name="welcome"></string></resources>')
        self.assert_error("empty translation")

    def test_registered_language_must_exist(self):
        self.registry("en", "fr")
        self.assert_error("no translations")

    def test_partial_translation_falls_back_unless_strict(self):
        self.registry("en", "fr")
        self.draft('<resources><string name="welcome">⟦%1$s %2$d⟧</string></resources>')
        self.assertEqual([], validate(self.res)[0])
        self.assertTrue(validate(self.res, strict=True)[0])

    def test_formatting_attribute_cannot_change(self):
        self.draft('<resources><string name="naming">{prefix} {n}</string></resources>')
        self.assert_error("formatted attribute")

    def test_encoding_damage_is_rejected(self):
        self.draft('<resources><string name="welcome">\ufffd %1$s %2$d</string></resources>')
        self.assert_error("encoding")

    def test_locale_directory_mapping(self):
        self.assertEqual("pt-BR", directory_tag("values-pt-rBR"))
        self.assertEqual("zh-Hant-TW", directory_tag("values-b+zh+Hant+TW"))
        self.assertIsNone(directory_tag("values-night"))

    def test_literal_percent_is_not_an_argument(self):
        self.assertEqual(placeholders("%1$d%%"), placeholders("%% %1$d"))

    def test_unescaped_apostrophe_fails_before_android_build(self):
        self.draft('<resources><string name="welcome">It\'s %1$s %2$d</string></resources>')
        self.assert_error("escape apostrophes")

    def test_escaped_apostrophe_is_valid(self):
        self.draft('<resources><string name="welcome">It\\\'s %1$s %2$d</string></resources>')
        self.assertEqual([], validate(self.res)[0])

    def test_broken_unicode_escape_fails(self):
        self.draft('<resources><string name="welcome">\\u12 %1$s %2$d</string></resources>')
        self.assert_error("Unicode escape")


if __name__ == "__main__":
    unittest.main()
