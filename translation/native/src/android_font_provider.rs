//! Android-native [`FontProvider`] backed by `/system/etc/fonts.xml`.
//!
//! Non-Latin scripts select a script-specific primary font. Every request
//! also receives the system's default sans-serif face (Roboto on AOSP),
//! including Latin text embedded inside a non-Latin run. If discovery
//! returns no usable files, the PDF writer can use its Standard-14 fallback.
//!
//! Lookup parses `/system/etc/fonts.xml` (the same data Skia / minikin
//! consume internally) and resolves to files in `/system/fonts/`. The
//! whole provider works back to minSdk 21 — the API-version differences
//! only affect *how* we identify a script's family, not whether we can.
//!
//! Three layered search passes (all run on every API level):
//!
//! 1. `<family lang="...">` exact match. CJK families (`ja`, `ko`,
//!    `zh-Hans`, `zh-Hant`) have carried this attribute since API 21.
//!    Other scripts (`und-Arab`, `und-Hebr`, `und-Thai`, Indic, …)
//!    only got `lang=` in the API 28 fonts.xml refresh, so this pass
//!    misses them on older devices.
//! 2. Filename keyword match (`NotoSansArabic`, `NotoNaskh`, …). On
//!    API 21..27 the non-CJK fallback families have no `lang` and
//!    are only identifiable by their Noto filenames; this pass picks
//!    them up. Harmless on API 28+ since pass 1 already hit.
//! 3. Default `name="sans-serif"` family (Roboto). Covers Cyrillic and
//!    Greek on every release — Roboto has shipped both glyph blocks
//!    since Android 5.0, and no `und-Cyrl` / `und-Grek` family ever
//!    existed.
//!
//! Within the chosen family we pick the closest `(weight, italic)`
//! match.

use std::cell::RefCell;
use std::path::{Path, PathBuf};
use std::sync::OnceLock;

use markup5ever::buffer_queue::BufferQueue;
use translator::font_provider::{FontHandle, FontProvider, FontRequest};
use translator::script::Script as TrScript;
use xml5ever::tendril::StrTendril;
use xml5ever::tokenizer::{EndTag, ProcessResult, StartTag, Token, TokenSink, XmlTokenizer};

const FONTS_XML_PATH: &str = "/system/etc/fonts.xml";
const SYSTEM_FONTS_DIR: &str = "/system/fonts";

#[derive(Debug, Clone)]
struct FontEntry {
    file_name: String,
    weight: u16,
    italic: bool,
    ttc_index: u32,
}

#[derive(Debug, Default, Clone)]
struct FamilyEntry {
    name: Option<String>,
    langs: Vec<String>,
    fonts: Vec<FontEntry>,
}

#[derive(Default)]
struct ParseState {
    families: Vec<FamilyEntry>,
    current_family: Option<FamilyEntry>,
    current_font: Option<FontEntry>,
    font_text_buf: String,
}

struct ParserSink {
    state: RefCell<ParseState>,
}

impl TokenSink for ParserSink {
    type Handle = ();

    fn process_token(&self, token: Token) -> ProcessResult<()> {
        let mut state = self.state.borrow_mut();
        match token {
            Token::Tag(tag) => {
                let local = &*tag.name.local;
                match tag.kind {
                    StartTag => {
                        if local == "family" {
                            let mut entry = FamilyEntry::default();
                            for attr in tag.attrs.iter() {
                                match &*attr.name.local {
                                    "name" => entry.name = Some(attr.value.to_string()),
                                    "lang" => {
                                        entry.langs = attr
                                            .value
                                            .split_whitespace()
                                            .map(str::to_string)
                                            .collect();
                                    }
                                    _ => {}
                                }
                            }
                            state.current_family = Some(entry);
                        } else if local == "font" && state.current_family.is_some() {
                            let mut font = FontEntry {
                                file_name: String::new(),
                                weight: 400,
                                italic: false,
                                ttc_index: 0,
                            };
                            for attr in tag.attrs.iter() {
                                match &*attr.name.local {
                                    "weight" => {
                                        font.weight = attr.value.parse().unwrap_or(400);
                                    }
                                    "style" => {
                                        font.italic = &*attr.value == "italic";
                                    }
                                    "index" => {
                                        font.ttc_index = attr.value.parse().unwrap_or(0);
                                    }
                                    _ => {}
                                }
                            }
                            state.current_font = Some(font);
                            state.font_text_buf.clear();
                        }
                    }
                    EndTag => {
                        if local == "font" {
                            if let Some(mut font) = state.current_font.take() {
                                font.file_name = state.font_text_buf.trim().to_string();
                                state.font_text_buf.clear();
                                if !font.file_name.is_empty()
                                    && let Some(family) = state.current_family.as_mut()
                                {
                                    family.fonts.push(font);
                                }
                            }
                        } else if local == "family"
                            && let Some(family) = state.current_family.take()
                            && !family.fonts.is_empty()
                        {
                            state.families.push(family);
                        }
                    }
                    _ => {}
                }
            }
            Token::Characters(s) => {
                if state.current_font.is_some() {
                    state.font_text_buf.push_str(&s);
                }
            }
            _ => {}
        }
        ProcessResult::Continue
    }
}

fn parse_fonts_xml(xml: &str) -> Vec<FamilyEntry> {
    let sink = ParserSink {
        state: RefCell::new(ParseState::default()),
    };
    let buffer = BufferQueue::default();
    buffer.push_back(StrTendril::from_slice(xml));
    let tok = XmlTokenizer::new(sink, Default::default());
    let _ = tok.feed(&buffer);
    tok.end();
    tok.sink.state.into_inner().families
}

fn load_families(path: &Path) -> Vec<FamilyEntry> {
    match std::fs::read_to_string(path) {
        Ok(xml) => {
            let parsed = parse_fonts_xml(&xml);
            log::debug!(
                "[font] parsed {} families from {}",
                parsed.len(), path.display()
            );
            parsed
        }
        Err(e) => {
            log::warn!("[font] cannot read {}: {e}", path.display());
            Vec::new()
        }
    }
}

fn families() -> &'static [FamilyEntry] {
    static CACHE: OnceLock<Vec<FamilyEntry>> = OnceLock::new();
    CACHE.get_or_init(|| load_families(Path::new(FONTS_XML_PATH)))
}

/// Scripts the bundled translator catalog has models for (per
/// `index_v6.json`). Anything outside this set returns `None` from
/// [`non_latin_script`] and rides the Latin / Helvetica path. Adding a
/// new pack to the catalog with a non-Latin script means adding a
/// variant here, a code in [`non_latin_script`], an entry in
/// [`lang_tags`], and a fallback keyword in [`name_keywords`] — the
/// `every_index_language_maps_correctly` test reads the catalog and catches misses.
#[derive(Clone, Copy)]
enum Script {
    Cyrillic,       // be, bg, ru, sr, uk
    Greek,          // el
    Georgian,       // ka
    Arabic,         // ar, fa, ug, ur
    Hebrew,         // he
    Thai,           // th
    Devanagari,     // hi, mr
    Bengali,        // bn
    Tamil,          // ta
    Telugu,         // te
    Kannada,        // kn
    Malayalam,      // ml
    Gujarati,       // gu
    Japanese,       // ja
    Korean,         // ko
    HanSimplified,  // zh
    HanTraditional, // zh_hant
}

fn non_latin_script(language: &str) -> Option<Script> {
    let normalized = language.replace('_', "-").to_ascii_lowercase();
    let mut parts = normalized.split('-');
    let primary = parts.next().unwrap_or(&normalized);
    let suffix = parts.next();
    Some(match primary {
        "ja" => Script::Japanese,
        "ko" => Script::Korean,
        "zh" => match suffix {
            Some("hant") | Some("tw") | Some("hk") | Some("mo") => Script::HanTraditional,
            _ => Script::HanSimplified,
        },
        "ru" | "uk" | "be" | "bg" | "sr" => Script::Cyrillic,
        "el" => Script::Greek,
        "ka" => Script::Georgian,
        "ar" | "fa" | "ug" | "ur" => Script::Arabic,
        "he" => Script::Hebrew,
        "th" => Script::Thai,
        "hi" | "mr" => Script::Devanagari,
        "bn" => Script::Bengali,
        "ta" => Script::Tamil,
        "te" => Script::Telugu,
        "kn" => Script::Kannada,
        "ml" => Script::Malayalam,
        "gu" => Script::Gujarati,
        _ => return None,
    })
}

/// Map the translator's [`TrScript`] to this provider's internal enum. The
/// internal enum exists because Android's `fonts.xml` distinguishes
/// simplified vs traditional Han via `lang=zh-Hans` / `lang=zh-Hant`, which
/// the translator's script enum collapses into a single `Han` variant.
/// We pull the disambiguation from the BCP-47 hint when needed.
fn script_from_translator(script: TrScript, language: &str) -> Option<Script> {
    Some(match script {
        TrScript::Cyrillic => Script::Cyrillic,
        TrScript::Greek => Script::Greek,
        TrScript::Georgian => Script::Georgian,
        TrScript::Arabic => Script::Arabic,
        TrScript::Hebrew => Script::Hebrew,
        TrScript::Thai => Script::Thai,
        TrScript::Devanagari => Script::Devanagari,
        TrScript::Bengali => Script::Bengali,
        TrScript::Tamil => Script::Tamil,
        TrScript::Telugu => Script::Telugu,
        TrScript::Kannada => Script::Kannada,
        TrScript::Malayalam => Script::Malayalam,
        TrScript::Gujarati => Script::Gujarati,
        TrScript::Hiragana | TrScript::Katakana => Script::Japanese,
        TrScript::Hangul => Script::Korean,
        TrScript::Han => match non_latin_script(language) {
            Some(Script::HanTraditional) => Script::HanTraditional,
            _ => Script::HanSimplified,
        },
        // Latin / Common / anything else uses the default sans-serif
        // fallback appended by `locate`.
        _ => return None,
    })
}

fn lang_tags(script: Script) -> &'static [&'static str] {
    match script {
        Script::Cyrillic | Script::Greek => &[],
        Script::Georgian => &["und-Geor"],
        Script::Japanese => &["ja"],
        Script::Korean => &["ko"],
        Script::HanSimplified => &["zh-Hans"],
        Script::HanTraditional => &["zh-Hant", "zh-Bopo"],
        Script::Arabic => &["und-Arab"],
        Script::Hebrew => &["und-Hebr"],
        Script::Thai => &["und-Thai"],
        Script::Devanagari => &["und-Deva"],
        Script::Bengali => &["und-Beng"],
        Script::Tamil => &["und-Taml"],
        Script::Telugu => &["und-Telu"],
        Script::Kannada => &["und-Knda"],
        Script::Malayalam => &["und-Mlym"],
        Script::Gujarati => &["und-Gujr"],
    }
}

/// Substrings to look for in font filenames when no `lang=` family is
/// found. This handles API 21..27 fonts.xml, where most fallback
/// families have no `lang` attribute and the only signal is the Noto
/// family encoded in the filename.
fn name_keywords(script: Script) -> &'static [&'static str] {
    match script {
        Script::Cyrillic | Script::Greek => &[],
        Script::Georgian => &["NotoSansGeorgian", "NotoSerifGeorgian"],
        Script::Japanese => &["NotoSansCJK", "NotoSansJP", "DroidSansJapanese"],
        Script::Korean => &["NotoSansCJK", "NotoSansKR", "DroidSansKorean"],
        Script::HanSimplified => &["NotoSansSC", "NotoSansHans", "NotoSansCJK"],
        Script::HanTraditional => &["NotoSansTC", "NotoSansHant", "NotoSansCJK"],
        Script::Arabic => &["NotoNaskh", "NotoSansArabic", "DroidNaskh"],
        Script::Hebrew => &["NotoSansHebrew"],
        Script::Thai => &["NotoSansThai", "DroidSansThai"],
        Script::Devanagari => &["NotoSansDevanagari"],
        Script::Bengali => &["NotoSansBengali"],
        Script::Tamil => &["NotoSansTamil"],
        Script::Telugu => &["NotoSansTelugu"],
        Script::Kannada => &["NotoSansKannada"],
        Script::Malayalam => &["NotoSansMalayalam"],
        Script::Gujarati => &["NotoSansGujarati"],
    }
}

fn pick_font(family: &FamilyEntry, want_weight: u16, want_italic: bool) -> Option<&FontEntry> {
    if let Some(exact) = family
        .fonts
        .iter()
        .find(|f| f.weight == want_weight && f.italic == want_italic)
    {
        return Some(exact);
    }
    if let Some(matching_italic) = family
        .fonts
        .iter()
        .filter(|f| f.italic == want_italic)
        .min_by_key(|f| (f.weight as i32 - want_weight as i32).abs())
    {
        return Some(matching_italic);
    }
    family
        .fonts
        .iter()
        .min_by_key(|f| (f.weight as i32 - want_weight as i32).abs())
}

fn resolve(file_name: &str, ttc_index: u32) -> Option<(PathBuf, u32)> {
    let path = PathBuf::from(SYSTEM_FONTS_DIR).join(file_name);
    if !path.exists() {
        log::warn!("[font] missing system font: {}", path.display());
        return None;
    }
    Some((path, ttc_index))
}

fn locate_in_families(
    families: &[FamilyEntry],
    script: Script,
    bold: bool,
    italic: bool,
    resolve_font: impl Fn(&str, u32) -> Option<(PathBuf, u32)>,
) -> Option<(PathBuf, u32, u16)> {
    let want_weight: u16 = if bold { 700 } else { 400 };

    for want in lang_tags(script) {
        for family in families {
            if !family.langs.iter().any(|l| l == want) {
                continue;
            }
            if let Some(font) = pick_font(family, want_weight, italic)
                && let Some(r) = resolve_font(&font.file_name, font.ttc_index)
            {
                return Some((r.0, r.1, font.weight));
            }
        }
    }

    for keyword in name_keywords(script) {
        for family in families {
            if !family.fonts.iter().any(|f| f.file_name.contains(keyword)) {
                continue;
            }
            if let Some(font) = pick_font(family, want_weight, italic)
                && let Some(r) = resolve_font(&font.file_name, font.ttc_index)
            {
                return Some((r.0, r.1, font.weight));
            }
        }
    }

    for family in families {
        if family.name.as_deref() != Some("sans-serif") {
            continue;
        }
        if let Some(font) = pick_font(family, want_weight, italic)
            && let Some(r) = resolve_font(&font.file_name, font.ttc_index)
        {
            return Some((r.0, r.1, font.weight));
        }
    }

    None
}

fn locate_for(script: Script, bold: bool, italic: bool) -> Option<(PathBuf, u32, u16)> {
    locate_in_families(families(), script, bold, italic, resolve)
}

pub struct AndroidFontProvider;

impl FontProvider for AndroidFontProvider {
    fn locate(&self, request: &FontRequest) -> Vec<FontHandle> {
        let mut chain: Vec<FontHandle> = Vec::new();

        if let Some(script) = script_from_translator(request.script, &request.language)
            && let Some((path, ttc_index, weight)) = locate_for(script, request.bold, request.italic)
        {
            log::debug!(
                "[font] {:?} lang={} bold={} italic={} -> {} (ttc={} weight={})",
                request.script,
                request.language,
                request.bold,
                request.italic,
                path.display(),
                ttc_index,
                weight,
            );
            chain.push(FontHandle::new(path, ttc_index).with_weight(weight));
        }

        // Always append Roboto (sans-serif default) as a Latin/Cyrillic/Greek
        // fallback. Roboto covers Latin/Cyrillic/Greek out of the box on every
        // Android release; it's the right fallback for ASCII embedded inside
        // a non-Latin run.
        if let Some(family) = families()
            .iter()
            .find(|f| f.name.as_deref() == Some("sans-serif"))
            && let Some(font) =
                pick_font(family, if request.bold { 700 } else { 400 }, request.italic)
            && let Some((path, ttc_index)) = resolve(&font.file_name, font.ttc_index)
        {
            chain.push(FontHandle::new(path, ttc_index).with_weight(font.weight));
        }

        chain
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const FONTS_XML_API28: &str = include_str!("../testdata/fonts_api28.xml");

    const INDEX_V6: &str = include_str!("../../src/main/assets/index_v6.json");

    #[cfg_attr(feature = "ci-coverage", coverage(off))]
    fn index_languages() -> Vec<(String, String)> {
        let catalog: serde_json::Value = serde_json::from_str(INDEX_V6).expect("valid bundled catalog");
        assert_eq!(catalog["formatVersion"].as_u64(), Some(6));
        let languages = catalog["languages"].as_object().expect("catalog languages");
        assert!(!languages.is_empty(), "An empty catalog is not font coverage");
        languages.iter().map(|(code, language)| {
            (code.clone(), language["meta"]["script"].as_str().expect("catalog script").to_owned())
        }).collect()
    }

    #[cfg_attr(feature = "ci-coverage", coverage(off))]
    fn translator_script(iso: &str) -> TrScript {
        match iso {
            "Hans" | "Hant" => TrScript::Han,
            "Jpan" => TrScript::Hiragana,
            _ => TrScript::from_iso15924(iso).expect("supported catalog script"),
        }
    }

    #[cfg_attr(feature = "ci-coverage", coverage(off))]
    fn fixture_resolve(file_name: &str, ttc_index: u32) -> Option<(PathBuf, u32)> {
        Some((PathBuf::from(file_name), ttc_index))
    }

    #[cfg_attr(feature = "ci-coverage", coverage(off))]
    #[test]
    fn every_index_language_maps_correctly() {
        for (code, iso) in index_languages() {
            let mapped = non_latin_script(&code);
            let from_script = script_from_translator(translator_script(&iso), &code);
            if iso == "Latn" {
                assert!(mapped.is_none() && from_script.is_none(), "{code} must use the Latin fallback");
            } else {
                let mapped = mapped.unwrap_or_else(|| panic!("{code} ({iso}) has no language mapping"));
                let from_script = from_script.unwrap_or_else(|| panic!("{code} ({iso}) has no renderer mapping"));
                assert_eq!(std::mem::discriminant(&mapped), std::mem::discriminant(&from_script), "{code} ({iso}) routes to different fonts");
            }
        }
    }

    #[cfg_attr(feature = "ci-coverage", coverage(off))]
    #[test]
    fn every_non_latin_script_resolves_in_api28_fonts_xml() {
        let families = parse_fonts_xml(FONTS_XML_API28);
        for (code, iso) in index_languages() {
            if iso == "Latn" { continue; }
            let script = non_latin_script(&code).expect("catalog language mapping");
            // Exercise the production lookup, replacing only file existence.
            let font = locate_in_families(&families, script, false, false, fixture_resolve)
                .unwrap_or_else(|| panic!("{code} ({iso}) has no API 28 font"));
            let keywords = name_keywords(script);
            let name = font.0.to_string_lossy();
            assert!(keywords.is_empty() || keywords.iter().any(|word| name.contains(word)), "{code} ({iso}) fell back to an unrelated font: {name}");
        }
    }

    #[cfg_attr(feature = "ci-coverage", coverage(off))]
    #[test]
    fn zh_hant_routes_to_traditional_family() {
        let families = parse_fonts_xml(FONTS_XML_API28);
        let s = non_latin_script("zh_hant").expect("zh_hant maps to a script");
        assert!(matches!(s, Script::HanTraditional));
        let tag = lang_tags(s)[0];
        let family = families
            .iter()
            .find(|f| f.langs.iter().any(|l| l == tag))
            .expect("API 28 fonts.xml has a zh-Hant family");
        let font = pick_font(family, 400, false).expect("zh-Hant has a regular font");
        assert!(
            font.ttc_index >= 1,
            "zh-Hant should pick a non-zero ttc index (got {})",
            font.ttc_index
        );
    }

    #[cfg_attr(feature = "ci-coverage", coverage(off))]
    #[test]
    fn parses_default_sans_serif() {
        let families = parse_fonts_xml(FONTS_XML_API28);
        let sans = families
            .iter()
            .find(|f| f.name.as_deref() == Some("sans-serif"))
            .expect("sans-serif family present");
        assert!(
            sans.fonts
                .iter()
                .any(|f| f.file_name == "Roboto-Regular.ttf" && f.weight == 400 && !f.italic),
            "Roboto regular weight 400 normal must be parsed"
        );
        assert!(
            sans.fonts
                .iter()
                .any(|f| f.file_name == "Roboto-Bold.ttf" && f.weight == 700),
            "Roboto bold must be parsed"
        );
    }

    #[cfg_attr(feature = "ci-coverage", coverage(off))]
    #[test]
    fn parses_lang_tagged_arabic() {
        let families = parse_fonts_xml(FONTS_XML_API28);
        assert!(
            families
                .iter()
                .any(|f| f.langs.iter().any(|l| l == "und-Arab")),
            "und-Arab family must be parsed"
        );
    }

    #[cfg_attr(feature = "ci-coverage", coverage(off))]
    #[test]
    fn parses_ttc_index_for_cjk() {
        let families = parse_fonts_xml(FONTS_XML_API28);
        let zh_hans = families
            .iter()
            .find(|f| f.langs.iter().any(|l| l == "zh-Hans"))
            .expect("zh-Hans family present");
        assert!(
            zh_hans.fonts.iter().any(|f| f.ttc_index > 0),
            "zh-Hans should have a non-zero ttc index in NotoSansCJK"
        );
    }
    #[cfg_attr(feature = "ci-coverage", coverage(off))]
    fn preference_fixture() -> Vec<FamilyEntry> {
        parse_fonts_xml(r#"<familyset>
            <family name="sans-serif"><font>Roboto.ttf</font></family>
            <family><font>NotoNaskhFallback.ttf</font></family>
            <family lang="und-Arab"><font>TaggedArabic.ttf</font></family>
        </familyset>"#)
    }

    #[cfg_attr(feature = "ci-coverage", coverage(off))]
    #[test]
    fn parser_ignores_orphans_and_empty_entries_and_defaults_invalid_metadata() {
        let parsed = parse_fonts_xml(r#"<?xml version="1.0"?><familyset>
            <!-- ignored --> <font>Orphan.ttf</font>
            <family name="example" lang=" und-Arab   und-Hebr " ignored="yes">
                outside-font text
                <font weight="invalid" style="oblique" index="-3" ignored="yes"> D&#101;fault.ttf </font>
                <font>   </font><unknown>ignored</unknown>
            </family><family name="empty"></family>
        </familyset>"#);
        assert_eq!(parsed.len(), 1);
        assert_eq!(parsed[0].name.as_deref(), Some("example"));
        assert_eq!(parsed[0].langs, vec!["und-Arab".to_owned(), "und-Hebr".to_owned()]);
        assert_eq!(parsed[0].fonts.len(), 1);
        let font = &parsed[0].fonts[0];
        assert_eq!(font.file_name, "Default.ttf");
        assert_eq!(font.weight, 400);
        assert!(!font.italic);
        assert_eq!(font.ttc_index, 0);
        assert!(parse_fonts_xml("<familyset><family><font>Unfinished.ttf").is_empty());
    }

    #[cfg_attr(feature = "ci-coverage", coverage(off))]
    #[test]
    fn face_selection_prefers_exact_style_then_closest_weight_without_underflow() {
        let families = parse_fonts_xml(r#"<familyset><family>
            <font weight="400">Regular.ttf</font><font weight="700">Bold.ttf</font>
            <font weight="500" style="italic">Italic.ttf</font>
            <font weight="900" style="italic">HeavyItalic.ttf</font>
        </family></familyset>"#);
        let family = &families[0];
        assert_eq!(pick_font(family, 700, false).unwrap().file_name, "Bold.ttf");
        assert_eq!(pick_font(family, 650, false).unwrap().file_name, "Bold.ttf");
        assert_eq!(pick_font(family, 450, true).unwrap().file_name, "Italic.ttf");
        assert_eq!(pick_font(family, u16::MAX, true).unwrap().file_name, "HeavyItalic.ttf");
        let upright = FamilyEntry { fonts: family.fonts.iter().filter(|font| !font.italic).cloned().collect(), ..FamilyEntry::default() };
        assert_eq!(pick_font(&upright, 600, true).unwrap().file_name, "Bold.ttf");
        assert_eq!(pick_font(&upright, 0, true).unwrap().file_name, "Regular.ttf");
        assert!(pick_font(&FamilyEntry::default(), 400, false).is_none());
    }

    #[cfg_attr(feature = "ci-coverage", coverage(off))]
    #[test]
    fn tagged_families_take_precedence_over_filename_and_default_matches() {
        let mut families = preference_fixture();
        families.insert(0, FamilyEntry { langs: vec!["und-Arab".into()], ..FamilyEntry::default() });
        let found = locate_in_families(&families, Script::Arabic, false, false, fixture_resolve).unwrap();
        assert_eq!(found, (PathBuf::from("TaggedArabic.ttf"), 0, 400));
        assert!(locate_in_families(&[], Script::Arabic, false, false, fixture_resolve).is_none());
    }

    #[cfg_attr(feature = "ci-coverage", coverage(off))]
    #[test]
    fn missing_tagged_fonts_try_filename_then_default_and_report_no_available_font() {
        let families = preference_fixture();
        let found = locate_in_families(&families, Script::Arabic, false, false, |name, index| {
            if name == "TaggedArabic.ttf" { None } else { fixture_resolve(name, index) }
        }).unwrap();
        assert_eq!(found.0, PathBuf::from("NotoNaskhFallback.ttf"));
        let found = locate_in_families(&families, Script::Arabic, false, false, |name, index| {
            if name == "Roboto.ttf" { fixture_resolve(name, index) } else { None }
        }).unwrap();
        assert_eq!(found.0, PathBuf::from("Roboto.ttf"));
        assert!(locate_in_families(&families, Script::Arabic, true, true, |_, _| None).is_none());
    }

    #[cfg_attr(feature = "ci-coverage", coverage(off))]
    #[test]
    fn absent_font_catalogs_and_font_files_fail_without_panicking() {
        let stamp = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos();
        let name = format!("paperflow-absent-fonts-{}-{stamp}", std::process::id());
        assert!(load_families(&std::env::temp_dir().join(format!("{name}.xml"))).is_empty());
        assert!(resolve(&format!("{name}.ttf"), 0).is_none());
    }

    #[cfg_attr(feature = "ci-coverage", coverage(off))]
    #[test]
    fn language_aliases_and_translator_categories_keep_their_expected_routes() {
        for language in ["ZH_hAnT", "zh-TW", "zh-hk", "zh-MO"] {
            assert!(matches!(non_latin_script(language), Some(Script::HanTraditional)));
            assert!(matches!(script_from_translator(TrScript::Han, language), Some(Script::HanTraditional)));
        }
        for language in ["zh", "zh-CN", "zh-Hans"] {
            assert!(matches!(script_from_translator(TrScript::Han, language), Some(Script::HanSimplified)));
        }
        assert!(matches!(script_from_translator(TrScript::Katakana, "ja"), Some(Script::Japanese)));
        for script in [TrScript::Latin, TrScript::Common, TrScript::Inherited, TrScript::Other] {
            assert!(script_from_translator(script, "en").is_none());
        }
        assert!(non_latin_script("").is_none());
    }

    #[cfg(target_os = "android")]
    #[cfg_attr(feature = "ci-coverage", coverage(off))]
    #[test]
    fn every_bundled_language_style_resolves_to_real_android_font_files() {
        for (language, iso) in index_languages() {
            for bold in [false, true] { for italic in [false, true] {
                let request = FontRequest { script: translator_script(&iso), language: language.clone(), bold, italic, monospace: false };
                let fonts = AndroidFontProvider.locate(&request);
                assert!(!fonts.is_empty(), "{language} bold={bold} italic={italic} has no font");
                for font in fonts {
                    assert!(font.path.is_file(), "Missing Android font {}", font.path.display());
                    assert!(font.weight > 0);
                }
            }}
        }
    }

    #[cfg(target_os = "android")]
    #[cfg_attr(feature = "ci-coverage", coverage(off))]
    #[test]
    fn georgian_requests_use_the_available_georgian_font_before_roboto() {
        for bold in [false, true] { for italic in [false, true] {
            let request = FontRequest { script: TrScript::Georgian, language: "ka".into(), bold, italic, monospace: false };
            let fonts = AndroidFontProvider.locate(&request);
            assert!(fonts.len() >= 2, "Georgian needs its own font plus a Latin fallback");
            assert!(fonts[0].path.file_name().unwrap().to_string_lossy().contains("Georgian"));
            assert!(fonts[0].path.is_file());
            assert!(fonts.last().unwrap().path.file_name().unwrap().to_string_lossy().contains("Roboto"));
        }}
    }

}
