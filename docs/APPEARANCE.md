# Appearance and adaptable layouts

Settings > Appearance offers System default, Light and Dark. The default follows the device's actual night configuration. Explicit choices persist across process restarts. AppCompat recreates active activities when appearance changes; the Settings tab stays selected. App language remains independent of appearance.

The palette uses terracotta accents, warm light surfaces and dark brown surfaces. Both primary text and accent-button foregrounds meet a 4.5:1 contrast ratio in the regression checks. Image processing, PDF output and signature ink keep their original colors.

UI colors are defined in `values/colors.xml` and `values-night/colors.xml`; programmatically constructed screens use `AppTheme`. Keep the palette values synchronized (the appearance tests compare resource and programmatic colors). Use surface/background/ink/muted/accent/onAccent roles instead of hard-coded colors. Camera overlays remain on dark surfaces in either mode.

New display strings belong in `res/values/strings.xml`, including the appearance choices and explanation. No new translation was added. The translation validator automatically includes these resources when checking future language PRs. `AppLanguageTest` checks the appearance row in every registered app language.

Buttons use rounded surfaces and ripple feedback. Settings rows and tool grids measure their content instead of assuming a fixed text height. Tools use three columns on narrow screens or with larger fonts. Photo Translation language controls wrap, and its empty-state instructions scroll when the preview space is small. Existing system-bar/cutout/keyboard insets remain applied at the screen root.

`AppearanceTest` verifies saved explicit choices, actual system night-mode changes, theme/resource consistency, foreground contrast, navigation-bar icon appearance, navigation restoration and control/text bounds in light and dark modes. GitHub Actions runs the full device suite first, then repeats the appearance tests at 320dp width and 130% font size on both API 29 and API 37.0. Full-suite reports are preserved before that second run. Some API 29 Google APIs emulator images lock night mode; the test first tries the normal shell command, then uses the root shell available on those emulator images if needed. It asserts that the actual system setting changed. This is test-only and adds no permission or root requirement to the app.

## Universal APK

A build without `-PpaperflowAbi` includes `arm64-v8a`, `armeabi-v7a` and `x86_64`. Only the published-release workflow builds this universal APK plus the two separate signed ARM APKs. PR/push CI validates compilation/R8, manifest and native libraries without packaging release APKs. `check_apk.py <apk> all` checks every required native engine and ELF architecture separately for all three ABIs.
