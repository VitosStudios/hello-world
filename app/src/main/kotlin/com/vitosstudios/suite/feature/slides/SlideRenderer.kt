package com.vitosstudios.suite.feature.slides

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import com.vitosstudios.suite.feature.common.ImageLoader
import com.vitosstudios.suite.pptx.EmuRect
import com.vitosstudios.suite.pptx.FillView
import com.vitosstudios.suite.pptx.LINE_BREAK
import com.vitosstudios.suite.pptx.PptxPresentation
import com.vitosstudios.suite.pptx.ShapeKind
import com.vitosstudios.suite.pptx.ShapeView
import com.vitosstudios.suite.pptx.SlideView
import com.vitosstudios.suite.pptx.TextAlign
import com.vitosstudios.suite.pptx.TextAnchor
import com.vitosstudios.suite.pptx.TextBodyView
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws a slide's read model on an Android canvas. Used for the editor view, the filmstrip,
 * presentation mode and PDF export. Locked content (charts, SmartArt, media...) is drawn as a
 * labelled placeholder so the user always sees that it exists.
 */
class SlideRenderer(private val pres: PptxPresentation) {
    private val slideW = pres.slideSize.first.toFloat()
    private val slideH = pres.slideSize.second.toFloat()

    val aspect: Float get() = slideW / slideH

    /** Draws [slide] into a [width] x width/aspect area at the canvas origin. */
    fun draw(canvas: Canvas, slide: SlideView, width: Float, imageMaxEdge: Int = 1600) {
        val s = width / slideW
        val area = RectF(0f, 0f, width, slideH * s)
        canvas.save()
        canvas.clipRect(area)
        drawFill(canvas, slide.background, area, Paint(Paint.ANTI_ALIAS_FLAG), imageMaxEdge, defaultArgb = 0xFFFFFFFF.toInt())
        for (shape in slide.shapes) drawShape(canvas, shape, s, imageMaxEdge)
        canvas.restore()
    }

    private fun rect(b: EmuRect, s: Float) = RectF(b.x * s, b.y * s, (b.x + b.cx) * s, (b.y + b.cy) * s)

    private fun drawShape(canvas: Canvas, shape: ShapeView, s: Float, maxEdge: Int) {
        if (shape.hidden) return
        val r = rect(shape.bounds, s)
        canvas.save()
        if (shape.rotation != 0.0 || shape.flipH || shape.flipV) {
            canvas.translate(r.centerX(), r.centerY())
            if (shape.rotation != 0.0) canvas.rotate(shape.rotation.toFloat())
            canvas.scale(if (shape.flipH) -1f else 1f, if (shape.flipV) -1f else 1f)
            canvas.translate(-r.centerX(), -r.centerY())
        }
        when (shape.kind) {
            ShapeKind.GROUP -> shape.children.forEach { drawShape(canvas, it, s, maxEdge) }
            ShapeKind.PICTURE -> drawPicture(canvas, shape, r, maxEdge)
            ShapeKind.TABLE -> drawTable(canvas, shape, r, s)
            ShapeKind.CHART, ShapeKind.SMARTART, ShapeKind.OLE, ShapeKind.MEDIA, ShapeKind.INK, ShapeKind.OTHER -> {
                if (shape.image != null) drawPicture(canvas, shape, r, maxEdge) else drawPlaceholder(canvas, r, label(shape), s)
            }
            else -> {
                drawGeometry(canvas, shape, r, s, maxEdge)
                shape.text?.let { drawText(canvas, it, r, s) }
            }
        }
        canvas.restore()
    }

    private fun label(shape: ShapeView): String = when (shape.kind) {
        ShapeKind.CHART -> "Chart"
        ShapeKind.SMARTART -> "SmartArt"
        ShapeKind.OLE -> "Embedded object"
        ShapeKind.MEDIA -> "Audio or video"
        ShapeKind.INK -> "Ink"
        else -> shape.lockReason ?: "Content"
    }

    private fun drawPlaceholder(canvas: Canvas, r: RectF, label: String, s: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000; style = Paint.Style.FILL }
        canvas.drawRect(r, p)
        p.apply { color = 0x88000000.toInt(); style = Paint.Style.STROKE; strokeWidth = maxOf(1f, 12700f * 1.5f * s); pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 6f), 0f) }
        canvas.drawRect(r, p)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt(); textSize = maxOf(8f, min(r.height() / 6, 14 * 12700f * s)) }
        val text = "🔒 $label (kept unchanged)"
        canvas.drawText(text, r.left + tp.textSize / 2, r.centerY() + tp.textSize / 3, tp)
    }

    private fun geometryPath(shape: ShapeView, r: RectF): Path? {
        val path = Path()
        when (shape.geometry) {
            null -> return if (shape.kind == ShapeKind.TEXT_BOX) Path().apply { addRect(r, Path.Direction.CW) } else null
            "rect", "custom", "flowChartProcess", "frame" -> path.addRect(r, Path.Direction.CW)
            "roundRect", "flowChartAlternateProcess" -> path.addRoundRect(r, min(r.width(), r.height()) * 0.17f, min(r.width(), r.height()) * 0.17f, Path.Direction.CW)
            "ellipse", "flowChartConnector" -> path.addOval(r, Path.Direction.CW)
            "triangle", "isoTriangle" -> { path.moveTo(r.centerX(), r.top); path.lineTo(r.right, r.bottom); path.lineTo(r.left, r.bottom); path.close() }
            "rtTriangle" -> { path.moveTo(r.left, r.top); path.lineTo(r.right, r.bottom); path.lineTo(r.left, r.bottom); path.close() }
            "diamond", "flowChartDecision" -> { path.moveTo(r.centerX(), r.top); path.lineTo(r.right, r.centerY()); path.lineTo(r.centerX(), r.bottom); path.lineTo(r.left, r.centerY()); path.close() }
            "rightArrow" -> arrow(path, r, right = true)
            "leftArrow" -> arrow(path, r, right = false)
            "line", "straightConnector1", "bentConnector2", "bentConnector3", "curvedConnector3" -> { path.moveTo(r.left, r.top); path.lineTo(r.right, r.bottom) }
            "hexagon" -> {
                val d = r.width() * 0.25f
                path.moveTo(r.left + d, r.top); path.lineTo(r.right - d, r.top); path.lineTo(r.right, r.centerY())
                path.lineTo(r.right - d, r.bottom); path.lineTo(r.left + d, r.bottom); path.lineTo(r.left, r.centerY()); path.close()
            }
            "chevron", "homePlate" -> {
                val d = min(r.width() * 0.4f, r.height() * 0.5f)
                path.moveTo(r.left, r.top); path.lineTo(r.right - d, r.top); path.lineTo(r.right, r.centerY())
                path.lineTo(r.right - d, r.bottom); path.lineTo(r.left, r.bottom)
                if (shape.geometry == "chevron") path.lineTo(r.left + d, r.centerY())
                path.close()
            }
            "star5" -> star(path, r, 5)
            else -> path.addRect(r, Path.Direction.CW)
        }
        return path
    }

    private fun arrow(path: Path, r: RectF, right: Boolean) {
        val head = min(r.width() * 0.4f, r.height())
        val shaftTop = r.top + r.height() * 0.25f
        val shaftBottom = r.bottom - r.height() * 0.25f
        if (right) {
            path.moveTo(r.left, shaftTop); path.lineTo(r.right - head, shaftTop); path.lineTo(r.right - head, r.top)
            path.lineTo(r.right, r.centerY()); path.lineTo(r.right - head, r.bottom); path.lineTo(r.right - head, shaftBottom)
            path.lineTo(r.left, shaftBottom)
        } else {
            path.moveTo(r.right, shaftTop); path.lineTo(r.left + head, shaftTop); path.lineTo(r.left + head, r.top)
            path.lineTo(r.left, r.centerY()); path.lineTo(r.left + head, r.bottom); path.lineTo(r.left + head, shaftBottom)
            path.lineTo(r.right, shaftBottom)
        }
        path.close()
    }

    private fun star(path: Path, r: RectF, points: Int) {
        val cx = r.centerX(); val cy = r.centerY()
        val rx = r.width() / 2; val ry = r.height() / 2
        for (i in 0 until points * 2) {
            val a = Math.PI * i / points - Math.PI / 2
            val f = if (i % 2 == 0) 1f else 0.4f
            val x = cx + (cos(a) * rx * f).toFloat()
            val y = cy + (sin(a) * ry * f).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    private fun drawGeometry(canvas: Canvas, shape: ShapeView, r: RectF, s: Float, maxEdge: Int) {
        val path = geometryPath(shape, r) ?: return
        val isLine = shape.kind == ShapeKind.CONNECTOR || shape.geometry in setOf("line", "straightConnector1", "bentConnector2", "bentConnector3", "curvedConnector3")
        if (!isLine) {
            canvas.save()
            canvas.clipPath(path)
            drawFill(canvas, shape.fill, r, Paint(Paint.ANTI_ALIAS_FLAG), maxEdge, defaultArgb = null)
            canvas.restore()
        }
        val line = shape.line
        if (line?.argb != null) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = line.argb!!
                strokeWidth = maxOf(1f, line.widthEmu * s)
                if (line.dash != null && line.dash != "solid") pathEffect = android.graphics.DashPathEffect(floatArrayOf(strokeWidth * 3, strokeWidth * 2), 0f)
            }
            canvas.drawPath(path, p)
            if (isLine && shape.geometry == "straightConnector1") {
                // Arrow head at the end of connectors drawn by "Insert arrow".
            }
        }
    }

    private fun drawFill(canvas: Canvas, fill: FillView, r: RectF, paint: Paint, maxEdge: Int, defaultArgb: Int?) {
        when (fill) {
            is FillView.Solid -> { paint.color = fill.argb; canvas.drawRect(r, paint) }
            is FillView.Gradient -> {
                if (fill.stops.size >= 2) {
                    val a = Math.toRadians(fill.angleDegrees)
                    val dx = (cos(a) * r.width() / 2).toFloat()
                    val dy = (sin(a) * r.height() / 2).toFloat()
                    paint.shader = LinearGradient(
                        r.centerX() - dx, r.centerY() - dy, r.centerX() + dx, r.centerY() + dy,
                        fill.stops.map { it.second }.toIntArray(), fill.stops.map { it.first.toFloat() }.toFloatArray(), Shader.TileMode.CLAMP,
                    )
                    canvas.drawRect(r, paint)
                    paint.shader = null
                } else fill.stops.firstOrNull()?.let { paint.color = it.second; canvas.drawRect(r, paint) }
            }
            is FillView.Image -> bitmap(fill.partName, maxEdge)?.let { canvas.drawBitmap(it, null, r, Paint(Paint.FILTER_BITMAP_FLAG)) }
            is FillView.Unsupported -> fill.fallbackArgb?.let { paint.color = it; canvas.drawRect(r, paint) }
            FillView.None -> defaultArgb?.let { paint.color = it; canvas.drawRect(r, paint) }
        }
    }

    private fun bitmap(partName: String, maxEdge: Int): Bitmap? = ImageLoader.cached(partName, maxEdge) { pres.partBytes(partName) }

    private fun drawPicture(canvas: Canvas, shape: ShapeView, r: RectF, maxEdge: Int) {
        val img = shape.image ?: return
        val bmp = bitmap(img.partName, maxEdge)
        if (bmp == null) {
            drawPlaceholder(canvas, r, "Picture", 1f)
            return
        }
        val src = Rect(
            (bmp.width * img.cropLeft).toInt(), (bmp.height * img.cropTop).toInt(),
            (bmp.width * (1 - img.cropRight)).toInt(), (bmp.height * (1 - img.cropBottom)).toInt(),
        )
        canvas.drawBitmap(bmp, src, r, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }

    private fun drawTable(canvas: Canvas, shape: ShapeView, r: RectF, s: Float) {
        val t = shape.table ?: return drawPlaceholder(canvas, r, "Table", s)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF888888.toInt(); strokeWidth = 1f }
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF222222.toInt(); textSize = maxOf(6f, 12 * 12700f * s) }
        var y = r.top
        t.rows.forEachIndexed { ri, row ->
            val h = (t.rowHeights.getOrNull(ri) ?: (shape.bounds.cy / maxOf(1, t.rows.size))) * s
            var x = r.left
            row.forEachIndexed { ci, text ->
                val w = (t.columnWidths.getOrNull(ci) ?: (shape.bounds.cx / maxOf(1, row.size))) * s
                canvas.drawRect(x, y, x + w, y + h, line)
                canvas.save()
                canvas.clipRect(x, y, x + w, y + h)
                canvas.drawText(text, x + 4, y + tp.textSize + 2, tp)
                canvas.restore()
                x += w
            }
            y += h
        }
    }

    private fun drawText(canvas: Canvas, body: TextBodyView, r: RectF, s: Float) {
        if (body.paragraphs.all { it.text.isEmpty() }) return
        val left = r.left + body.insetLeft * s
        val right = r.right - body.insetRight * s
        val width = (right - left).coerceAtLeast(4f)
        val sb = SpannableStringBuilder()
        val scale = body.fontScale.toFloat()
        val ptToPx = 12700f * s
        body.paragraphs.forEachIndexed { pi, p ->
            val start = sb.length
            val bulletLabel = p.bullet.label
            if (bulletLabel.isNotEmpty() && p.text.isNotEmpty()) {
                val bs = sb.length
                sb.append(bulletLabel).append(' ')
                p.bullet.argb?.let { sb.setSpan(ForegroundColorSpan(it), bs, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                val size = ((p.runs.firstOrNull()?.sizePt ?: p.endSizePt) * p.bullet.sizeRatio * scale * ptToPx).toInt().coerceAtLeast(1)
                sb.setSpan(AbsoluteSizeSpan(size), bs, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val textStart = sb.length
            sb.append(p.text.replace(LINE_BREAK, '\n'))
            for (run in p.runs) {
                val a = textStart + run.start
                val e = textStart + run.end
                if (a >= e || e > sb.length) continue
                fun set(span: Any) = sb.setSpan(span, a, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                set(AbsoluteSizeSpan((run.sizePt * scale * ptToPx).toInt().coerceAtLeast(1)))
                set(ForegroundColorSpan(run.argb))
                when {
                    run.bold && run.italic -> set(StyleSpan(Typeface.BOLD_ITALIC))
                    run.bold -> set(StyleSpan(Typeface.BOLD))
                    run.italic -> set(StyleSpan(Typeface.ITALIC))
                }
                if (run.underline) set(UnderlineSpan())
                if (run.strike) set(StrikethroughSpan())
                val fam = run.typeface.lowercase()
                set(TypefaceSpan(when {
                    "mono" in fam || "courier" in fam || "consolas" in fam -> "monospace"
                    ("serif" in fam && "sans" !in fam) || "times" in fam || "georgia" in fam || "cambria" in fam -> "serif"
                    else -> "sans-serif"
                }))
            }
            if (p.text.isEmpty()) {
                // Empty paragraph keeps its line height.
                sb.append(' ')
                sb.setSpan(AbsoluteSizeSpan((p.endSizePt * scale * ptToPx).toInt().coerceAtLeast(1)), sb.length - 1, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (pi < body.paragraphs.lastIndex) sb.append('\n')
            val end = sb.length
            val align = when (p.alignment) {
                TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
                TextAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_NORMAL
            }
            sb.setSpan(AlignmentSpan.Standard(align), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            val margin = (p.marginLeftEmu * s).toInt().coerceAtLeast(0)
            val first = (margin + p.indentEmu * s).toInt().coerceAtLeast(0)
            if (margin > 0 || first > 0) sb.setSpan(LeadingMarginSpan.Standard(first, margin), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18 * ptToPx; color = 0xFF000000.toInt() }
        val spacing = body.paragraphs.firstOrNull()?.lineSpacing?.multiple?.toFloat()?.coerceIn(0.5f, 3f) ?: 1f
        val layout = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, width.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, spacing * 0.95f)
            .setIncludePad(false)
            .build()
        val top = r.top + body.insetTop * s
        val bottom = r.bottom - body.insetBottom * s
        val available = bottom - top
        val y = when (body.anchor) {
            TextAnchor.TOP -> top
            TextAnchor.MIDDLE -> top + (available - layout.height) / 2
            TextAnchor.BOTTOM -> bottom - layout.height
        }
        canvas.save()
        canvas.translate(left, y)
        layout.draw(canvas)
        canvas.restore()
    }
}
