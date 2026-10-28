# Photo and Video: feature status

Status of the pure-Kotlin cores (`format/photo`, `format/video`). The Android layer decodes and
encodes media and draws previews; everything listed here is computed by the cores and covered by
JVM unit tests.

Legend: **Editable** = available in the editor; **Kept** = preserved on export; **Not supported** = out of scope in this version.

## Photo (JPG, PNG, WebP)

| Feature | Status | Notes |
|---|---|---|
| Crop (free and 1:1, 4:3, 3:2, 16:9, original ratio) | Editable | Normalised to the visible frame; non-square ratios follow the frame orientation. |
| Rotate 90° left/right | Editable | The crop turns with the image. |
| Flip horizontal / vertical | Editable | Mirrors what is on screen, also after a rotation or with straighten. |
| Straighten (−45°..45°) | Editable | Automatic inscribed crop: no empty corners. |
| Exposure (−2..2 EV) | Editable | Applied in linear light. |
| Contrast | Editable | S-curve around mid-grey; negative values flatten. |
| Highlights / Shadows | Editable | Luminance-masked; pure black and white never move. |
| Saturation | Editable | Luma-preserving; −100 is greyscale. |
| Warmth | Editable | Red/blue shift strongest in mid-tones. |
| Sharpness (0..100) | Editable | Unsharp mask on a 3×3 binomial blur. |
| Filter presets | Editable | None, Natural, Vivid, Warm, Cool, Mono, Fade, Crisp, Noir; sliders stay adjustable. |
| Before/after comparison | Editable | `PhotoEditState.isIdentity` / `withoutAdjustments()`. |
| Undo/redo | Editable | Unlimited per session; a slider drag is one step. |
| EXIF orientation (values 1–8) | Kept | Applied to the pixels on open; exported files carry orientation 1. |
| EXIF camera/date metadata | Kept (optional) | Copied when "Keep metadata" is on (default). |
| GPS location | Kept (opt-in) | Copied only when "Keep location" is also on. |
| Maker notes, embedded thumbnails | Not kept | They reference offsets in the original file. |
| Export JPG / PNG / WebP, quality, size presets | Editable | Original, 4096, 2048, 1080 px long edge; never upscales. |
| Alpha channel | Kept | Untouched by every adjustment; JPEG export has no alpha. |
| Layers, masks, local adjustments, healing | Not supported | Out of scope in this version. |
| RAW, HEIF, colour profiles other than sRGB | Not supported | Pixels are treated as sRGB. |

## Video (MP4)

| Feature | Status | Notes |
|---|---|---|
| Trim clip start/end | Editable | Minimum clip length 100 ms. |
| Split at playhead | Editable | No-op within 100 ms of a clip boundary. |
| Delete segment (range, across clips) | Editable | Overlays after the range move with the content. |
| Delete clip | Editable | |
| Reorder clips | Editable | Overlays keep their timeline times. |
| Join clips (add more sources) | Editable | One video track. |
| Clip volume (0–200 %) and mute | Editable | Muted clips keep a silent audio track. |
| Background music (one audio track) | Editable | Volume, loop, start offset; non-looping music stops with the video. |
| Text overlays | Editable | Text, size, colour, bold, position + anchor, start/end time. |
| Export 1080p / 720p / vertical 9:16 | Editable | Pure `ExportPlan` mapped onto Media3 Transformer by the app. |
| Autosave / restore after process death | Editable | Project JSON written with `AtomicFile`, verified before replace. |
| Undo/redo | Editable | Unlimited per session; handle drags are one step. |
| Transitions, colour grading, keyframes, effects | Not supported | Out of scope in this version. |
| Multiple video tracks, picture-in-picture | Not supported | Single video track by design. |
