package com.spielgrund.glitchr.effects

import java.awt.BasicStroke
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * An own symbol, pasted in: an outline read from SVG, or the coverage of a black-and-white
 * picture. Stored as text in a [Param.Icon] – "svg:" and the SVG code, or "pic:" and the
 * picture as base64 PNG; which of its parts make the symbol is a [Look].
 */
internal class CustomIcon private constructor(private val shape: Shape?, private val mask: BufferedImage?) {
    /** Width to height. */
    val aspect: Double = if (shape != null) shape.bounds2D.let { it.width / max(1e-9, it.height) } else mask!!.width / mask.height.toDouble()

    /** Fills the icon in [g]'s color – white – fitted into a [size] square around ([cx], [cy]), keeping its proportions. */
    fun fill(g: Graphics2D, cx: Double, cy: Double, size: Double) {
        val w = if (aspect >= 1) size else size * aspect
        val h = if (aspect >= 1) size / aspect else size
        if (shape != null) {
            val b = shape.bounds2D
            val t = AffineTransform()
            t.translate(cx - w / 2, cy - h / 2)
            t.scale(w / max(1e-9, b.width), h / max(1e-9, b.height))
            t.translate(-b.x, -b.y)
            g.fill(t.createTransformedShape(shape))
        } else {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.drawImage(mask, (cx - w / 2).toInt(), (cy - h / 2).toInt(), w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), null)
        }
    }

    /**
     * How a pasted picture becomes the symbol: which parts ([mode] 0 auto, 1 dark, 2 light,
     * 3 opaque), its [contrast] and [brightness] before that (−1..2, −1..1), and how much wider
     * or narrower the lines get ([width], −1..1: up to 4 % of the icon's size), and how much the
     * outline is smoothed before ([smooth], 0..1: up to 4 % of the size). The width also works on
     * SVG icons.
     */
    data class Look(val mode: Int = 0, val contrast: Double = 0.0, val brightness: Double = 0.0, val width: Double = 0.0, val smooth: Double = 0.0)

    companion object {
        /** The last few icons, by their text and look. */
        private val cache = object : LinkedHashMap<Pair<String, Look>, CustomIcon?>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Look>, CustomIcon?>) = size > 6
        }

        /** The icon stored in [text], shaped by [look]; null if there is none, it can't be read or nothing is left of it. */
        @Synchronized
        fun decode(text: String, look: Look = Look()): CustomIcon? {
            if (text.isBlank()) return null
            val key = text to look
            if (cache.containsKey(key)) return cache[key]
            val icon = try {
                when {
                    text.startsWith("svg:") -> svgShape(text.substring(4))?.let { widen(it, look.width) }?.let { CustomIcon(it, null) }
                    text.startsWith("pic:") -> ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(text.substring(4))))?.let { mask(it, look) }?.let { CustomIcon(null, it) }
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
            cache[key] = icon
            return icon
        }

        /** SVG code as icon text, or null if no filled shape could be read from it. */
        fun fromSvg(svg: String): String? = if (svgShape(svg) != null) "svg:" + svg.trim() else null

        /**
         * A picture as icon text: the picture itself, at most 1024 pixels wide or high – which parts
         * of it make the symbol is decided when drawing (see [Look]). Null for an empty picture.
         */
        fun fromImage(image: BufferedImage): String? {
            if (image.width < 1 || image.height < 1) return null
            val k = min(1.0, 1024.0 / max(image.width, image.height))
            val w = max(1, (image.width * k).toInt())
            val h = max(1, (image.height * k).toInt())
            val stored = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            stored.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                drawImage(if (k < 1) image.getScaledInstance(w, h, java.awt.Image.SCALE_AREA_AVERAGING) else image, 0, 0, null)
                dispose()
            }
            val bytes = ByteArrayOutputStream().also { ImageIO.write(stored, "png", it) }.toByteArray()
            return "pic:" + Base64.getEncoder().encodeToString(bytes)
        }

        /** An SVG outline grown (or shrunk) by [width] – up to 4 % of its size to each side. */
        private fun widen(shape: Shape, width: Double): Shape {
            if (width == 0.0) return shape
            val b = shape.bounds2D
            val r = abs(width) * 0.04 * max(b.width, b.height)
            val rim = Area(BasicStroke((2 * r).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(shape))
            return Area(shape).apply { if (width > 0) add(rim) else subtract(rim) }
        }

        /**
         * Which parts of [image] make the symbol, as white with alpha: brightness and contrast
         * first, then the dark parts, the light ones or the opaque ones (auto: opaque ones of a
         * transparent picture, otherwise whatever differs from the border's brightness); lines
         * widened or narrowed, then cut to what is covered. Null if nothing is.
         */
        private fun mask(original: BufferedImage, look: Look): BufferedImage? {
            // small icons first smoothly enlarged, so their outlines come out round, not stepped
            val up = if (max(original.width, original.height) >= 600) 1 else kotlin.math.ceil(600.0 / max(original.width, original.height)).toInt()
            val image = if (up == 1) original else BufferedImage(original.width * up, original.height * up, BufferedImage.TYPE_INT_ARGB).also { big ->
                big.createGraphics().apply {
                    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    drawImage(original, 0, 0, big.width, big.height, null)
                    dispose()
                }
            }
            val w = image.width
            val h = image.height
            val px = IntArray(w * h)
            image.getRGB(0, 0, w, h, px, 0, w)
            fun luma(c: Int) = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 255000.0
            val gain = if (look.contrast >= 0) 1 + look.contrast * 4 else 1 + look.contrast
            // brightness moves the threshold, contrast sets how hard the edge around it is
            fun tone(v: Double) = ((v - 0.5 + look.brightness) * gain + 0.5).coerceIn(0.0, 1.0)
            // the border's brightness: the background, for telling the symbol apart
            var border = 0.0
            var n = 0
            for (x in 0 until w) { border += luma(px[x]); border += luma(px[(h - 1) * w + x]); n += 2 }
            for (y in 0 until h) { border += luma(px[y * w]); border += luma(px[y * w + w - 1]); n += 2 }
            border /= n
            val transparent = px.count { it ushr 24 < 250 } > px.size / 50
            val mode = if (look.mode == 0 && transparent) 3 else look.mode
            // how much each pixel is of the kind wanted, before the threshold
            val value = FloatArray(w * h) { i ->
                val c = px[i]
                when (mode) {
                    1 -> 1 - luma(c)
                    2 -> luma(c)
                    3 -> (c ushr 24) / 255.0
                    // auto: whatever differs from the background, lighter or darker
                    else -> abs(luma(c) - border) / max(border, 1 - border)
                }.toFloat()
            }
            val opacity = FloatArray(w * h) { (px[it] ushr 24) / 255f }
            // smoothed before the threshold: the steps of enlarged pixels always, more as wanted –
            // the outline comes out round instead of wavy
            val sigma = (if (up > 1) up * 0.4 else 0.0) + look.smooth * 0.04 * max(w, h)
            val soft = if (sigma >= 0.5) Blur.gauss(value, w, h, sigma) else value
            val softOpacity = if (sigma >= 0.5 && mode != 3) Blur.gauss(opacity, w, h, sigma) else opacity
            var cover = DoubleArray(w * h) { i ->
                if (mode == 3) tone(soft[i].toDouble()) else tone(soft[i].toDouble()) * softOpacity[i]
            }
            // wider or narrower lines: by the distance to the edge, so curves stay round
            val r = look.width * 0.04 * max(w, h)
            if (abs(r) >= 0.5) {
                val grow = r > 0
                // growing: how far from the covered parts; shrinking: how far from the uncovered ones
                val d = distance(BooleanArray(w * h) { (cover[it] >= 0.5) == grow }, w, h)
                cover = DoubleArray(w * h) { i -> if (grow) (abs(r) + 0.5 - d[i]).coerceIn(0.0, 1.0) else (d[i] - abs(r) + 0.5).coerceIn(0.0, 1.0) }
            }
            // cut to the covered part
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
            for (y in 0 until h) for (x in 0 until w) if (cover[y * w + x] > 0.05) {
                x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y)
            }
            if (x1 < 0) return null
            val out = BufferedImage(x1 - x0 + 1, y1 - y0 + 1, BufferedImage.TYPE_INT_ARGB)
            for (y in y0..y1) for (x in x0..x1) out.setRGB(x - x0, y - y0, ((cover[y * w + x] * 255 + 0.5).toInt() shl 24) or 0xFFFFFF)
            return out
        }

        /**
         * How far every pixel lies from the nearest [source] pixel (0 on them), in pixels – a
         * two-pass chamfer distance, close enough to the true one.
         */
        private fun distance(source: BooleanArray, w: Int, h: Int): FloatArray {
            val d = FloatArray(w * h) { if (source[it]) 0f else 1e9f }
            val diag = 1.4142135f
            for (y in 0 until h) for (x in 0 until w) {
                val j = y * w + x
                if (d[j] == 0f) continue
                var m = d[j]
                if (x > 0) m = min(m, d[j - 1] + 1)
                if (y > 0) {
                    m = min(m, d[j - w] + 1)
                    if (x > 0) m = min(m, d[j - w - 1] + diag)
                    if (x < w - 1) m = min(m, d[j - w + 1] + diag)
                }
                d[j] = m
            }
            for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
                val j = y * w + x
                if (d[j] == 0f) continue
                var m = d[j]
                if (x < w - 1) m = min(m, d[j + 1] + 1)
                if (y < h - 1) {
                    m = min(m, d[j + w] + 1)
                    if (x < w - 1) m = min(m, d[j + w + 1] + diag)
                    if (x > 0) m = min(m, d[j + w - 1] + diag)
                }
                d[j] = m
            }
            return d
        }

        // ---- SVG ----

        /**
         * The filled outline of an SVG drawing: paths, rectangles, circles, ellipses, polygons
         * and lines, with group transforms, inline styles and simple CSS classes. Dark fills add
         * to the shape, light ones (white) cut it out; strokes count with their width.
         */
        fun svgShape(svg: String): Shape? {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                // never fetch anything from outside
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                isExpandEntityReferences = false
            }
            val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(svg.trim().toByteArray(Charsets.UTF_8)))
            val root = doc.documentElement ?: return null
            // simple CSS: ".a, .b { fill: #fff; }"
            val classes = HashMap<String, Map<String, String>>()
            val styles = doc.getElementsByTagName("style")
            for (i in 0 until styles.length) {
                Regex("""([^{}]+)\{([^}]*)\}""").findAll(styles.item(i).textContent).forEach { m ->
                    val decl = declarations(m.groupValues[2])
                    for (sel in m.groupValues[1].split(',')) {
                        val name = sel.trim()
                        if (name.startsWith(".")) classes[name.substring(1)] = (classes[name.substring(1)] ?: emptyMap()) + decl
                    }
                }
            }
            val area = Area()
            walk(root, AffineTransform(), emptyMap(), classes, area)
            return if (area.isEmpty) null else area
        }

        private fun declarations(css: String): Map<String, String> = css.split(';').mapNotNull { d ->
            val k = d.indexOf(':')
            if (k < 0) null else d.substring(0, k).trim().lowercase() to d.substring(k + 1).trim()
        }.toMap()

        private val skipped = setOf("defs", "clippath", "mask", "symbol", "title", "desc", "metadata", "style", "pattern", "lineargradient", "radialgradient", "filter", "marker", "text", "image", "script")
        private val inherited = setOf("fill", "fill-rule", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin", "display", "visibility")

        private fun walk(node: org.w3c.dom.Element, parent: AffineTransform, inheritedStyle: Map<String, String>, classes: Map<String, Map<String, String>>, area: Area) {
            val tag = node.tagName.substringAfter(':').lowercase()
            if (tag in skipped) return
            // the element's own style: inherited, then attributes, classes, inline style
            val style = HashMap(inheritedStyle.filterKeys { it in inherited })
            for (name in listOf("fill", "fill-rule", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin", "display", "visibility", "opacity", "fill-opacity")) {
                node.getAttribute(name).takeIf { it.isNotBlank() }?.let { style[name] = it.trim() }
            }
            for (c in node.getAttribute("class").split(' ', '\t').filter { it.isNotBlank() }) classes[c]?.let { style.putAll(it) }
            style.putAll(declarations(node.getAttribute("style")))
            if (style["display"] == "none" || style["visibility"] == "hidden") return
            val t = AffineTransform(parent)
            node.getAttribute("transform").takeIf { it.isNotBlank() }?.let { t.concatenate(transform(it)) }
            if (tag == "svg" || tag == "g" || tag == "a" || tag == "switch") {
                val kids = node.childNodes
                for (i in 0 until kids.length) (kids.item(i) as? org.w3c.dom.Element)?.let { walk(it, t, style, classes, area) }
                return
            }
            fun num(name: String) = node.getAttribute(name).trim().removeSuffix("px").toDoubleOrNull() ?: 0.0
            val geometry: Shape = when (tag) {
                "path" -> path(node.getAttribute("d"), style["fill-rule"] == "evenodd")
                "rect" -> {
                    var rx = num("rx"); var ry = num("ry")
                    if (rx == 0.0) rx = ry
                    if (ry == 0.0) ry = rx
                    if (rx > 0) RoundRectangle2D.Double(num("x"), num("y"), num("width"), num("height"), 2 * rx, 2 * ry)
                    else Rectangle2D.Double(num("x"), num("y"), num("width"), num("height"))
                }
                "circle" -> num("r").let { r -> Ellipse2D.Double(num("cx") - r, num("cy") - r, 2 * r, 2 * r) }
                "ellipse" -> Ellipse2D.Double(num("cx") - num("rx"), num("cy") - num("ry"), 2 * num("rx"), 2 * num("ry"))
                "line" -> Line2D.Double(num("x1"), num("y1"), num("x2"), num("y2"))
                "polygon", "polyline" -> {
                    val v = numbers(node.getAttribute("points"))
                    Path2D.Double(if (style["fill-rule"] == "evenodd") Path2D.WIND_EVEN_ODD else Path2D.WIND_NON_ZERO).apply {
                        for (i in 0 until v.size / 2) if (i == 0) moveTo(v[0], v[1]) else lineTo(v[2 * i], v[2 * i + 1])
                        if (tag == "polygon") closePath()
                    }
                }
                else -> return
            }
            if (style["opacity"]?.toDoubleOrNull() == 0.0 || style["fill-opacity"]?.toDoubleOrNull() == 0.0 && style["stroke"].isNullOrBlank()) return
            val fill = style["fill"] ?: "black"
            if (fill != "none" && tag != "line") {
                val part = Area(t.createTransformedShape(geometry))
                if (light(fill)) area.subtract(part) else area.add(part)
            }
            val stroke = style["stroke"]
            val width = style["stroke-width"]?.removeSuffix("px")?.toDoubleOrNull() ?: 1.0
            if (stroke != null && stroke != "none" && width > 0) {
                val cap = when (style["stroke-linecap"]) { "round" -> BasicStroke.CAP_ROUND; "square" -> BasicStroke.CAP_SQUARE; else -> BasicStroke.CAP_BUTT }
                val join = when (style["stroke-linejoin"]) { "round" -> BasicStroke.JOIN_ROUND; "bevel" -> BasicStroke.JOIN_BEVEL; else -> BasicStroke.JOIN_MITER }
                val part = Area(t.createTransformedShape(BasicStroke(width.toFloat(), cap, join).createStrokedShape(geometry)))
                if (light(stroke)) area.subtract(part) else area.add(part)
            }
        }

        /** Whether an SVG color is light – white and the like cut out of the symbol. */
        private fun light(color: String): Boolean {
            val c = color.trim().lowercase()
            val rgb = when {
                c == "white" -> 0xFFFFFF
                c.startsWith("#") && c.length == 4 -> c.substring(1).map { "$it$it" }.joinToString("").toIntOrNull(16)
                c.startsWith("#") && c.length >= 7 -> c.substring(1, 7).toIntOrNull(16)
                c.startsWith("rgb") -> numbers(c.substringAfter('(').substringBefore(')')).let { v ->
                    if (v.size >= 3) (v[0].toInt().coerceIn(0, 255) shl 16) or (v[1].toInt().coerceIn(0, 255) shl 8) or v[2].toInt().coerceIn(0, 255) else null
                }
                else -> null
            } ?: return false
            return ((rgb shr 16 and 0xFF) * 299 + (rgb shr 8 and 0xFF) * 587 + (rgb and 0xFF) * 114) / 1000 > 160
        }

        private val numberPattern = Regex("""[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?""")
        private fun numbers(s: String) = numberPattern.findAll(s).map { it.value.toDouble() }.toList()

        /** An SVG transform list, applied right to left as in SVG. */
        private fun transform(s: String): AffineTransform {
            val t = AffineTransform()
            Regex("""(\w+)\s*\(([^)]*)\)""").findAll(s).forEach { m ->
                val v = numbers(m.groupValues[2])
                fun at(i: Int, d: Double = 0.0) = v.getOrElse(i) { d }
                val step = when (m.groupValues[1].lowercase()) {
                    "matrix" -> AffineTransform(at(0, 1.0), at(1), at(2), at(3, 1.0), at(4), at(5))
                    "translate" -> AffineTransform.getTranslateInstance(at(0), at(1))
                    "scale" -> AffineTransform.getScaleInstance(at(0, 1.0), v.getOrElse(1) { at(0, 1.0) })
                    "rotate" -> AffineTransform.getRotateInstance(Math.toRadians(at(0)), at(1), at(2))
                    "skewx" -> AffineTransform.getShearInstance(tan(Math.toRadians(at(0))), 0.0)
                    "skewy" -> AffineTransform.getShearInstance(0.0, tan(Math.toRadians(at(0))))
                    else -> AffineTransform()
                }
                t.concatenate(step)
            }
            return t
        }

        /** The path data of an SVG path: every command, absolute and relative, arcs included. */
        private fun path(d: String, evenOdd: Boolean): Path2D.Double {
            val p = Path2D.Double(if (evenOdd) Path2D.WIND_EVEN_ODD else Path2D.WIND_NON_ZERO)
            var i = 0
            fun skip() { while (i < d.length && (d[i].isWhitespace() || d[i] == ',')) i++ }
            fun number(): Double? {
                skip()
                val m = numberPattern.matchAt(d, i) ?: return null
                i = m.range.last + 1
                return m.value.toDouble()
            }
            fun flag(): Double? {
                skip()
                if (i < d.length && (d[i] == '0' || d[i] == '1')) return (d[i++] - '0').toDouble()
                return null
            }
            var x = 0.0; var y = 0.0
            var sx = 0.0; var sy = 0.0
            // the last control point, for the smooth curves
            var cx = 0.0; var cy = 0.0
            var cmd = ' '
            var open = false
            while (true) {
                skip()
                if (i >= d.length) break
                if (d[i].isLetter()) cmd = d[i++]
                else if (cmd == ' ') break
                val rel = cmd.isLowerCase()
                val ox = if (rel) x else 0.0
                val oy = if (rel) y else 0.0
                when (cmd.uppercaseChar()) {
                    'M' -> {
                        val nx = number() ?: break; val ny = number() ?: break
                        x = ox + nx; y = oy + ny; sx = x; sy = y
                        p.moveTo(x, y); open = true
                        cx = x; cy = y
                        // further pairs are lines
                        cmd = if (rel) 'l' else 'L'
                    }
                    'L' -> { val nx = number() ?: break; val ny = number() ?: break; x = ox + nx; y = oy + ny; p.lineTo(x, y); cx = x; cy = y }
                    'H' -> { val nx = number() ?: break; x = ox + nx; p.lineTo(x, y); cx = x; cy = y }
                    'V' -> { val ny = number() ?: break; y = oy + ny; p.lineTo(x, y); cx = x; cy = y }
                    'C' -> {
                        val v = DoubleArray(6) { number() ?: return p }
                        p.curveTo(ox + v[0], oy + v[1], ox + v[2], oy + v[3], ox + v[4], oy + v[5])
                        cx = ox + v[2]; cy = oy + v[3]; x = ox + v[4]; y = oy + v[5]
                    }
                    'S' -> {
                        val v = DoubleArray(4) { number() ?: return p }
                        p.curveTo(2 * x - cx, 2 * y - cy, ox + v[0], oy + v[1], ox + v[2], oy + v[3])
                        cx = ox + v[0]; cy = oy + v[1]; x = ox + v[2]; y = oy + v[3]
                    }
                    'Q' -> {
                        val v = DoubleArray(4) { number() ?: return p }
                        p.quadTo(ox + v[0], oy + v[1], ox + v[2], oy + v[3])
                        cx = ox + v[0]; cy = oy + v[1]; x = ox + v[2]; y = oy + v[3]
                    }
                    'T' -> {
                        val nx = number() ?: break; val ny = number() ?: break
                        val qx = 2 * x - cx; val qy = 2 * y - cy
                        p.quadTo(qx, qy, ox + nx, oy + ny)
                        cx = qx; cy = qy; x = ox + nx; y = oy + ny
                    }
                    'A' -> {
                        val rx = number() ?: break; val ry = number() ?: break; val rot = number() ?: break
                        val large = flag() ?: break; val sweep = flag() ?: break
                        val nx = number() ?: break; val ny = number() ?: break
                        arc(p, x, y, rx, ry, rot, large != 0.0, sweep != 0.0, ox + nx, oy + ny)
                        x = ox + nx; y = oy + ny; cx = x; cy = y
                    }
                    'Z' -> {
                        if (open) p.closePath()
                        x = sx; y = sy; cx = x; cy = y
                        // a command letter must follow
                        skip()
                        if (i < d.length && !d[i].isLetter()) break
                    }
                    else -> break
                }
            }
            return p
        }

        /** An elliptical arc from (x1, y1) to (x2, y2) as SVG draws it, in cubic pieces of at most 90°. */
        private fun arc(p: Path2D.Double, x1: Double, y1: Double, rxIn: Double, ryIn: Double, rotation: Double, large: Boolean, sweep: Boolean, x2: Double, y2: Double) {
            if (x1 == x2 && y1 == y2) return
            var rx = abs(rxIn); var ry = abs(ryIn)
            if (rx == 0.0 || ry == 0.0) { p.lineTo(x2, y2); return }
            val phi = Math.toRadians(rotation)
            val cp = cos(phi); val sp = sin(phi)
            val dx = (x1 - x2) / 2; val dy = (y1 - y2) / 2
            val x1p = cp * dx + sp * dy
            val y1p = -sp * dx + cp * dy
            val lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
            if (lambda > 1) { rx *= sqrt(lambda); ry *= sqrt(lambda) }
            val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
            val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
            val co = (if (large != sweep) 1 else -1) * sqrt(max(0.0, num / den))
            val cxp = co * rx * y1p / ry
            val cyp = -co * ry * x1p / rx
            val cx = cp * cxp - sp * cyp + (x1 + x2) / 2
            val cy = sp * cxp + cp * cyp + (y1 + y2) / 2
            fun angle(ux: Double, uy: Double, vx: Double, vy: Double) = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
            val t1 = angle(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
            var dt = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
            if (!sweep && dt > 0) dt -= 2 * PI
            if (sweep && dt < 0) dt += 2 * PI
            val pieces = kotlin.math.ceil(abs(dt) / (PI / 2)).toInt().coerceAtLeast(1)
            val step = dt / pieces
            val k = 4.0 / 3 * tan(step / 4)
            fun px(t: Double) = cx + rx * cos(t) * cp - ry * sin(t) * sp
            fun py(t: Double) = cy + rx * cos(t) * sp + ry * sin(t) * cp
            // the tangent at angle t, scaled
            fun tx(t: Double) = -rx * sin(t) * cp - ry * cos(t) * sp
            fun ty(t: Double) = -rx * sin(t) * sp + ry * cos(t) * cp
            var a = t1
            repeat(pieces) {
                val b = a + step
                p.curveTo(px(a) + k * tx(a), py(a) + k * ty(a), px(b) - k * tx(b), py(b) - k * ty(b), px(b), py(b))
                a = b
            }
        }
    }
}
