# GlitchR

Destructive glitch filters as layers, in Kotlin (Swing + FlatLaf).

The layer stack holds **image layers**, **generator layers** and **effect layers**. Effects work on
the nearest image or generator layer below them (shown indented above it in the list); the image with
its effects is then laid over the layers below. This way several images can be stacked, each with
effects of its own. Every layer has opacity, a blend mode and a mask.

## Image layers

- The first image (Open, Ctrl+O) sets the canvas size.
- Further images are added as new image layers: drag them into the window (several at once too),
  “+ Image…” / Ctrl+I or Ctrl+V. Larger images are fitted, all are centered.
- With an image layer selected, dragging in the picture moves it, the corner handles scale it
  (keeping the aspect ratio), Shift + corner handle rotates it around its middle (with Ctrl in 15° steps).
  While its mask is shown (“Show mask”, Ctrl+M), dragging edits the mask instead; to move the layer,
  hide the mask or hold Ctrl.
- Long file names are shortened as layer names (start…end); rename in the panel.
- In the properties panel: X, Y, scale, rotation, “Smooth scaling” (off = hard pixels) and the
  buttons Fit, Fill, Original size, Center, Reset rotation.
- Outside the images the canvas is transparent; PNG export keeps that, JPEG puts it on white.
- An image layer's mask cuts out the image *before* the effects above work; so the effects can run
  beyond the mask edge. The masks of effect layers on the other hand set where the effect is visible.
- The masks of an image layer and of its effects belong to the image: they move, scale and rotate
  with it – when painting, with the selection tools, the gradient handles and the red overlay.
  Painted masks have the image's resolution; outside the image the mask's edge value applies.
- Effects can work beyond the picture edge into the empty canvas: Pixelbleed keeps running,
  Pixelsort pulls runs that hit the edge out by the “Overhang”, shifted pixels
  (RGB-Distort, Slice shift, Block-Glitch, Datamosh) take their opacity along, and with the
  JPEG artifacts the transparency is compressed too, so the blocks fray.
- Moving, duplicating and deleting an image layer takes its effects along.
- “Original” (Ctrl+B) shows all image and generator layers without effects.

## Starting without an image: generator layers

“New…” (Ctrl+N) creates an empty canvas of any size (with presets such as HD, 4K, square,
A4 300 dpi) and can start it with a generator right away. “+ Generator ▾” or
“Layer → Add generator” inserts a generator layer above the selected group; without an open
document it first asks for the canvas size.

A generator layer creates an image the size of the canvas and otherwise behaves like an image layer:
effects above work on it, its mask cuts out the generated image, moving, duplicating and deleting
take the effects along. Every generator except Gradient has a **base color** (the area the pattern is
created on); settings that only make sense with an image (e.g. “Picture shapes noise”,
“Picture influence”) are hidden. Where a pattern still asks for the picture (picture colors,
background “Original picture”), it gets the base color.

| Generator | What it creates |
| --- | --- |
| Color fill | A single-color area, as a base for effects |
| Gradient | Color gradient linear, linear mirrored, radial, angle, diamond or square; two colors and optionally a middle color (adjustable position), angle, center, size, repeats (mirrored if you like), distribution, hard steps, dithering against banding |
| Moiré | Two patterns over each other (each lines, rings, rays, spiral, grid, dots, checkerboard or zone plate) with their own spacing to 0.1 px, line width, angle to 0.01°, center, stretch X/Y (along the pattern's own axes, rings become ellipses) and wave; combined as overprint (the lines lie on the background, where they cross their colors mix like printing inks), B covers A, difference (XOR), light or intersection only, three colors; “Moiré only” blurs until only the moiré bands remain (with stretched contrast if you like); anti-aliasing off (hard aliasing), 3×3 or 5×5 |
| Mandala | Concentric rings, each with a motif (leaves, dots, arches, spikes, drops, rays, diamonds) repeated symmetrically around the middle; symmetry 3–48, number of rings, fixed or mixed motif, ring width outwards, ring width variation, ornament (inner outlines, veins, dots), dividing circles, rotation, center; style lines, filled or filled with outline, line width; colors single, alternating, gradient inside → outside, rainbow, pastel; background colored or transparent. “Reroll” gives a new mandala with the same settings |
| Spirograph | Like the stencil: a gear rolls inside the toothed ring or around its outside, the pen in the hole draws the curve; with whole numbers of teeth it closes by itself (after wheel ÷ gcd(ring, wheel) turns); hole 0–150 % (above 100 % loops), share of the curve; several passes, each rotated further and/or with the pen in a different hole; size, rotation, center, line width, opacity; colors single, gradient or rainbow per pass or along the curve |
| Noise | The noise effect, preset to black and white (colors changeable, fractal), with all noise types, direction, size, stretch, detail and contrast |
| Color pattern | Stripes, checkerboard, triangles, hexagons, dots, diamonds, zigzag, rings, Truchet – as a gradient between two colors |
| Geometric | The op-art patterns, black and white or as a gradient; for the Y pattern the line length is adjustable (up to 300 %: the arms run through the neighboring cells all the way to the other side) |
| Feedback | Video feedback: copies of the picture over each other again and again (1–100 steps); every step scales (50–200 %), rotates and offsets the previous copy around a chosen center – the offset is rotated and scaled along, which makes tunnels and spirals; edge of the copies transparent, repeated, mirrored or stretched; shift hue, saturation and lightness (HSL) per step; copies above the picture or the picture above the copies, blend mode, opacity, decay per step; finally, if you like, the first copy or the original on top once more (own opacity), so that growing copies don't cover the subject. Source *Picture*, *Edges* (color edges per channel, threshold, strength, line width; colored in the picture color, one color or by edge direction) or *Blobs*: the picture is split into color areas as for Hologram (abstraction, minimum size), every blob gets its own feedback around its middle – above 100 % it grows out of the blobs, below it runs into them – as outline (equally wide in every copy; blob color, one color or hue from the direction to the middle), area or picture content; or *Alpha edge* (only the outline). The alpha edge – along the transparent parts of the picture and the picture edge, including the image layer's mask, since that cuts out the picture before the effects – can also be drawn in the other modes (“Draw alpha edge”): then it frames the picture and every copy. It has a line width (0.5–200 px, round corners from exact distances), placement outside, middle or inside (default; at the picture edge only the inner part is visible) and coloring in one color, the picture color at the edge or the hue from the direction; base picture, black, white or transparent |
| Generative | Bands, flow lines, moiré rings, mirror tiles along noise, preset to pastel rainbow |
| Grid | Even grid modules: dots, circles, rays, hatching, stripes, moiré grid |
| Spectroscope | Stacked noise lines like a spectrogram (or the “Unknown Pleasures” cover), middle emphasized |
| Characters | Your own text (or a character set) as a pattern across the whole area |

Moiré ideas: the same lines one or two degrees apart give wide stripes; two ring patterns with slightly
shifted centers give hyperbolas; two spacings like 8.0 and 8.4 px give beats; two zone plates
side by side give straight stripes.

Every generator also has a *position*: the generated image can be shifted, scaled and rotated around the
middle of the canvas; at the edge it stays transparent (default), repeats, is mirrored or
stretched. The masks of the generator layer and its effects move, scale and rotate with it.

The project stores only the settings; the image is created anew when opening. The layer list
shows a preview of the generated image.

## Effects

The effects are sorted into categories in the “+ Effect ▾” menu (and under Layer → Add effect).

### Glitch

| Effect | What it does |
| --- | --- |
| Pixelsort | Sorts runs of pixels between two brightness thresholds, at any angle, by brightness/hue/saturation/R/G/B; block size 1–32 px or random; overhang beyond the picture edge |
| Pixelbleed | Port of `pixelbleed_06.py`: pixels above (or below) the threshold bleed out in randomly long streaks – threshold by average RGB (as in the script), brightness, brightest/darkest channel, saturation, colorfulness, hue or a color channel, fading to a chosen target color; block size 1–32 px or random |
| Pixelstretch | Stretches pixels whose value lies between two thresholds (brightness, average RGB, brightest or darkest channel, saturation, colorfulness, hue – also across red, e.g. 230–20 –, red, green or blue) by a length (with length variation) at any angle; the pixels of a run keep their order. *Overlay* (like Pixelbleed): the stretched run lies over the following pixels, covered pixels are not stretched themselves. *Push* (like Pixelsort): the following pixels are pushed on by the length, at the picture edge into the empty canvas. “Max. run” limits how many pixels are stretched together (default 1 = each on its own, 0 = the whole run); block size 1–32 px or random |
| JPEG artifacts | Real JPEG compression, several passes, larger blocks (nearest neighbor or bilinear), randomly broken bytes, full color resolution 4:4:4, resharpening |
| Datamosh | Macroblocks are dragged along over several “frames” as in a video without keyframes (flow, random, along the brightness or one direction) |
| Block-Glitch | Shift rectangles, swap channels, invert, smear, as a big pixel or with a median filter |
| Bitcrush | Fewer bits per channel, dithering, pixel size |
| RGB-Distort | Shift channels one by one, sine wave, swap the channel order |
| Slice shift | Shift random stripes sideways, optionally only one channel |
| Slitscan | Cuts the picture into rows (size and angle adjustable) and shifts each along its direction and with “Offset Y” across it, so the picture runs through the rows; the offset (positive or negative) grows from the first to the last row linearly, exponentially (with an adjustable curve) or randomly; edge repeat, stretch or mirror; the picture can be shifted in X and Y beforehand (it repeats at the edge) |

### Distort & Repeat

| Effect | What it does |
| --- | --- |
| Shift | Shifts the picture in X and Y, it repeats at the edge; mirror in X and Y |
| Transform | Shift (to 0.1 px), scale (1–1000 %, plus stretch X/Y), rotate (to 0.1°) and mirror around a chosen center; transparent, repeated, mirrored or stretched at the edge; smooth or hard pixels |
| Displace | Shifts the picture's pixels along noise (Perlin, fractal, ridged, Worley, value, white); seamless with a gradient mask |
| Flow | Draw a flow direction into the picture with the mouse (arrows on the canvas, the right mouse button/Alt wipes strokes away); the picture travels along the direction (offset −400 to 400 %, 100 % = one complete repeat) – whole with repeats at the edge, in sections that keep starting over, or as a loop in a band along the arrows (whatever arrives at the arrow tip jumps to the start), with hard or soft edges; follow the flow or directly like a UV offset; reach of the strokes, continue or stand still away from them, base direction without strokes |
| Turbulence | Colors flow as on water or a soap film: a swirling, slowly changing flow (divergence-free – it folds and stretches instead of piling up) draws the colors out into fine, tangled streaks – swirl strength, swirl size, fineness, passes, change; direction and direction strength also drive the color into long paths. The whole picture flows or only a threshold range (brightness, saturation, hue …), fading out softly (feather, “Show range”). The streaks stay sharp because only the origin of every pixel is tracked and the picture is read just once at the end; anti-aliasing (off, 2 × 2, 4 × 4) reads several times where streaks become finer than a pixel. Film colors lay rainbow streaks like a soap or oil film along the brightness lines on top (own gradient, film bands) |
| Kaleidoscope | Folds the picture into 2–32 segments; rotation, source angle, offset, center, zoom, mirroring; up to 6 levels of “fold within the fold” with their own segment count, distance, rotation and scale |
| Feedback | Video feedback on the picture: copies of the picture, its edges or blobs over each other again and again – larger, smaller, rotated, offset, with color shift (like the Feedback generator, see above) |
| Blob-Echo | Splits the picture into areas (like Particles) and copies all areas – or just a pie slice of each area (1–360°, direction selectable) – over the picture again and again in a chosen direction, from the largest to the smallest area, up to the edge or a fixed number of times; very large areas (usually the background) can be left out; spacing, fade, copies behind their own area, above everything or behind all areas |

### Color & Sharpness

| Effect | What it does |
| --- | --- |
| Color correction | *Exposure and tone mapping*: exposure in stops in linear light, tone mapping Reinhard, filmic (ACES) or Hable; highlight compression (−200 to 200 %) above a knee, per channel (positive pulls the highlights down softly and makes them paler, negative stretches them until they burn out channel by channel and the contrast rises sharply – the hues shift); levels (black point, white point, gamma), contrast, highlights, shadows. *HSL*: hue, lightness, vibrance and saturation up to 1000 % – as in old Photoshop every channel is pushed away from the pixel's grey, high values overdrive into garish, burning colors; beyond full saturation the channels are clipped (hard overdrive), or the saturation starts again at grey (overflow, color bands) or runs back (mirror), the hue stays; brightness beyond its range is clipped, overflows (too bright jumps to dark) or is mirrored back. The default is neutral (contrast and saturation 100 %). *Curves*: curve editor for RGB, red, green and blue (click sets a point, dragging moves it, right-click removes it; the curve runs smoothly without overshoot). Strength |
| Ramp | Colors through a gradient (gradient map) with any number of color stops – editor as in Noiser: click adds a stop, dragging moves it, double-click chooses the color, right-click removes it; presets (Sunset, Greyscale, Terrain, Fire, Ocean, Neon, Toxic, Duotone, Thermal, Rainbow) and invert. Ramp by brightness, average, brightest/darkest channel, saturation, colorfulness, hue or a channel; mix: replace, color only (brightness stays), soft light, overlay, multiply, screen, difference; repeats (mirrored or with hard jumps), shift, strength |
| LAB colors | Colors in the CIELAB color space: shift L (lightness), contrast, invert; shift and amplify a (green ↔ magenta) and b (blue ↔ yellow), negative swaps the opposite colors; chroma (0 % = grey, default 150 %) and rotate hue in LCh – before the shifts, so a desaturated picture can be tinted (e.g. sepia); swap channels (a ↔ b, L ↔ a, L ↔ b, rotate), cut into steps; clip or overflow colors outside the screen's gamut; strength |
| Fill areas | Finds areas in the picture and fills each one differently. Methods: same color (connected pixels of similar color – detects every tile of a generator pattern; compared with the neighbor pixel, so gradients within a tile stay whole, or with the area's start color, better for photos; tolerance), brightness levels, hue (HSL, greys on their own, optionally also split by brightness), blobs (connected pieces of a threshold range) or edges (what lies between the outlines); diagonally connected, minimum area, small areas to the neighboring area or unchanged. Filling: random greys, random colors (saturation, brightness range), random from a gradient, gradient by the area's size or brightness, area average; replace, multiply or color only (brightness stays), outlines, strength. Cut out: the largest area, everything at the picture edge (usually the background), areas by color, the brightest, the darkest or a random share become transparent – or the other way round only these remain. Anti-aliasing (2 × 2 or 4 × 4 as for Glass): along the borders between the areas and at cut-out edges it is sampled several times – every sample belongs to the adjacent area with the most similar color – and averaged. Reroll gives new colors |
| Blur | Blur: Gauss, box, directional (angle), radial (zoom) and spin around a chosen center; all channels, only red/green/blue/alpha, only the brightness or only the color; data errors: overflow (sums wrap around), row width (a misread row length shears the picture), bit errors (flipping bits in stripes), carry-over (the running sum is not reset between rows) |
| Sharpen | Sharpen, unsharp mask (strength −300 to 500 %, negative softens; radius, threshold) and clarity (local contrast in the midtones); RGB or brightness only; “Overdrive” drives the sharpening into glitchiness: at the edges the brightness or saturation shoots up, the values overflow or red and blue drift apart as colorful fringes |

### Growth & Simulation

| Effect | What it does |
| --- | --- |
| Grow | Spreading like an infection, after Entagma's “Point Based Growth Solver”: the start mask (pixels whose brightness, saturation, hue … lies between two thresholds; “Show start mask” shows it in black and white) is infected; in every step the pixels gather infection from their infected neighbors (radius, weighted by distance and direction) – depending on susceptibility: random immunity per pixel, a noise field and the picture (brightness, saturation … get infected more or less easily). Pace and shape: round (bays fill first) to branching (where it gets tight it slows down – corals, mazes, crystal offshoots). Every pixel remembers when, by whom and from which start pixel it was infected. Content: *Smear* (default: like a smudge finger the growth drags the whole picture in its direction – every grown spot drags as far as it has grown, the drag field fades out softly over the reach, so the subject and its surroundings are dragged along too; displacement, reach, smudge streaks), *Stretch picture* (every grown pixel is shifted back to its start pixel, the direction smoothed – the start mask's picture is pulled along the growth paths onto the new area; stretch 100 % all the way to the front, less only shifts; smoothness from crystalline facets to flowing), carried pixels (each takes over the content of the one that infected it – the start pixels grow outwards; texture lets their texture travel along), growth time as a color gradient (gradient editor, rings) or reveal the picture along the front; direction and direction strength steer it; drawn softly and anti-aliased); what has not grown as picture, black or transparent; strength; precision 1, 2 or 4 px (coarser: faster, thicker branches). |
| Horns | From the edge of a region – threshold range (brightness, saturation, hue …), ellipse (position, size; e.g. around a head) or both; “Show start mask” – strands grow, straight outwards or leaning in a direction (direction, direction strength). Shape *Horn*: they curl into spirals towards the tip; each takes the picture's cross-section at its base along and pulls it as stripes along the curve – count, length, width, taper, curl up to three turns, spiral shape from an even arc to straight at first and tight at the tip, turning direction, variation, drag texture along, roundness, edge softness, soft base (fades the horn in softly at the base); shape *Fan* (like a split gill mushroom: the strand widens instead of tapering, forks several times into overlapping branches, the end lobes open in a wedge with a round, wavy hem; fine gills run lengthwise and fan out across all forks – fan out, branches, branches per fork, fork angle, gills, gill depth, ruffles, ruffle waves, tint in one color with the picture's brightness as structure, its own drag texture along); shape *Split* (the whole region grows outwards: in every direction (stems) it splits into two halves that curl outwards, from the middle of the split the next, larger head grows on a stalk and splits again – levels, growth per level, stalk, head width; “Front” sets how the region is read, so all stems look the same – one stem is drawn once and cloned rotated, scaled and mirrored for every stem, so even many stems are fast (up to 200, evenly all around or in random directions with random size; rotate stems tilts each around its base, mirror texture swaps left/right, front/back or both); the strands carry the region's picture stretched along the curve, “Region only” leaves out its surroundings) |
| Bubbles | Foam over the picture: the start mask (pixels between two thresholds by brightness, saturation, hue …) first merges into one large area (merge closes gaps), on which bubbles appear and grow over the course of the steps (count, bubble growth, max. radius, over the edge) until they press against each other – they never overlap but share straight walls (wall pressure); now and then a wall bursts and two become one larger bubble (merging). After that ever smaller bubbles fill the gaps (fill gaps, smallest bubble). Every bubble bulges the picture like a sphere or the bloat/pucker brush of a liquify tool – middle enlarged, squeezed towards the walls, seamless up to the wall – the larger, the stronger (bulge, share reversed, size matters). Light: every bubble is lit as a sphere – light direction and height, shading, highlight (gloss, gloss size) and rim light. Iridescence: colors like a thin film (soap bubble, oil) via a gradient of their own (film colors), stronger towards the rim, with color bands and swirling streaks. “Light on” switches shading, gloss and rim light off in one click. Anti-aliasing (off, 2 × 2, 4 × 4) samples walls, rims and highlights several times; “Show mask and bubbles” shows the start mask, the area and the bubble walls |
| Differential Growth | Curves grow and fold into bulges, like brain folds or fungi: small rings (seeds, seed size) on the threshold range (merge closes gaps) become closed curves; every point is pulled to the middle of its neighbors (smoothness) and pushed away by all points nearby, also from other curves (repulsion, fold size); pieces that are too long split, some at random (growth) – only where there is still room, so nothing overlaps. The curves stay inside the area (stay in area), max. points limits the computing time. Shown as inflated, lit bulges (inflate until they touch, bulge radius, light direction, height, shading, gloss) with metal in the joints (joints, joint colors – copper, joint width); surface: the picture dragged along (every spot shows the picture from the origin of its piece of curve, softly stretched), the picture itself or a color; edges anti-aliased; “Show mask and curves” |
| Erosion | Hydraulic erosion with raindrops on the picture as a landscape, optionally only in drawn regions (arrows as in Flow, with region width; the water can also flow along the arrows; flow direction also “Random” with a random hilly landscape): over several generations drops run downhill – by the picture height (bright or dark is up) or at a fixed angle, with the picture's relief deflecting them –, wash out material, smear the colors along their way and dig channels that later drops follow; strength, rain, path length, smooth terrain, darken channels; precision 1, 2 or 4 px (coarser is much faster; how much original detail stays in the eroded spots is adjustable) |
| Erosion Fast | The picture as a landscape (brightness = height, tilted at an angle, along the drawn arrows or a random hilly landscape), optionally only in drawn regions: a branching river network from fine streams to wide rivers that digs in deeper over several generations (stream power); the colors are dragged downstream, large rivers furthest, the eroded landscape is lit in relief, the rivers can be drawn in dark, bright or in color; computed on a reduced grid (detail), so it's fast even on large images |

### Patterns & Generative

| Effect | What it does |
| --- | --- |
| Noise | Directional noise (Perlin, fractal, ridged, Worley, value, white, Voronoi) in a direction 0–360°; along the direction size, stretch, detail and contrast change from start to end values. The noise only changes pixel values, it doesn't move anything (that's what Displace is for): blend, threshold, cut-out, hue/saturation/lightness (HSL), overlay, difference, swap channels, random values, RGB values, invert, color steps. Where it mixes is decided by the picture (brightness, darkness or saturation above the threshold, the edge roughened by the noise) or the noise itself. “Picture shapes noise” makes the noise pattern follow the picture's shapes; the noise color “Picture color” only lightens or darkens the pixels |
| Color pattern | Geometric patterns from the most striking colors of the picture: stripes, checkerboard, triangles, hexagons, dots, diamonds, zigzag, rings, Truchet |
| Geometric | Op-art patterns, black and white by default: angles, woven check, meander, nested diamonds and squares, triangle bands, cubes, shards, maze, Y pattern; number of bands, line width and (Y pattern) line length adjustable, also in the picture's colors |
| Moiré filter | Adds moiré the way it really appears, or amplifies existing moiré: *Photographed screen* (the picture on RGB subpixels, sampled by a slightly rotated and scaled camera), *Halftone* (halftone of dots, lines, grid or rings; in color with one screen each for cyan, magenta, yellow at the classic angles, with slightly different screen rulings), *Overlay grid* (a fine line grating over the picture, optionally a second one, offset per channel in color), *Aliasing* (unfiltered sampling on a rotated grid folds fine textures into coarse bands), *Amplify existing moiré* (band-pass between grid pitch and band width, amplified; “Moiré only” leaves out the fine lines); grid pitch to 0.1 px, twist to 0.01°, deviation, anti-aliasing off for extra aliasing, softness, strength |
| Generative | Generative line patterns in creative-coding style: bands (wandering, dented shapes of hairlines), flow lines (along noise or the picture's brightness contours, optionally filling evenly and denser in dark areas – an engraving look), moiré rings, mirror tiles; colors from the picture, the picture palette, pastel rainbow or one color |
| Grid | Grid modules as in the book “Generative Gestaltung” (P.2.1): dot screen, concentric circles, rays, hatching, stripes, moiré grid; density, size or direction follow the brightness of every cell |
| Characters | Character shader: the picture from ASCII (fine/simple), blocks, dots, strokes, binary, Matrix katakana or your own text; cell size, letter spacing (kerning), line spacing, font, size by brightness, color from the picture or a single color |
| Spectroscope | Stacked lines as on the cover of “Unknown Pleasures”: every line piles up with the picture's brightness (or darkness, or pure noise) and hides the ones behind it; line spacing, height, peaks, spikes, point spacing, smoothing, emphasize middle, line width, lines in one color or the picture color, background color/transparent/original picture |
| Particles | Simplifies the picture into color areas (blobs, degree via “Abstraction”) and rebuilds it from geometric particles (circles, squares, triangles, hexagons, strokes); all particles of an area are the same size, larger areas give larger particles; particle size, size variation, color variety (random original colors of the area), density, distribution, orientation, background including the blob picture |
| UV texture | Texture placement via UV coordinates: two freely chosen channels (red, green, blue, alpha, brightness, hue, saturation; each invertible) are X (U) and Y (V) from 0 to 1 – like a UV pass from 3D, a gradient or a generated field. On top lies a pattern: checkerboard, stripes, dots, grid, rings, waves, bricks, hexagons, noise, gradient or the picture itself (UV remap); repeats, line width, two colors or a gradient. Shift, rotate and scale the placement. Smoothing averages the UV channels first (soft gradients, no 8-bit steps); “Black is no UV” leaves out the background without it distorting the UVs next to it. Mix: replace, multiply, soft light; anti-aliasing 2 × 2 or 4 × 4 with UVs interpolated between the pixels |

Color pattern and Geometric anti-alias their edges (several samples per pixel, adjustable) and can
show a gradient instead of picture or black-and-white colors: “Gradient” runs across the picture from
color 1 to color 2 (angle adjustable), “Gradient per band” colors nested patterns band by band; between
the lines lies the background color. “Stripe gradient” makes every pair of stripes run softly from
color 1 (top edge of the dark stripe) to color 2 (bottom edge of the light stripe).

### Material & Optics

| Effect | What it does |
| --- | --- |
| Glass | Patterned glass, as seen through a glass block wall or reeded glass: every kind of glass is a height profile whose slope refracts the picture and catches the light – glass blocks (pillows, show the picture shrunk and mirrored), reeded glass (round rods), waves, prisms, honeycomb, crystal (randomly tilted facets), dimples, panes (architectural glass: tall panes of different widths, slightly offset, with frames); reeded glass is the default. Colored glass can be mixed into every kind: every piece of glass – pane, block, rod, cell, facet – gets a color from a gradient (glass colors, colorfulness), the edges glow in their color (edge glow), slanted reflections lie on top (reflection); two patterns over each other mix their colors like overlapping colored glass; per pattern size, refraction (negative: reversed) and angle. Two patterns can be combined, their slopes add up (e.g. blocks with reeds, panes with reeded glass). Offset shifts the picture randomly in every piece of glass, mostly along the stripes, as with architectural glass. Plus dispersion (rainbow fringes), frosting, glass color and tint, joints light or dark, light with shading and gloss; joints and facet edges anti-aliased |
| Metal | Turns everything into metal: the picture's brightness becomes a relief (bright is high; embossing, roundness, invertible), with a surface on top – smooth, brushed (direction), hammered, grainy or spun. Every pixel reflects an environment along its slope, in perspective, so flat spots get a gradient too: chrome horizon (sky above, dark ground below; horizon height, rotation), studio with softboxes, stripes or the picture itself. Metals: chrome, silver, gold, copper, bronze, brass, steel, tempered (temper colors of heated steel depending on the height) or a custom color; picture color tints the metal in the picture's colors (like anodized). Towards grazing angles the reflection gets brighter and whiter (Fresnel); roughness blurs it and widens the gloss; light with gloss and shading; anti-aliasing (2 × 2, 4 × 4 or 8 × 8) where neighboring pixels differ a lot – horizon, highlights, steep edges –, with the slope interpolated between the pixels, the threshold is adjustable; transparency stays |
| Hologram | Holographic foil: the picture is split into areas (as for Particles), every area gets a random tilt and depth; when the card is turned (rotation, rotation axis) the foil colors travel across the areas, deeper areas shimmer faster, front and back ones shift against each other (parallax in px, mirrored or repeated at the area's edge, never with parts of other areas), smooth edges; rainbow, gold, diamond or a custom gradient of three colors; sparkle stars in every style that travel with their area and flash when turned (amount up to packed tightly, size); stripes in various shapes (lines, waves, rings, squares, diamonds, hexagons, rays, spiral), optionally repeated as tiles, strength, mix (screen, overlay, color), highlights, embossed edges; embossed patterns in the areas (stars, crosses, circles, dots, diamonds, mixed) with size, density and strength; glow of the bright parts in three levels with radius, color from foil and picture, color fringe and light streaks (length, angle); filmic tone mapping with exposure, highlights and shadows |
| Optics | Lens defects around a chosen optical center: distortion (barrel or pincushion, optionally filling the picture), chromatic aberration (red/cyan or spectral), edge blur, vignette (strength, size, softness); glitches: shattered lens (shards), jagged cracks that branch like lightning (from top to bottom up to radiating from the impact, jaggedness and branching adjustable), Fresnel rings, facets like an insect's eye, colored ghosts of bright spots; bright or dark break lines of adjustable width with RGB shift and blur towards the edges; gradient in every piece of glass towards the edges, random tilt of every piece with lighting (light direction) and refraction |
| TV | CRT television with VHS tape: curvature, rounded corners, vignette, scanlines, pixel grid (aperture grille, shadow mask or slot mask), color fringe towards the edge, bloom of bright spots, noise; VHS errors: color bleed, line jitter, waves, tracking errors with snow, head switching at the bottom edge |

### Depth (2.5D)

| Effect | What it does |
| --- | --- |
| Z-Map | Estimates a depth map from the picture (white near, black far), without an AI model: from sharpness (sharp is near), position (low is near), brightness, colorfulness (far is pale) and the middle, each weighted and also negative; smoothed edge-preserving (guided filter along the picture's edges; smooth, edge fidelity), depth contrast, invertible. Fog and Camera wiggle have the same depth sliders, there with “Show Z-map” |
| Fog | Fog by the estimated depth: the further away, the more fog color – linear or exponential between start and end, density, ground fog (denser at the bottom or top), wisps, haze blur and aerial perspective (far things lose their color) |
| Camera wiggle | 2.5D by the estimated depth: the camera moves, near things shift more than far ones and hide what lies behind them; the focal plane stays still. Offset (one view), wiggle (views along a line over each other, with trail), orbit (views around a circle) or anaglyph for red/cyan glasses; camera X/Y (how far and where – can also be dragged with the handle in the picture), views |

For hue, almost grey pixels have no hue and are never selected.

For Pixelsort and Pixelbleed the block size only coarsens the changed spots, the rest stays at full
resolution. “Random” splits the picture into stripes with a block size of their own.

“Max. length” of Pixelsort and Pixelbleed as well as the “Overhang” of Pixelsort reach up to the
longer side of the canvas.

Effects with randomness are reproducible; “Reroll” gives a different result.

## Masks

- **Brush / selection**: paint into the mask with tools or select parts of the picture.
  The left mouse button adds, the right mouse button or Alt subtracts.
  - *Brush*: size, hardness, strength.
  - *Rectangle*, *Ellipse*, *Lasso*: drag out or trace a region, with an adjustable soft edge.
  - *Magic wand*: a click selects similar colors of the original picture (tolerance, contiguous only or in the whole picture).
  - “From picture brightness”: bright parts of the picture get the effect, dark ones don't.
- **Linear gradient**: drag in the picture; full effect at the white point, none at the black one.
- **Radial gradient**: drag in the picture; full effect in the middle, none from the circle's edge on.
- **Hard edge**: makes every mask black/white; from the threshold up the effect works fully, below not at all.
  Together with “From picture brightness” this gives a sharp brightness selection.
- Invert inverts the mask.
- “Show mask” (toolbar or Ctrl+M) shows and hides the red overlay; the mask works either way.

Changes to only the mask, opacity or blend mode don't recompute the effect itself.

## Projects

`Ctrl+S` saves a `.glitchr` project: a ZIP with `project.json` (all layers, settings,
random seeds, gradients, positions), the images as PNG and every painted mask as a greyscale PNG
under `masks/`. So the project is complete even if the original images are moved.
Every image is stored once under `images/`, even if several layers use it.
Projects can be loaded via Open, by drag & drop or as a start argument; projects from
older versions (one source image) open with that image as the lowest image layer.

## Build and run

Requires Java 21 or newer.

```
mvn package
java -jar target/GlitchR.jar [image or project.glitchr] [more images as layers …]
```

For very large images with many layers give it more memory: `java -Xmx8g -jar target/GlitchR.jar`.

## Controls

| Action | Key |
| --- | --- |
| New empty canvas (without an image) | Ctrl+N |
| Open an image or project (new document) | Ctrl+O |
| Insert an image as a layer / from the clipboard | Ctrl+I / Ctrl+V (or drag it into the window) |
| Save project / save as | Ctrl+S / Ctrl+Shift+S |
| Export image (PNG, JPEG) | Ctrl+E |
| Undo / redo | Ctrl+Z / Ctrl+Y (or Ctrl+Shift+Z) |
| Copy result (with transparency) | Ctrl+Shift+C or “Copy” in the toolbar |
| Duplicate / delete layer | Ctrl+J / Del |
| Layer up / down | Ctrl+PgUp / Ctrl+PgDn |
| Show mask | Ctrl+M |
| Show original | Ctrl+B |
| Zoom | Ctrl+mouse wheel, Ctrl+Plus/Minus, Ctrl+0 (fit), Ctrl+1 (100 %) |
| Pan the view | Space + drag or middle mouse button |

Undo covers all changes to layers, effect settings and masks, brush strokes included,
as well as opening and applying; one slider drag or one brush stroke is one step (up to 60 steps).

Copy puts the result on the clipboard as a PNG with transparency (the format browsers,
Affinity, Photoshop and GIMP use too) and additionally on white for programs without PNG. Paste likewise
reads PNG first, so transparency from other programs is kept.

“Layer → Apply all layers to the image” replaces the stack with one image layer holding the result.
