# PaperFlow icon

Original PaperFlow vector artwork: warm terracotta, cream paper and a curved text stroke suggesting flow. No text is embedded in the launcher icon.

- `paperflow-icon.svg`: scalable color source, using the central visible adaptive viewport.
- `paperflow-icon-monochrome.svg`: themed-icon silhouette with transparent text/fold details.
- `paperflow-play-store.png`: 512 x 512 RGB, square full-bleed store artwork.
- Android foreground/background/monochrome vectors live in `app/src/main/res/drawable`.
- Adaptive launcher resources use the Android launcher mask; five legacy densities include both rounded-square and circular WebP versions.

To regenerate, install developer-only `Pillow` and `aggdraw`, then run:

```text
python tools/branding/generate_icons.py
```

The generator also writes local color and sample themed previews to `artifacts/branding`. The sample themed colors are illustrative; Android chooses the actual themed colors. Artwork follows the project's license.
