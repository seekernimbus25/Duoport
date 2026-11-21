# Studio Android design system

The phone UI follows the web app's single dark theme (screenshot-match `src/styles.css` and `DESIGN_SYSTEM.md`):
graphite surfaces, warm ivory text, a muted amber accent.

## Where it lives

- `app/src/main/java/app/filmmaking/studio/ui/StudioTheme.kt`: colour tokens (`StudioColors`, the web's oklch values
  converted to sRGB), fonts, type scale, shapes, and the Material colour scheme built from them.
- `ui/StudioUi.kt`: shared pieces matching the web components (top bar, tabs, cards, chips, buttons, fields, sheets,
  bottom navigation, assistant button).
- `ui/Lucide.kt`: the Lucide icons the app uses, as Compose `ImageVector`s.
- `StudioMainScreens.kt`: Home, Idea Vault, Projects, project Overview and the Shots tab.

## Fonts

Bundled in `app/src/main/res/font/` (static instances from Google Fonts), each under the SIL Open Font License 1.1:

| Font | Role | Licence |
|---|---|---|
| Fraunces 400/500/600 | Titles, writing | [licenses/Fraunces-OFL.txt](licenses/Fraunces-OFL.txt) |
| Manrope 400–700 | Interface text | [licenses/Manrope-OFL.txt](licenses/Manrope-OFL.txt) |
| JetBrains Mono 400/500 | Codes, metadata, eyebrow labels | [licenses/JetBrainsMono-OFL.txt](licenses/JetBrainsMono-OFL.txt) |

## Icons

Lucide (ISC licence, [licenses/Lucide-LICENSE.txt](licenses/Lucide-LICENSE.txt)), the same set the web app uses
through `lucide-react`. `Lucide.kt` is generated from the SVG nodes in `lucide-react/dist/esm/icons/*.js`; to add an
icon, convert its nodes to a path in the same style as the existing entries (24×24 viewport, 2px round stroke).

## Screenshots

Robolectric renders the main screens at 390×844 dp with sample content, to compare against the web app:

```bash
./gradlew :app:testDebugUnitTest --tests '*StudioScreenshots*' -Dstudio.screenshots=true
```

The PNGs land in `app/build/screenshots/`. The test is skipped unless `studio.screenshots=true` is set.

## Not yet restyled

The shot editor, Account & data, AI Studio, Creative DNA, the workflow panels (Develop, Story, Visual, Planner, Shoot,
Wrap) and Shoot Mode use the new colours and fonts but still have their earlier layouts.
