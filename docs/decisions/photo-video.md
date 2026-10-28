# Decisions: photo and video cores

**PV-1. Pure Kotlin cores, platform glue in the app.** `format/photo` and `format/video` have no
Android, AWT or ImageIO dependency. The app converts `Bitmap` ⇄ `IntArray` (ARGB_8888, the layout
of `Bitmap.getPixels`) and maps the video model onto Media3. This keeps all editing logic unit
tested on the JVM in seconds.

**PV-2. Non-destructive photo edits.** The editor keeps the decoded upright source untouched and an
immutable `PhotoEditState` (geometry + adjustments). Previews and exports render from the source,
so undo/redo is just a `HistoryStack` of small states and never accumulates resampling loss.

**PV-3. Geometry order.** Flips → quarter turns → straighten → crop, all about the image centre.
The crop is stored normalised to the *visible* frame, so the crop overlay needs no conversion.
"Rotate" and "Flip" act on what the user sees: they also turn/mirror the crop and, for flips,
negate the straighten angle (a mirror reverses rotation). Tests check these operations commute
with rendering.

**PV-4. Straighten crops automatically.** The frame is scaled to the largest rectangle with the
same aspect ratio that fits inside the rotated image. Users never see empty corners and no fill
colour has to be chosen. Pixel scale is kept (no upscaling), so the output gets smaller.

**PV-5. One Android-compatible matrix.** `PhotoGeometry.matrix` returns a row-major 3×3 affine
matrix in the layout of `android.graphics.Matrix.setValues`. The app draws the source once with
bilinear filtering; `Raster` contains a reference implementation used by the tests. The crop is
centred inside the rounded output size so mirrored edits stay pixel-symmetric.

**PV-6. EXIF orientation is baked in on open.** Orientation 1–8 is applied when the photo is
decoded; the editor works on upright pixels and exports orientation 1. This avoids double rotation
in viewers that ignore or apply the tag differently.

**PV-7. Colour pipeline in integer arithmetic.** Exposure (in linear light) and contrast fold into
one 256-entry curve; highlights/shadows are a luminance gain table; saturation and warmth are
integer operations; sharpness is an unsharp mask on a 3×3 binomial blur. Every curve is monotonic
(tested) so no setting inverts tones, and neutral stages are skipped so an identity edit returns
the exact input. Rows are processed in parallel chunks. Measured on a 4-core x86 development container (JVM 21):
12 MP with every adjustment ≈0.7 s, without sharpness ≈0.3 s. Live previews run the same code on a
downscaled buffer.

**PV-8. Location is opt-in.** "Keep metadata" (default on) copies date and camera tags; GPS tags
are copied only if "Keep location" is also on, so a shared export does not reveal where a photo
was taken by accident. Maker notes and thumbnails are never copied because they point at byte
offsets of the original file.

**PV-9. Immutable timeline with deterministic ids.** Every video operation is a pure function
returning a new `Timeline`; new clip/overlay ids are the next free `cN`/`tN`. Undo/redo is a
`HistoryStack`, and tests can compare whole timelines.

**PV-10. Minimum lengths instead of slivers.** Trim, split and delete never create clips shorter
than 100 ms (a split near a boundary is a no-op; tiny remainders of a deleted range are dropped).
Very short clips decode unreliably and are impossible to select on a phone.

**PV-11. Overlays live on the timeline.** Overlay times are timeline times. Deleting content maps
them through the surviving segments (content after a cut moves left, an overlay spanning a cut
shrinks, one inside a deleted range disappears). Reordering clips leaves overlays where they are:
titles usually belong to a position (intro, ending), not to a clip.

**PV-12. Muting keeps an audio track.** A muted clip is exported with volume 0 rather than with its
audio removed, because Media3 sequences need consistent tracks across items.

**PV-13. Non-looping music is cut at the video end.** Otherwise a longer music sequence would make
Media3 extend the export beyond the last clip.

**PV-14. Hand-written JSON for the project file.** The timeline autosave is a small JSON document
written by a ~150-line writer/reader (no serialization plugin or library). Unknown keys are
ignored, newer versions are rejected with a clear error, and `TimelineStore.save` decodes the
temporary file and compares it with the in-memory timeline before atomically replacing the
project file.
