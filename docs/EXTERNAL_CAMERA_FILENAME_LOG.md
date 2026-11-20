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

This feature works offline. Sentry is unrelated to external camera filenames.
