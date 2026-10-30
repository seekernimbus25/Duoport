#!/bin/bash
# Makes the test clips, photos, LUTs, GIF, font and transcript the emulator tests use.
set -euo pipefail
OUT=${1:-media}
mkdir -p "$OUT"
cd "$OUT"

# 12 s portrait clip: 2 s of tone, 2 s of silence, repeating (for silence removal).
ffmpeg -y -v error -f lavfi -i "testsrc2=size=1080x1920:rate=30:duration=12" \
  -f lavfi -i "aevalsrc='0.4*sin(2*PI*440*t)*lt(mod(t,4),2)':s=48000:d=12" \
  -c:v libx264 -pix_fmt yuv420p -preset veryfast -c:a aac -shortest talk.mp4

# 8 s landscape clip.
ffmpeg -y -v error -f lavfi -i "testsrc=size=1920x1080:rate=30:duration=8" \
  -f lavfi -i "sine=frequency=660:sample_rate=48000:duration=8" \
  -c:v libx264 -pix_fmt yuv420p -preset veryfast -c:a aac -shortest wide.mp4

# 6 s 10-bit HLG clip.
ffmpeg -y -v error -f lavfi -i "smptehdbars=size=1920x1080:rate=30:duration=6" \
  -f lavfi -i "sine=frequency=550:sample_rate=48000:duration=6" \
  -c:v libx265 -pix_fmt yuv420p10le -preset veryfast \
  -x265-params "colorprim=bt2020:transfer=arib-std-b67:colormatrix=bt2020nc:log-level=error" \
  -color_primaries bt2020 -color_trc arib-std-b67 -colorspace bt2020nc -tag:v hvc1 \
  -c:a aac -shortest hlg.mp4

# Background video for the design editor.
ffmpeg -y -v error -f lavfi -i "mandelbrot=size=720x1280:rate=30" -t 6 \
  -c:v libx264 -pix_fmt yuv420p -preset veryfast bg_video.mp4

python3 - <<'EOF'
from PIL import Image, ImageDraw
import json, math

# Background photo.
im = Image.new("RGB", (1080, 1920))
d = ImageDraw.Draw(im)
for y in range(1920):
    d.line([(0, y), (1080, y)], fill=(40 + y // 20, 90, 200 - y // 15))
d.ellipse([300, 500, 780, 980], fill=(250, 210, 120))
im.save("bg_photo.jpg", quality=90)

# Transparent logo.
logo = Image.new("RGBA", (400, 200), (0, 0, 0, 0))
d = ImageDraw.Draw(logo)
d.rounded_rectangle([10, 10, 390, 190], 40, fill=(216, 185, 138, 255))
d.text((120, 80), "DF TEST", fill=(20, 20, 20, 255))
logo.save("logo.png")

# Animated GIF: a bouncing dot.
frames = []
for i in range(16):
    f = Image.new("RGBA", (200, 200), (0, 0, 0, 0))
    d = ImageDraw.Draw(f)
    y = 100 + int(60 * math.sin(i / 16 * 2 * math.pi))
    d.ellipse([70, y - 30, 130, y + 30], fill=(255, 80, 120, 255))
    frames.append(f)
frames[0].save("anim.gif", save_all=True, append_images=frames[1:], duration=60, loop=0, disposal=2, transparency=0)

# A warm 17-point .cube LUT and a broken one.
n = 17
lines = ["TITLE \"warm test\"", f"LUT_3D_SIZE {n}"]
for b in range(n):
    for g in range(n):
        for r in range(n):
            R, G, B = r / (n - 1), g / (n - 1), b / (n - 1)
            lines.append(f"{min(1, R * 1.1):.6f} {G:.6f} {B * 0.85:.6f}")
open("warm_test.cube", "w").write("\n".join(lines) + "\n")
open("broken.cube", "w").write("this is not a LUT\n1 2\n")

# Fake transcript: a word every 400 ms for 12 s.
words = "Hello this is the DualFrame camera test and every word should show up in the captions right on time".split()
out, t = [], 300
while t < 11500:
    w = words[len(out) % len(words)]
    out.append({"start": t, "end": t + 350, "text": w})
    t += 400
json.dump(out, open("test_transcript.json", "w"))
EOF

cp /usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf TestFont.ttf 2>/dev/null || \
  find / -name "*.ttf" 2>/dev/null | head -1 | xargs -I{} cp {} TestFont.ttf
ls -la
