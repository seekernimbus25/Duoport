# Caption placement and styles

## Where captions go

Short-form apps lay their own interface over the video, so captions are kept inside a
central safe area:

| Area to avoid (1080 × 1920) | Why |
|---|---|
| Top ~14% (~270 px) | account name, "Reels"/"Following" tabs, close button |
| Bottom ~20–25% (~400–480 px) | caption text, username, music, comment box |
| Right ~12–15% (~130–160 px) | like / comment / share / remix buttons |
| Left ~5% | edge cropping on some phones |

DualFrame's defaults:

- **Tall videos (9:16, 4:5…):** captions centred about **60% down** the frame (a little
  below the middle, the most-watched spot after the face), wrapped to **72%** of the width so
  they never run to the edges or under the right-hand buttons, and at most three short rows.
- **Wide videos (16:9…):** about **70% down**, wrapped to **62%** of the width.
- Top / Middle / Bottom are available in Caption style; Bottom still stays above the bottom
  safe zone.

These values follow the commonly published Instagram Reels, TikTok and YouTube Shorts
safe-zone templates for 1080 × 1920 video. They live in `CaptionRenderer` (`SAFE_WIDTH_*`,
`AUTO_Y_*`) if they ever need tuning.

## Styles

- **Fonts:** bundled creator fonts (Montserrat, Anton, Bebas Neue, Poppins, Archivo Black,
  Bangers, Permanent Marker, Pacifico; open-source licences in `assets/licenses/fonts.txt`),
  the phone's own fonts, or any imported `.ttf` / `.otf`.
- **Colours:** text, spoken-word highlight, outline, shadow and background, each with a full
  colour picker (hue, saturation, brightness, opacity, hex) and quick picks.
- **Spoken word:** colour, box, pop (bigger), underline or none.
- **Entrances:** pop, bounce, slide up, typewriter, flicker (neon-sign), glitch (RGB split),
  shake, or still.
- **Letters:** neon glow or gradient fill.
- **Extras:** outline 1–4, soft / hard / long shadow, glow, background pill / full bar / word
  boxes, karaoke reveal, UPPERCASE, 1–4 words on screen, auto emoji (English + Hinglish
  keywords).
- **Presets:** Classic, Bold pop, Boxed, Karaoke, Hype, Neon flicker, Glitch, Typewriter,
  Sunset, One word, Handwritten, Minimal.
