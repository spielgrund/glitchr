package com.spielgrund.glitchr.effects

import com.spielgrund.glitchr.image.Pixels
import com.spielgrund.glitchr.image.parallelRows
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Colors a picture with the palette of another one, pasted in or opened: the reference's
 * colors are found by how often they appear – primary, secondary, third … – and the most
 * vivid of the small ones becomes the accent. The picture's own colors get the same roles,
 * and each role takes on the reference's color: primary becomes primary, accent becomes accent.
 * Or the palette as a gradient over the brightness, as flat poster colors, or only the mood.
 */
object PaletteTransfer : Effect("palette", "Palette from image", "Colors the picture with the palette of a reference image – primary, secondary … and accent colors by how often they appear") {
    override val random = false

    private val mappings = listOf("By role", "By brightness", "Flat colors", "Mood only")

    override val params = listOf(
        Param.Picture("reference", "Reference image", "The picture whose colors are used: paste one or open a file. Without one, a sample palette is used"),
        Param.Slider("colors", "Colors", 2, 8, 5, tip = "How many colors are found in the reference (and in the picture): primary, secondary … and accent"),
        Param.Choice(
            "mapping", "Mapping", mappings, 0,
            "By role: the picture's main color becomes the reference's main color, its accent the accent, and so on – details stay · " +
                "By brightness: the palette from dark to light laid over the brightness · Flat colors: every area in exactly its palette color · " +
                "Mood only: the overall color and contrast of the reference",
        ),
        Param.Slider("amount", "Amount", 0, 100, 100, " %"),
        Param.Slider("softness", "Softness", 0, 100, 50, " %", "How smoothly the colors blend into each other – low: crisp borders between the color areas"),
        Param.Slider("keepLight", "Keep lightness", 0, 100, 0, " %", "How much of the picture's own lightness stays"),
        Param.Slider("hue", "Hue", -180, 180, 0, "°", "Turns all the new colors around the color wheel"),
        Param.Slider("saturation", "Saturation", 0, 200, 100, " %", "Paler or more vivid colors; 0 % grey"),
    )

    /** A color of a palette: in Lab and sRGB, with its share of the picture. */
    class Swatch(val lab: DoubleArray, val rgb: Int, val share: Double)

    /** The role of the [i]-th of [count] colors, as the palette lists them. */
    fun role(i: Int, count: Int) = when {
        count >= 3 && i == count - 1 -> "Accent"
        i == 0 -> "Primary"
        i == 1 -> "Secondary"
        i == 2 -> "Tertiary"
        else -> "${i + 1}th"
    }

    /** A sample palette for when no reference is given: deep teal, sea green, sand, apricot, coral accent. */
    private val sample = intArrayOf(0x264653, 0x2A9D8F, 0xE9C46A, 0xF4A261, 0xE76F51)

    private class Analysis(val swatches: List<Swatch>, val mean: DoubleArray, val spread: DoubleArray)

    private var lastKey: Pair<String, Int>? = null
    private var last: Analysis? = null

    /** The palette of the reference stored in [text] with [count] colors, in role order (accent last); the sample palette without one. */
    fun palette(text: String, count: Int): List<Swatch> = reference(text, count).swatches

    @Synchronized
    private fun reference(text: String, count: Int): Analysis {
        val key = text to count
        if (key == lastKey) return last!!
        val image = decode(text)
        val result = if (image != null) {
            val px = IntArray(image.width * image.height)
            image.getRGB(0, 0, image.width, image.height, px, 0, image.width)
            analyse(px, count)
        } else {
            // the sample palette: its colors with falling shares, the last one the accent
            val n = count.coerceIn(2, sample.size)
            val picked = sample.take(n - 1) + sample.last()
            val shares = picked.indices.map { if (it == n - 1 && n >= 3) 0.08 else 1.0 / (it + 1.5) }
            val total = shares.sum()
            val swatches = picked.mapIndexed { i, c -> Swatch(DoubleArray(3).also { LabColor.toLab(c, it) }, c, shares[i] / total) }
            val mean = DoubleArray(3) { ch -> swatches.sumOf { it.lab[ch] * it.share } }
            val spread = DoubleArray(3) { ch -> sqrt(swatches.sumOf { (it.lab[ch] - mean[ch]).let { d -> d * d } * it.share }).coerceAtLeast(1.0) }
            Analysis(swatches, mean, spread)
        }
        lastKey = key
        last = result
        return result
    }

    /**
     * The [count] main colors of [px] (k-means in Lab over up to 30 000 pixels), sorted by
     * how often they appear; the most vivid of the smaller ones, standing out most from the
     * primary color, moves to the end as the accent. With mean and spread of all the colors.
     */
    private fun analyse(px: IntArray, count: Int): Analysis {
        val opaque = px.filter { it ushr 24 >= 128 }.ifEmpty { px.toList() }
        val stride = max(1, opaque.size / 30_000)
        val n = (opaque.size + stride - 1) / stride
        val lab = DoubleArray(n * 3)
        val tmp = DoubleArray(3)
        for (i in 0 until n) {
            LabColor.toLab(opaque[i * stride], tmp)
            lab[i * 3] = tmp[0]; lab[i * 3 + 1] = tmp[1]; lab[i * 3 + 2] = tmp[2]
        }
        val k = count.coerceIn(1, max(1, n))
        fun dist(i: Int, c: DoubleArray, j: Int): Double {
            val dl = lab[i * 3] - c[j * 3]; val da = lab[i * 3 + 1] - c[j * 3 + 1]; val db = lab[i * 3 + 2] - c[j * 3 + 2]
            return dl * dl + da * da + db * db
        }
        // k-means++ start, always the same for the same picture
        val random = Random(7)
        val centers = DoubleArray(k * 3)
        val first = random.nextInt(n)
        for (ch in 0..2) centers[ch] = lab[first * 3 + ch]
        val nearest = DoubleArray(n) { dist(it, centers, 0) }
        for (j in 1 until k) {
            val total = nearest.sum()
            var pick = random.nextDouble() * total
            var chosen = n - 1
            for (i in 0 until n) { pick -= nearest[i]; if (pick <= 0) { chosen = i; break } }
            for (ch in 0..2) centers[j * 3 + ch] = lab[chosen * 3 + ch]
            for (i in 0 until n) nearest[i] = min(nearest[i], dist(i, centers, j))
        }
        val owner = IntArray(n)
        val sizes = IntArray(k)
        repeat(20) {
            for (i in 0 until n) {
                var best = 0
                var bd = Double.MAX_VALUE
                for (j in 0 until k) { val d = dist(i, centers, j); if (d < bd) { bd = d; best = j } }
                owner[i] = best
            }
            val sums = DoubleArray(k * 3)
            sizes.fill(0)
            for (i in 0 until n) { val j = owner[i]; sizes[j]++; for (ch in 0..2) sums[j * 3 + ch] += lab[i * 3 + ch] }
            for (j in 0 until k) if (sizes[j] > 0) for (ch in 0..2) centers[j * 3 + ch] = sums[j * 3 + ch] / sizes[j]
        }
        var swatches = (0 until k).filter { sizes[it] > 0 }.map { j ->
            val c = doubleArrayOf(centers[j * 3], centers[j * 3 + 1], centers[j * 3 + 2])
            Swatch(c, LabColor.fromLab(c[0], c[1], c[2], 0, false) and 0xFFFFFF, sizes[j] / n.toDouble())
        }.sortedByDescending { it.share }
        if (swatches.size >= 3) {
            // the accent: vivid, and far from the primary color
            val primary = swatches[0].lab
            val accent = swatches.drop(2).maxBy { s ->
                val chroma = sqrt(s.lab[1] * s.lab[1] + s.lab[2] * s.lab[2])
                val apart = sqrt((0..2).sumOf { (s.lab[it] - primary[it]).let { d -> d * d } })
                chroma * (1 + apart / 40)
            }
            swatches = swatches.filter { it !== accent } + accent
        }
        val mean = DoubleArray(3) { ch -> (0 until n).sumOf { lab[it * 3 + ch] } / n }
        val spread = DoubleArray(3) { ch -> sqrt((0 until n).sumOf { (lab[it * 3 + ch] - mean[ch]).let { d -> d * d } } / n).coerceAtLeast(1.0) }
        return Analysis(swatches, mean, spread)
    }

    override fun apply(src: Pixels, v: Values, seed: Long): Pixels {
        val count = v["colors"]
        val target = reference(v.text("reference"), count)
        val own = analyse(src.data, count)
        val amount = v["amount"] / 100.0
        val keep = v["keepLight"] / 100.0
        // how far a color reaches (Lab distance) when blending between the color areas
        val sigma = 1.5 + v["softness"] / 100.0 * 18.5
        val mapping = v["mapping"]
        // hue and saturation: a turn and a scale of the color plane (a, b)
        val turn = Math.toRadians(v["hue"].toDouble())
        val ct = kotlin.math.cos(turn)
        val st = kotlin.math.sin(turn)
        val saturation = v["saturation"] / 100.0
        // roles: each of the picture's colors to the reference's color of the same role
        val pairs = min(own.swatches.size, target.swatches.size)
        val from = own.swatches.take(pairs)
        val to = if (pairs == target.swatches.size) target.swatches else target.swatches.take(pairs - 1) + target.swatches.last()
        // by brightness: the reference's colors from dark to light, over the picture's brightness range
        val ramp = target.swatches.sortedBy { it.lab[0] }
        val lights = src.data.map { DoubleArray(3).also { lab -> LabColor.toLab(it, lab) }[0] }.sorted()
        val lo = lights[(lights.size * 0.02).toInt()]
        val hi = max(lo + 1, lights[min(lights.size - 1, (lights.size * 0.98).toInt())])
        val out = Pixels(src.width, src.height)
        val w = src.width
        parallelRows(src.height) { y ->
            val lab = DoubleArray(3)
            val weights = DoubleArray(pairs)
            for (x in 0 until w) {
                val i = y * w + x
                val c = src.data[i]
                LabColor.toLab(c, lab)
                val l0 = lab[0]
                var l = lab[0]; var a = lab[1]; var b = lab[2]
                when (mapping) {
                    1 -> {
                        val t = ((l0 - lo) / (hi - lo)).coerceIn(0.0, 1.0) * (ramp.size - 1)
                        val k0 = t.toInt().coerceAtMost(ramp.size - 2).coerceAtLeast(0)
                        val f = if (ramp.size == 1) 0.0 else t - k0
                        val p = ramp[k0].lab
                        val q = ramp[min(ramp.size - 1, k0 + 1)].lab
                        l = p[0] + (q[0] - p[0]) * f; a = p[1] + (q[1] - p[1]) * f; b = p[2] + (q[2] - p[2]) * f
                    }
                    3 -> {
                        l = (l0 - own.mean[0]) / own.spread[0] * target.spread[0] + target.mean[0]
                        a = (lab[1] - own.mean[1]) / own.spread[1] * target.spread[1] + target.mean[1]
                        b = (lab[2] - own.mean[2]) / own.spread[2] * target.spread[2] + target.mean[2]
                    }
                    else -> {
                        // how much the pixel belongs to each of the picture's colors
                        var total = 0.0
                        var best = 0
                        var bestD = Double.MAX_VALUE
                        for (j in 0 until pairs) {
                            val s = from[j].lab
                            val d = (lab[0] - s[0]) * (lab[0] - s[0]) + (lab[1] - s[1]) * (lab[1] - s[1]) + (lab[2] - s[2]) * (lab[2] - s[2])
                            if (d < bestD) { bestD = d; best = j }
                            weights[j] = d
                        }
                        for (j in 0 until pairs) { weights[j] = exp(-(weights[j] - bestD) / (2 * sigma * sigma)); total += weights[j] }
                        if (mapping == 2) {
                            // flat: the palette color itself, softly blended at the borders
                            l = 0.0; a = 0.0; b = 0.0
                            for (j in 0 until pairs) { val t = to[j].lab; val k = weights[j] / total; l += t[0] * k; a += t[1] * k; b += t[2] * k }
                        } else {
                            // by role: moved by the difference between the two colors of its role – details stay
                            for (j in 0 until pairs) {
                                val k = weights[j] / total
                                l += (to[j].lab[0] - from[j].lab[0]) * k
                                a += (to[j].lab[1] - from[j].lab[1]) * k
                                b += (to[j].lab[2] - from[j].lab[2]) * k
                            }
                        }
                        if (best < 0) l = l0
                    }
                }
                l = l + (l0 - l) * keep
                val a2 = (a * ct - b * st) * saturation
                b = (a * st + b * ct) * saturation
                a = a2
                val colored = LabColor.fromLab(l, a, b, c, false)
                out.data[i] = if (amount >= 1) colored else mixArgb(c, colored, amount)
            }
        }
        return out
    }

    private fun mixArgb(a: Int, b: Int, t: Double): Int {
        fun ch(s: Int) = ((a shr s and 0xFF) + ((b shr s and 0xFF) - (a shr s and 0xFF)) * t + 0.5).toInt().coerceIn(0, 255) shl s
        return (a and 0xFF000000.toInt()) or ch(16) or ch(8) or ch(0)
    }

    // ---- palettes from text and other data ----

    /** Mood words (English and German) and the hue they stand for, in degrees. */
    private val hueWords = listOf(
        0 to listOf("red", "rot", "fire", "feuer", "blood", "blut", "love", "liebe", "rose", "cherry", "kirsche"),
        25 to listOf("orange", "sunset", "sonnenuntergang", "autumn", "herbst", "rust", "rost", "copper", "kupfer", "terracotta"),
        45 to listOf("gold", "sand", "desert", "wüste", "wueste", "honey", "honig", "amber", "bernstein", "beach", "strand"),
        58 to listOf("yellow", "gelb", "sun", "sonne", "lemon", "zitrone", "summer", "sommer"),
        95 to listOf("lime", "limette", "spring", "frühling", "fruehling", "grass", "gras"),
        130 to listOf("green", "grün", "gruen", "forest", "wald", "nature", "natur", "leaf", "blatt", "jungle", "dschungel", "moss", "moos"),
        170 to listOf("mint", "minze", "jade", "teal", "petrol"),
        190 to listOf("cyan", "ocean", "ozean", "sea", "meer", "water", "wasser", "ice", "eis", "lagoon", "lagune", "aqua"),
        215 to listOf("blue", "blau", "sky", "himmel", "night", "nacht", "denim", "storm", "sturm"),
        255 to listOf("indigo", "space", "weltall", "galaxy", "galaxie", "cosmic", "deep", "tief"),
        280 to listOf("purple", "lila", "violet", "violett", "lavender", "lavendel", "grape", "traube", "dream", "traum"),
        320 to listOf("pink", "rosa", "magenta", "candy", "bonbon", "flamingo", "berry", "beere"),
    )

    /** A palette from [text]: the colors written in it (#rrggbb, #rgb, 0xrrggbb, rgb(…)), or one made up to fit its words. */
    fun fromText(text: String): String {
        val written = colorsIn(text)
        if (written.size >= 2) return encode(blocks(written))
        val lower = text.lowercase()
        val words = Regex("""[\p{L}]+""").findAll(lower).map { it.value }.toSet()
        // the hue: one written color, or the mood words, or chance
        val hue = written.firstOrNull()?.let { c -> java.awt.Color.RGBtoHSB(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF, null)[0] * 360.0 }
            ?: hueWords.filter { (_, list) -> list.any { it in words } }.map { it.first.toDouble() }.takeIf { it.isNotEmpty() }?.let { hues ->
                // several moods: their mean on the color wheel
                val x = hues.sumOf { kotlin.math.cos(Math.toRadians(it)) }
                val y = hues.sumOf { kotlin.math.sin(Math.toRadians(it)) }
                (Math.toDegrees(kotlin.math.atan2(y, x)) + 360) % 360
            }
        return encode(blocks(harmony(text.toByteArray(Charsets.UTF_8), hue, words)))
    }

    /** A palette made up to fit [data] – any bytes: the same data always gives the same colors. */
    fun fromBytes(data: ByteArray): String = encode(blocks(harmony(data, null, emptySet())))

    /** The colors written in [text], in their order, without repeats. */
    private fun colorsIn(text: String): List<Int> {
        val found = ArrayList<Pair<Int, Int>>()
        Regex("""#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})(?![0-9a-fA-F])""").findAll(text).forEach { m ->
            val hex = m.groupValues[1].let { h -> if (h.length == 3) h.map { "$it$it" }.joinToString("") else h }
            found += m.range.first to hex.toInt(16)
        }
        Regex("""0x([0-9a-fA-F]{6})(?![0-9a-fA-F])""").findAll(text).forEach { m -> found += m.range.first to m.groupValues[1].toInt(16) }
        Regex("""rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})""").findAll(text).forEach { m ->
            val (r, g, b) = m.destructured
            found += m.range.first to ((r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255))
        }
        return found.sortedBy { it.first }.map { it.second }.distinct()
    }

    /**
     * Five colors that go together, chosen by chance from [data] (always the same for the same
     * data): a color scheme – analogous, complementary, split, triadic or one hue – around [hue]
     * (or a chance one), dark or light, soft or vivid as [words] say. In role order: primary,
     * secondary, a quiet tertiary, a fourth, and a vivid accent.
     */
    private fun harmony(data: ByteArray, hue: Double?, words: Set<String>): List<Int> {
        val random = Random(java.util.Arrays.hashCode(data).toLong() * 0x9E3779B97F4A7C15uL.toLong() + data.size)
        fun has(vararg w: String) = w.any { it in words }
        val base = hue ?: (random.nextDouble() * 360)
        val dark = has("dark", "dunkel", "night", "nacht", "noir", "schwarz", "black", "space", "weltall", "deep", "tief", "gothic")
        val light = has("light", "hell", "pastel", "pastell", "soft", "sanft", "weich", "baby", "cream", "creme", "white", "weiß", "weiss")
        val vivid = has("neon", "vivid", "kräftig", "kraeftig", "bright", "leuchtend", "electric", "elektrisch", "pop", "candy", "bonbon")
        val muted = has("muted", "gedeckt", "vintage", "retro", "dusty", "staubig", "calm", "ruhig", "earth", "erde")
        val saturation = when {
            vivid -> 0.85 + random.nextDouble() * 0.15
            muted || light -> 0.25 + random.nextDouble() * 0.2
            else -> 0.45 + random.nextDouble() * 0.35
        }
        // the main brightness: a dark, a light or a middle palette
        val mainLight = when {
            dark -> 0.18 + random.nextDouble() * 0.12
            light -> 0.82 + random.nextDouble() * 0.12
            else -> listOf(0.22, 0.5, 0.85)[random.nextInt(3)] + (random.nextDouble() - 0.5) * 0.1
        }
        // the scheme: where the second color and the accent sit on the color wheel
        val (second, accent) = when (random.nextInt(5)) {
            0 -> 30.0 to 180.0 // analogous with a complementary accent
            1 -> 180.0 to 30.0 // complementary
            2 -> 150.0 to 210.0 // split complementary
            3 -> 120.0 to 240.0 // triadic
            else -> 0.0 to 180.0 // one hue, a complementary accent
        }.let { (a, b) -> if (random.nextBoolean()) a to b else -a to -b }
        fun hsb(h: Double, s: Double, b: Double) = java.awt.Color.HSBtoRGB((((h % 360) + 360) % 360 / 360).toFloat(), s.coerceIn(0.0, 1.0).toFloat(), b.coerceIn(0.03, 1.0).toFloat()) and 0xFFFFFF
        val contrast = if (mainLight > 0.6) mainLight - 0.45 else mainLight + 0.4
        return listOf(
            hsb(base, saturation * 0.8, mainLight),
            hsb(base + second, saturation, contrast),
            // quiet: almost grey, far from the main brightness
            hsb(base, saturation * 0.2, if (mainLight > 0.6) 0.25 else 0.92),
            hsb(base + second / 2 + 12, saturation * 0.9, (mainLight + contrast) / 2),
            // the accent: vivid and bright
            hsb(base + accent, max(0.75, saturation), 0.9),
        )
    }

    /** A picture of [colors] side by side, each one less wide than the one before, so they keep their roles. */
    private fun blocks(colors: List<Int>): BufferedImage {
        // the accent the smallest of all
        val weights = colors.indices.map { if (it == colors.size - 1 && colors.size >= 3) 0.6 / (it + 1.2) else 1.0 / (it + 1.2) }
        val total = weights.sum()
        val image = BufferedImage(200, 20, BufferedImage.TYPE_INT_RGB)
        var x = 0
        colors.forEachIndexed { i, c ->
            val wide = if (i == colors.size - 1) 200 - x else max(1, (weights[i] / total * 200).toInt())
            for (xx in x until min(200, x + wide)) for (y in 0 until 20) image.setRGB(xx, y, c)
            x += wide
        }
        return image
    }

    // ---- the stored reference ----

    private var lastText: String? = null
    private var lastImage: BufferedImage? = null

    /** A picture as reference text: at most 256 pixels wide or high, as PNG. */
    fun encode(image: BufferedImage): String {
        val k = min(1.0, 256.0 / max(image.width, image.height))
        val w = max(1, (image.width * k).toInt())
        val h = max(1, (image.height * k).toInt())
        val small = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        small.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(if (k < 1) image.getScaledInstance(w, h, java.awt.Image.SCALE_AREA_AVERAGING) else image, 0, 0, null)
            dispose()
        }
        val bytes = ByteArrayOutputStream().also { ImageIO.write(small, "png", it) }.toByteArray()
        return "png:" + Base64.getEncoder().encodeToString(bytes)
    }

    /** The reference picture stored in [text], or null. Remembers the last one. */
    @Synchronized
    fun decode(text: String): BufferedImage? {
        if (!text.startsWith("png:")) return null
        if (text == lastText) return lastImage
        val image = try {
            ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(text.substring(4))))
        } catch (e: Exception) {
            null
        }
        lastText = text
        lastImage = image
        return image
    }
}
