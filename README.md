# GlitchR

Destruktive Glitch-Filter als Ebenen, in Kotlin (Swing + FlatLaf).

Der Ebenenstapel enthält **Bildebenen** und **Effektebenen**. Effekte wirken auf die nächste
Bildebene darunter (in der Liste eingerückt darüber angezeigt); das Bild samt seinen Effekten
wird dann über die Ebenen darunter gelegt. So lassen sich mehrere Bilder mit je eigenen Effekten
stapeln. Jede Ebene hat Deckkraft, Mischmodus und eine Maske.

## Bildebenen

- Das erste Bild (Öffnen, Strg+O) legt die Leinwandgrösse fest.
- Weitere Bilder kommen als neue Bildebene dazu: ins Fenster ziehen (auch mehrere auf einmal),
  „+ Bild…“ / Strg+I oder Strg+V. Grössere Bilder werden eingepasst, alle zentriert.
- Ist eine Bildebene ausgewählt, verschiebt Ziehen im Bild sie, die Eckgriffe skalieren sie
  (Seitenverhältnis bleibt). Ist ihre Maske eingeblendet („Maske zeigen“, Strg+M), bearbeitet
  Ziehen stattdessen die Maske; zum Verschieben die Maske ausblenden oder Strg halten.
- Lange Dateinamen werden als Ebenenname gekürzt (Anfang…Ende); umbenennen geht im Panel.
- Im Eigenschaften-Panel: X, Y, Skalierung, „Glatt skalieren“ (aus = harte Pixel) und die
  Knöpfe Einpassen, Füllen, Originalgrösse, Zentrieren.
- Ausserhalb der Bilder ist die Leinwand durchsichtig; PNG-Export behält das, JPEG legt es auf Weiss.
- Die Maske einer Bildebene schneidet das Bild aus, *bevor* die Effekte darüber wirken; die Effekte
  können also über die Maskenkante hinaus laufen. Masken von Effektebenen legen dagegen fest, wo der
  jeweilige Effekt sichtbar ist.
- Die Masken einer Bildebene und ihrer Effekte gehören zum Bild: sie wandern und skalieren mit.
  Gemalte Masken haben die Auflösung des Bilds; ausserhalb des Bilds gilt der Randwert der Maske.
- Effekte können über den Bildrand hinaus in die leere Leinwand wirken: Pixelbleed läuft weiter,
  Pixelsort zieht Strecken, die an den Rand stossen, um den „Überstand“ hinaus, verschobene Pixel
  (RGB-Distort, Zeilenversatz, Block-Glitch, Datamosh) nehmen ihre Deckkraft mit, und bei den
  JPEG-Artefakten wird auch die Transparenz komprimiert, sodass die Blöcke ausfransen.
- Verschieben, Duplizieren und Löschen einer Bildebene nimmt ihre Effekte mit.
- „Original“ (Strg+B) zeigt alle Bildebenen ohne Effekte.

## Effekte

| Effekt | Was er macht |
| --- | --- |
| Pixelsort | Sortiert Pixelstrecken zwischen zwei Helligkeitsschwellen, in beliebigem Winkel, nach Helligkeit/Farbton/Sättigung/R/G/B; Blockgrösse 1–32 px oder zufällig; Überstand über den Bildrand |
| Pixelbleed | Portierung von `pixelbleed_06.py`: helle (oder dunkle) Pixel laufen in zufällig langen Streifen aus und verlaufen zu einer wählbaren Zielfarbe; Blockgrösse 1–32 px oder zufällig |
| JPEG-Artefakte | Echte JPEG-Kompression, mehrere Durchgänge, grössere Blöcke (Nearest Neighbour oder bilinear), zufällig kaputte Bytes, volle Farbauflösung 4:4:4, Nachschärfen |
| Datamosh | Makroblöcke werden über mehrere „Frames“ verschleppt wie in einem Video ohne Keyframes (Fluss, Zufall, entlang der Helligkeit oder eine Richtung) |
| RGB-Distort | Kanäle einzeln verschieben, Sinuswelle, Kanalreihenfolge tauschen |
| Zeilenversatz | Zufällige Streifen seitlich verschieben, optional nur ein Kanal |
| Slitscan | Schneidet das Bild in Zeilen (Grösse und Winkel einstellbar) und verschiebt jede entlang ihrer Richtung und mit „Versatz Y“ quer dazu, sodass das Bild durch die Zeilen läuft; der Versatz (positiv oder negativ) wächst von der ersten zur letzten Zeile linear, exponentiell (mit einstellbarer Kurve) oder zufällig; Rand wiederholen, strecken oder spiegeln; das Bild lässt sich vorher in X und Y verschieben (wiederholt sich am Rand) |
| Verschieben | Verschiebt das Bild in X und Y, am Rand wiederholt es sich; spiegeln in X und Y |
| Blur | Weichzeichner: Gauss, Box, Richtung (Winkel), Radial (Zoom) und Drehung um eine wählbare Mitte; alle Kanäle, nur Rot/Grün/Blau/Alpha, nur die Helligkeit oder nur die Farbe; Datenfehler: Überlauf (Summen springen zurück), Zeilenbreite (falsch gelesene Zeilenlänge schert das Bild), Bitfehler (kippende Bits in Streifen), Verschleppung (die laufende Summe wird zwischen den Zeilen nicht zurückgesetzt) |
| Scharfzeichnen | Schärfen, Unscharf maskieren (Stärke −300 bis 500 %, negativ weicht es auf; Radius, Schwelle) und Clarity (lokaler Kontrast in den Mitteltönen); RGB oder nur Helligkeit; „Übersteuern“ treibt die Schärfung ins Glitchige: an den Kanten schiesst die Helligkeit oder die Sättigung hoch, die Werte laufen über oder Rot und Blau laufen als bunte Säume auseinander |
| Block-Glitch | Rechtecke verschieben, Kanäle tauschen, invertieren, verschmieren, als grosser Pixel oder mit Medianfilter |
| Bitcrush | Weniger Bits pro Kanal, Dithering, Pixelgrösse |
| Partikel | Vereinfacht das Bild zu Farbflächen (Blobs, Grad über „Abstraktion“) und baut es daraus mit geometrischen Partikeln neu auf (Kreise, Quadrate, Dreiecke, Sechsecke, Striche); alle Partikel einer Fläche sind gleich gross, grössere Flächen geben grössere Partikel; Partikelgrösse, Grössen-Zufall, Farbvielfalt (zufällige Originalfarben der Fläche), Dichte, Verteilung, Ausrichtung, Hintergrund inkl. Blob-Bild |
| Displace | Verschiebt die Bildpixel entlang von Noise (Perlin, Fraktal, Ridged, Worley, Wert, Weiss); mit einer Verlaufsmaske stufenlos |
| Flow | Mit der Maus eine Flussrichtung ins Bild zeichnen (Pfeile auf der Leinwand, rechte Maustaste/Alt wischt Striche weg); das Bild wandert entlang der Richtung (Versatz −400 bis 400 %, 100 % = eine komplette Wiederholung) – ganz mit Wiederholung am Rand, in Abschnitten, die immer wieder von vorn beginnen, oder als Schleife in einem Band entlang der Pfeile (was an der Pfeilspitze ankommt, springt an den Anfang), mit harten oder weichen Kanten; der Strömung folgen oder direkt wie ein UV-Offset; Reichweite der Striche, abseits fortsetzen oder stillstehen, Grundrichtung ohne Striche |
| Erosion | Hydraulische Erosion mit Regentropfen auf dem Bild als Landschaft, auf Wunsch nur in eingezeichneten Bereichen (Pfeile wie bei Flow, mit Bereich-Breite; das Wasser kann auch den Pfeilen entlang fliessen; Flussrichtung auch „Zufall“ mit zufälliger Hügellandschaft): über mehrere Generationen fliessen Tropfen bergab – nach der Bildhöhe (hell oder dunkel ist oben) oder in einem festen Winkel, wobei das Relief des Bildes sie ablenkt –, waschen Material aus, verschmieren die Farben entlang ihres Weges und graben Rinnen, denen spätere Tropfen folgen; Stärke, Regen, Weglänge, Gelände glätten, Rinnen abdunkeln; Rechengenauigkeit 1, 2 oder 4 px (gröber ist viel schneller; wie viel Originaldetail in den erodierten Stellen bleibt, ist einstellbar) |
| Erosion Fast | Das Bild als Landschaft (Helligkeit = Höhe, in einem Winkel gekippt, den gezeichneten Pfeilen entlang oder eine zufällige Hügellandschaft), auf Wunsch nur in eingezeichneten Bereichen: ein verästeltes Flussnetz von feinen Bächen bis zu breiten Flüssen, das sich über mehrere Generationen tiefer eingräbt (Stream Power); die Farben werden flussabwärts gezogen, grosse Flüsse am weitesten, die erodierte Landschaft wird plastisch beleuchtet, die Flüsse lassen sich dunkel, hell oder farbig einzeichnen; wird auf einem verkleinerten Raster berechnet (Detail), dadurch schnell auch bei grossen Bildern |
| Noise | Gerichteter Noise (Perlin, Fraktal, Ridged, Worley, Wert, Weiss, Voronoi) in einer Richtung 0–360°; entlang der Richtung ändern sich Grösse, Streckung, Detail und Kontrast von Anfangs- zu Endwerten. Der Noise ändert nur Pixelwerte, er verschiebt nichts (dafür gibt es Displace): Überblenden, Schwelle, Ausschneiden, Farbton/Sättigung/Helligkeit (HSL), Overlay, Differenz, Kanäle tauschen, Zufallswerte, RGB-Werte, Invertieren, Farbstufen. Wo gemischt wird, entscheidet das Bild (Helligkeit, Dunkelheit oder Sättigung über der Schwelle, die Kante vom Noise aufgeraut) oder der Noise selbst. „Bild formt Noise“ lässt das Noise-Muster den Bildformen folgen; die Noise-Farbe „Bildfarbe“ hellt die Pixel nur auf oder dunkelt sie ab |
| Farbmuster | Geometrische Muster aus den markantesten Bildfarben: Streifen, Schachbrett, Dreiecke, Sechsecke, Punkte, Rauten, Zickzack, Ringe, Truchet |
| Geometrisch | Op-Art-Muster, standardmässig schwarzweiss: Winkel, Karo gewebt, Mäander, Rauten und Quadrate verschachtelt, Dreiecksbänder, Würfel, Scherben, Labyrinth, Y-Muster; Anzahl der Bänder und Strichstärke einstellbar, auch in den Bildfarben |

Farbmuster und Geometrisch glätten die Kanten (mehrere Stichproben je Pixel, einstellbar) und können
statt Bild- oder Schwarzweissfarben einen Verlauf zeigen: „Verlauf“ läuft über das Bild von Farbe 1 zu
Farbe 2 (Winkel einstellbar), „Verlauf je Band“ färbt verschachtelte Muster Band für Band durch; zwischen
den Linien liegt die Hintergrundfarbe. „Streifenverlauf“ lässt jedes Streifenpaar weich von Farbe 1
(Oberkante des dunklen Streifens) zu Farbe 2 (Unterkante des hellen Streifens) laufen.
| Kaleidoskop | Faltet das Bild in 2–32 Segmente; Drehung, Quellwinkel, Versatz, Mitte, Zoom, Spiegeln; bis zu 6 Stufen „Faltung in der Faltung“ mit eigener Segmentzahl, Abstand, Drehung und Skalierung |
| Generativ | Generative Linienmuster im Creative-Coding-Stil: Bänder (wandernde, verbeulte Formen aus Haarlinien), Fliesslinien (entlang von Noise oder der Helligkeitskonturen des Bilds, auf Wunsch gleichmässig füllend und an dunklen Stellen dichter – Kupferstich-Look), Moiré-Ringe, Spiegelkacheln; Farben aus dem Bild, der Bildpalette, pastell-regenbogen oder eine Farbe |
| Raster | Rastermodule wie im Buch „Generative Gestaltung“ (P.2.1): Punktraster, konzentrische Kreise, Strahlen, Schraffur, Streifen, Moiré-Gitter; Dichte, Grösse oder Richtung folgen der Helligkeit jeder Zelle |
| Zeichen | Zeichenshader: das Bild aus ASCII (fein/einfach), Blöcken, Punkten, Strichen, Binär, Matrix-Katakana oder eigenem Text; Zellgrösse, Zeichenabstand (Kerning), Zeilenabstand, Schrift, Grösse nach Helligkeit, Farbe aus dem Bild oder einfarbig |
| Joy Division | Gestapelte Linien wie auf dem Cover von „Unknown Pleasures“: jede Linie türmt sich mit der Bildhelligkeit (oder Dunkelheit, oder reinem Noise) auf und verdeckt die dahinter liegenden; Linienabstand, Höhe, Spitzen, Zacken, Punktabstand, Glätten, Mitte betonen, Linienstärke, Linien in einer Farbe oder in Bildfarbe, Hintergrund Farbe/transparent/Originalbild |
| Optik | Objektivfehler um eine wählbare optische Mitte: Wölbung (tonnen- oder kissenförmig, auf Wunsch bildfüllend), chromatische Aberration (Rot/Cyan oder spektral), Randunschärfe, Vignette (Stärke, Grösse, Weichheit); Glitches: zersprungene Linse (Scherben), zackige Risse, die sich wie Blitze verzweigen (von oben nach unten bis strahlenförmig aus dem Einschlag, Zackigkeit und Verzweigung einstellbar), Fresnel-Ringe, Facetten wie ein Insektenauge, farbige Geisterbilder heller Stellen; helle oder dunkle Bruchkanten in einstellbarer Stärke mit RGB-Verschiebung und Unschärfe zu den Kanten hin; Verlauf in jedem Glasstück zu den Kanten, zufällige Neigung jedes Stücks mit Beleuchtung (Lichtrichtung) und Brechung |
| Hologramm | Holografische Folie: das Bild wird in Flächen zerlegt (wie bei Partikel), jede Fläche bekommt eine zufällige Neigung und Tiefe; beim Drehen der Karte (Drehung, Drehachse) wandern die Folienfarben über die Flächen, tiefere Flächen schillern schneller, vordere und hintere verschieben sich gegeneinander (Parallaxe in px, am Flächenrand gespiegelt oder wiederholt, nie mit Teilen anderer Flächen), Kanten glätten; Regenbogen, Gold, Diamant oder eigener Verlauf aus drei Farben; Funkelsterne in allen Stilen, die mit ihrer Fläche wandern und beim Drehen aufblitzen (Menge bis dicht an dicht, Grösse); Streifen in verschiedenen Formen (Linien, Wellen, Ringe, Quadrate, Rauten, Sechsecke, Strahlen, Spirale), auf Wunsch als Kacheln wiederholt, Stärke, Mischen (Aufhellen, Overlay, Farbe), Glanzlichter, Prägekanten; geprägte Muster in den Flächen (Sterne, Kreuze, Kreise, Punkte, Rauten, gemischt) mit Grösse, Dichte und Stärke; Glow der hellen Teile in drei Stufen mit Radius, Farbe aus Folie und Bild, Farbsaum und Lichtstreifen (Länge, Winkel); filmisches Tonemapping mit Belichtung, Lichtern und Schatten |
| TV | Röhrenfernseher mit VHS-Band: Wölbung, runde Ecken, Vignette, Scanlines, Pixelraster (Streifen-, Loch- oder Schlitzmaske), Farbsaum zum Rand, Leuchten heller Stellen, Rauschen; VHS-Fehler: Farbbluten, Zeilenzittern, Wellen, Trackingfehler mit Schnee, Kopfumschaltung am unteren Rand |

Bei Pixelsort und Pixelbleed macht die Blockgrösse nur die veränderten Stellen grob, der Rest bleibt in voller
Auflösung. „Zufällig“ teilt das Bild in Streifen mit je eigener Blockgrösse.

„Max. Länge“ von Pixelsort und Pixelbleed sowie der „Überstand“ von Pixelsort reichen bis zur
längeren Seite der Leinwand.

Effekte mit Zufall sind reproduzierbar; „Neu würfeln“ gibt ein anderes Ergebnis.

## Masken

- **Pinsel / Auswahl**: mit Werkzeugen in die Maske malen oder Bildteile auswählen.
  Linke Maustaste fügt hinzu, rechte Maustaste oder Alt zieht ab.
  - *Pinsel*: Grösse, Härte, Stärke.
  - *Rechteck*, *Ellipse*, *Lasso*: Bereich aufziehen bzw. umfahren, mit einstellbarer weicher Kante.
  - *Zauberstab*: Klick wählt ähnliche Farben des Originalbilds (Toleranz, nur zusammenhängend oder im ganzen Bild).
  - „Aus Bildhelligkeit“: helle Bildteile bekommen den Effekt, dunkle nicht.
- **Linearer Verlauf**: im Bild ziehen; voller Effekt am weissen Punkt, keiner am schwarzen.
- **Radialer Verlauf**: im Bild ziehen; voller Effekt in der Mitte, keiner ab dem Kreisrand.
- **Harte Kante**: macht jede Maske schwarz/weiss; ab der Schwelle wirkt der Effekt voll, darunter gar nicht.
  Zusammen mit „Aus Bildhelligkeit“ ergibt das eine scharfe Helligkeitsauswahl.
- Umkehren kehrt die Maske um.
- „Maske zeigen“ (Toolbar oder Strg+M) blendet die rote Markierung ein und aus; die Maske wirkt in beiden Fällen.

Nur Masken-, Deckkraft- oder Mischmodus-Änderungen berechnen den Effekt selbst nicht neu.

## Projekte

`Strg+S` speichert ein `.glitchr`-Projekt: ein ZIP mit `project.json` (alle Ebenen, Einstellungen,
Zufallswerte, Verläufe, Positionen), den Bildern als PNG und jeder gemalten Maske als Graustufen-PNG
unter `masks/`. Das Projekt ist damit vollständig, auch wenn die Originalbilder verschoben werden.
Jedes Bild wird unter `images/` einmal gespeichert, auch wenn mehrere Ebenen es nutzen.
Projekte lassen sich über Öffnen, per Drag & Drop oder als Startargument laden; Projekte aus
älteren Versionen (ein Ausgangsbild) öffnen mit diesem Bild als unterster Bildebene.

## Bauen und starten

Benötigt Java 21 oder neuer.

```
mvn package
java -jar target/GlitchR.jar [bild oder projekt.glitchr] [weitere bilder als ebenen …]
```

Für sehr grosse Bilder mit vielen Ebenen mehr Speicher geben: `java -Xmx8g -jar target/GlitchR.jar`.

## Bedienung

| Aktion | Taste |
| --- | --- |
| Bild oder Projekt öffnen (neues Dokument) | Strg+O |
| Bild als Ebene einfügen / aus Zwischenablage | Strg+I / Strg+V (oder ins Fenster ziehen) |
| Projekt speichern / speichern unter | Strg+S / Strg+Umschalt+S |
| Bild exportieren (PNG, JPEG) | Strg+E |
| Rückgängig / Wiederholen | Strg+Z / Strg+Y (oder Strg+Umschalt+Z) |
| Ergebnis kopieren | Strg+Umschalt+C |
| Ebene duplizieren / löschen | Strg+J / Entf |
| Ebene nach oben / unten | Strg+Bild↑ / Strg+Bild↓ |
| Maske anzeigen | Strg+M |
| Original zeigen | Strg+B |
| Zoom | Strg+Mausrad, Strg+Plus/Minus, Strg+0 (einpassen), Strg+1 (100 %) |
| Ansicht verschieben | Leertaste + Ziehen oder mittlere Maustaste |

Rückgängig umfasst alle Änderungen an Ebenen, Effekt-Einstellungen und Masken, auch Pinselstriche,
sowie Öffnen und Anwenden; ein Schieberegler-Zug oder ein Pinselstrich ist ein Schritt (bis zu 60 Schritte).

„Ebene → Alle Ebenen aufs Bild anwenden“ ersetzt den Stapel durch eine Bildebene mit dem Ergebnis.
