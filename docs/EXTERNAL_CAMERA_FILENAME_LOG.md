# External camera file logging (Studio)

In Filmmaking Studio, open a film project, add a scene and planned shot, then open that shot.

Under **External camera takes**, enter the first filename exactly as it appears on the
DJI, Sony, GoPro, SD card, or separate camera, for example:

- `DJI_0045.MP4` → suggests `DJI_0046.MP4`
- `DJI_20261120_0045.MP4` → suggests `DJI_20261120_0046.MP4`
- `C0001.MOV` → suggests `C0002.MOV`

The app detects the stable prefix, the final numeric run, its zero padding, and extension.
For subsequent files, type **only the number**, such as `52`; the app generates
`DJI_0052.MP4`. Leave the number blank to accept the next suggested filename.

The most recently logged camera file provides the next suggestion across the current
film's *shots and scenes*, not just inside one shot. The filename records survive app
restart, can be removed without deleting footage, and are included in the native JSON
backup and production metadata export. Duplicate filenames are rejected within a film
to prevent ambiguous relinking. Select **Different camera or naming pattern** to start
a fresh sequence with a different filename (or for cameras without a numeric suffix).

**Important:** These are filename references only. They are stored separately from
actual recorded takes and media in Room, and never count as verified footage. The
production handoff explicitly reports them as pending relink and does not claim
editorial readiness merely because a filename is logged. A future media-import and
relink workflow can use these names to find the corresponding card footage.

## Importing the actual footage (relink)

In the project's **Wrap** tab, tap **Import external camera footage** and pick the real files from
the SD card, USB card reader or Files app (several at once is fine). For each file the app:

1. matches the file name (case-insensitive) to a logged reference in this film — files that were
   never logged are listed as "matches no logged filename" and are **not** imported;
2. reads the whole file and computes its SHA-256 (a size change while reading is refused);
3. only then creates a `media` record (role master, `verification: verified`, checksum) and a
   `take` for the logged shot, and marks the reference `linked` / `footageImported: true`.

Re-picking the same bytes from a new location (card re-inserted, file moved) **relinks** the
existing media record. Picking a file with the same name but different bytes is refused as a
checksum mismatch. Bytes already imported under another name are refused as duplicates.
Read access is kept (persistable URI permission) where the document provider allows it.

This feature works offline. Sentry is unrelated to external camera filenames.
