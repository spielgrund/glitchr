# GlitchR

Destruktive Glitch-Filter als Ebenen, in Kotlin (Swing + FlatLaf).

Der Ebenenstapel enthält **Bildebenen**, **Generator-Ebenen** und **Effektebenen**. Effekte wirken
auf die nächste Bild- oder Generator-Ebene darunter (in der Liste eingerückt darüber angezeigt); das
Bild samt seinen Effekten wird dann über die Ebenen darunter gelegt. So lassen sich mehrere Bilder mit
je eigenen Effekten stapeln. Jede Ebene hat Deckkraft, Mischmodus und eine Maske.

## Bildebenen

- Das erste Bild (Öffnen, Strg+O) legt die Leinwandgrösse fest.
- Weitere Bilder kommen als neue Bildebene dazu: ins Fenster ziehen (auch mehrere auf einmal),
  „+ Bild…“ / Strg+I oder Strg+V. Grössere Bilder werden eingepasst, alle zentriert.
- Ist eine Bildebene ausgewählt, verschiebt Ziehen im Bild sie, die Eckgriffe skalieren sie
  (Seitenverhältnis bleibt), Umschalt + Eckgriff dreht sie um ihre Mitte (mit Strg in 15°-Schritten). Ist ihre Maske eingeblendet („Maske zeigen“, Strg+M), bearbeitet
  Ziehen stattdessen die Maske; zum Verschieben die Maske ausblenden oder Strg halten.
- Lange Dateinamen werden als Ebenenname gekürzt (Anfang…Ende); umbenennen geht im Panel.
- Im Eigenschaften-Panel: X, Y, Skalierung, Drehung, „Glatt skalieren“ (aus = harte Pixel) und die
  Knöpfe Einpassen, Füllen, Originalgrösse, Zentrieren, Drehung zurücksetzen.
- Ausserhalb der Bilder ist die Leinwand durchsichtig; PNG-Export behält das, JPEG legt es auf Weiss.
- Die Maske einer Bildebene schneidet das Bild aus, *bevor* die Effekte darüber wirken; die Effekte
  können also über die Maskenkante hinaus laufen. Masken von Effektebenen legen dagegen fest, wo der
  jeweilige Effekt sichtbar ist.
- Die Masken einer Bildebene und ihrer Effekte gehören zum Bild: sie wandern, skalieren und drehen mit –
  beim Malen, bei Auswahlwerkzeugen, Verlaufsgriffen und der roten Markierung.
  Gemalte Masken haben die Auflösung des Bilds; ausserhalb des Bilds gilt der Randwert der Maske.
- Effekte können über den Bildrand hinaus in die leere Leinwand wirken: Pixelbleed läuft weiter,
  Pixelsort zieht Strecken, die an den Rand stossen, um den „Überstand“ hinaus, verschobene Pixel
  (RGB-Distort, Zeilenversatz, Block-Glitch, Datamosh) nehmen ihre Deckkraft mit, und bei den
  JPEG-Artefakten wird auch die Transparenz komprimiert, sodass die Blöcke ausfransen.
- Verschieben, Duplizieren und Löschen einer Bildebene nimmt ihre Effekte mit.
- „Original“ (Strg+B) zeigt alle Bild- und Generator-Ebenen ohne Effekte.

## Ohne Bild beginnen: Generator-Ebenen

„Neu…“ (Strg+N) legt eine leere Leinwand in beliebiger Grösse an (mit Vorlagen wie HD, 4K, Quadrat,
A4 300 dpi) und startet sie auf Wunsch gleich mit einem Generator. „+ Generator ▾“ bzw.
„Ebene → Generator hinzufügen“ fügt eine Generator-Ebene über der ausgewählten Gruppe ein; ohne
offenes Dokument wird zuerst nach der Leinwandgrösse gefragt.

Eine Generator-Ebene erzeugt ein Bild in Leinwandgrösse und verhält sich sonst wie eine Bildebene:
Effekte darüber wirken darauf, ihre Maske schneidet das erzeugte Bild aus, Verschieben, Duplizieren
und Löschen nehmen die Effekte mit. Jeder Generator ausser Gradient hat eine **Grundfarbe** (die Fläche, auf der das
Muster entsteht); Einstellungen, die nur mit einem Bild Sinn ergeben (z. B. „Bild formt Noise“,
„Bildeinfluss“), sind ausgeblendet. Wo ein Muster trotzdem nach dem Bild fragt (Bildfarben,
Hintergrund „Originalbild“), bekommt es die Grundfarbe.

| Generator | Was er erzeugt |
| --- | --- |
| Farbfläche | Eine einfarbige Fläche, als Grundlage für Effekte |
| Gradient | Farbverlauf linear, linear gespiegelt, radial, Winkel, Raute oder Quadrat; zwei Farben und optional eine Mittelfarbe (Lage einstellbar), Winkel, Mitte, Grösse, Wiederholungen (auf Wunsch gespiegelt), Verteilung, harte Stufen, Dithering gegen Streifen |
| Moiré | Zwei Muster übereinander (je Linien, Ringe, Strahlen, Spirale, Gitter, Punkte, Schachbrett oder Zonenplatte) mit eigenem Abstand auf 0,1 px, Strichstärke, Winkel auf 0,01°, Mitte, Stretch X/Y (entlang der eigenen Achsen des Musters, Ringe werden zu Ellipsen) und Welle; gemischt als Überdrucken (die Linien liegen auf dem Hintergrund, an Kreuzungen mischen sich ihre Farben wie Druckfarben), B deckt A, Differenz (XOR), Licht oder nur Überschneidung, drei Farben; „Nur Moiré“ zeichnet weich, bis nur noch die Moiré-Bänder bleiben (auf Wunsch mit gestrecktem Kontrast); Kantenglättung aus (hartes Aliasing), 3×3 oder 5×5 |
| Mandala | Konzentrische Ringe, jeder mit einem Motiv (Blätter, Punkte, Bögen, Zacken, Tropfen, Strahlen, Rauten), das sich symmetrisch um die Mitte wiederholt; Symmetrie 3–48, Anzahl Ringe, Motiv fest oder gemischt, Ringbreite nach aussen, Zufall der Ringbreiten, Verzierung (innere Konturen, Adern, Punkte), Trennkreise, Drehung, Mitte; Stil Linien, gefüllt oder gefüllt mit Kontur, Strichstärke; Farben einfarbig, abwechselnd, Verlauf innen → aussen, Regenbogen, Pastell; Hintergrund farbig oder transparent. „Neu würfeln“ gibt ein neues Mandala mit denselben Einstellungen |
| Spirograph | Wie die Mandala-Schablone: ein Zahnrad rollt innen im Zahnring oder aussen herum, der Stift im Loch zeichnet die Kurve; mit ganzen Zähnezahlen schliesst sie sich von selbst (nach Rad ÷ ggT(Ring, Rad) Umläufen); Loch 0–150 % (über 100 % Schlaufen), Anteil der Kurve; mehrere Durchgänge, je weitergedreht und/oder mit dem Stift in einem anderen Loch; Grösse, Drehung, Mitte, Strichstärke, Deckkraft; Farben einfarbig, Verlauf oder Regenbogen je Durchgang oder entlang der Kurve |
| Noise | Der Noise-Effekt, schwarzweiss voreingestellt (Farben änderbar, Fraktal), mit allen Noise-Arten, Richtung, Grösse, Streckung, Detail und Kontrast |
| Farbmuster | Streifen, Schachbrett, Dreiecke, Sechsecke, Punkte, Rauten, Zickzack, Ringe, Truchet – als Verlauf zwischen zwei Farben |
| Geometrisch | Die Op-Art-Muster, schwarzweiss oder als Verlauf; beim Y-Muster ist die Strichlänge einstellbar (bis 300 %: die Arme laufen durch die Nachbarfelder bis auf die andere Seite) |
| Feedback | Video-Feedback: Kopien des Bilds immer wieder übereinander (1–100 Schritte); jeder Schritt skaliert (50–200 %), dreht und versetzt die vorige Kopie um eine wählbare Mitte – der Versatz wird mitgedreht und mitskaliert, so entstehen Tunnel und Spiralen; Rand der Kopien transparent, wiederholt, gespiegelt oder gestreckt; je Schritt Farbton, Sättigung und Helligkeit (HSL) verschieben; Kopien über dem Bild oder das Bild über den Kopien, Mischmodus, Deckkraft, Abklingen je Schritt; zum Schluss auf Wunsch die erste Kopie oder das Original noch einmal darüber (eigene Deckkraft), damit wachsende Kopien das Motiv nicht zudecken. Quelle *Bild*, *Kanten* (Farbkanten je Kanal, Schwelle, Stärke, Linienbreite; gefärbt in Bildfarbe, einer Farbe oder nach Kantenrichtung) oder *Blobs*: das Bild wird wie bei Hologramm in Farbflächen zerlegt (Abstraktion, Mindestgrösse), jeder Blob bekommt sein eigenes Feedback um seine Mitte – über 100 % wächst es aus den Blobs heraus, darunter läuft es in sie hinein – als Kontur (gleich breit in jeder Kopie; Blobfarbe, eine Farbe oder Farbton aus der Richtung zur Mitte), Fläche oder Bildinhalt; oder *Alphakante* (nur die Kontur). Die Alphakante – entlang der durchsichtigen Stellen des Bilds und des Bildrands, samt der Maske der Bildebene, weil sie das Bild vor den Effekten ausschneidet – lässt sich auch in den anderen Modi zeichnen („Alphakante zeichnen“): dann rahmt sie das Bild und jede Kopie ein. Sie hat eine Strichstärke (0,5–200 px, runde Ecken aus exakten Abständen), Lage aussen, mittig oder innen (Standard; am Bildrand ist nur der innere Teil sichtbar) und Färbung in einer Farbe, der Bildfarbe an der Kante oder dem Farbton aus der Richtung; Untergrund Bild, Schwarz, Weiss oder transparent |
| Generativ | Bänder, Fliesslinien, Moiré-Ringe, Spiegelkacheln entlang von Noise, pastell-regenbogen voreingestellt |
| Raster | Gleichmässige Rastermodule: Punkte, Kreise, Strahlen, Schraffur, Streifen, Moiré-Gitter |
| Spektroskop | Gestapelte Noise-Linien wie ein Spektrogramm (oder das „Unknown Pleasures“-Cover), Mitte betont |
| Zeichen | Eigener Text (oder ein Zeichensatz) als Muster über die ganze Fläche |

Moiré-Ideen: gleiche Linien mit ein, zwei Grad Unterschied geben breite Streifen; zwei Ringmuster mit leicht
verschobener Mitte geben Hyperbeln; zwei Abstände wie 8,0 und 8,4 px geben Schwebungen; zwei Zonenplatten
nebeneinander geben gerade Streifen.

Jeder Generator hat zudem eine *Position*: das erzeugte Bild lässt sich verschieben, skalieren und um die
Mitte der Leinwand drehen; am Rand bleibt es transparent (Standard), wiederholt sich, wird gespiegelt oder
gestreckt. Die Masken der Generator-Ebene und ihrer Effekte wandern, skalieren und drehen mit.

Im Projekt werden nur die Einstellungen gespeichert; das Bild entsteht beim Öffnen neu. Die Ebenenliste
zeigt eine Vorschau des erzeugten Bilds.

## Effekte

| Effekt | Was er macht |
| --- | --- |
| Pixelsort | Sortiert Pixelstrecken zwischen zwei Helligkeitsschwellen, in beliebigem Winkel, nach Helligkeit/Farbton/Sättigung/R/G/B; Blockgrösse 1–32 px oder zufällig; Überstand über den Bildrand |
| Pixelbleed | Portierung von `pixelbleed_06.py`: Pixel über (oder unter) der Schwelle laufen in zufällig langen Streifen aus – Schwelle nach Mittelwert RGB (wie im Skript), Helligkeit, hellstem/dunkelstem Kanal, Sättigung, Buntheit, Farbton oder einem Farbkanal und verlaufen zu einer wählbaren Zielfarbe; Blockgrösse 1–32 px oder zufällig |
| Pixelstretch | Zieht Pixel, deren Wert zwischen zwei Schwellen liegt (Helligkeit, Mittelwert RGB, hellster oder dunkelster Kanal, Sättigung, Buntheit, Farbton – auch über Rot hinweg, z. B. 230–20 –, Rot, Grün oder Blau), in beliebigem Winkel um eine Länge (mit Längen-Zufall) in die Länge; die Pixel einer Strecke bleiben dabei in ihrer Reihenfolge. *Überlagern* (wie Pixelbleed): die gezogene Strecke liegt über den folgenden Pixeln, abgedeckte Pixel werden nicht selbst gezogen. *Schieben* (wie Pixelsort): die folgenden Pixel werden um die Länge weitergeschoben, am Bildrand in die leere Leinwand hinein. „Max. Strecke“ begrenzt, wie viele Pixel zusammen gezogen werden (Standard 1 = jeder einzeln, 0 = die ganze Strecke); Blockgrösse 1–32 px oder zufällig |
| JPEG-Artefakte | Echte JPEG-Kompression, mehrere Durchgänge, grössere Blöcke (Nearest Neighbour oder bilinear), zufällig kaputte Bytes, volle Farbauflösung 4:4:4, Nachschärfen |
| Datamosh | Makroblöcke werden über mehrere „Frames“ verschleppt wie in einem Video ohne Keyframes (Fluss, Zufall, entlang der Helligkeit oder eine Richtung) |
| RGB-Distort | Kanäle einzeln verschieben, Sinuswelle, Kanalreihenfolge tauschen |
| Zeilenversatz | Zufällige Streifen seitlich verschieben, optional nur ein Kanal |
| Slitscan | Schneidet das Bild in Zeilen (Grösse und Winkel einstellbar) und verschiebt jede entlang ihrer Richtung und mit „Versatz Y“ quer dazu, sodass das Bild durch die Zeilen läuft; der Versatz (positiv oder negativ) wächst von der ersten zur letzten Zeile linear, exponentiell (mit einstellbarer Kurve) oder zufällig; Rand wiederholen, strecken oder spiegeln; das Bild lässt sich vorher in X und Y verschieben (wiederholt sich am Rand) |
| Verschieben | Verschiebt das Bild in X und Y, am Rand wiederholt es sich; spiegeln in X und Y |
| Transformieren | Verschieben (auf 0,1 px), skalieren (1–1000 %, dazu Stretch X/Y), drehen (auf 0,1°) und spiegeln um eine wählbare Mitte; am Rand transparent, wiederholt, gespiegelt oder gestreckt; glatt oder harte Pixel |
| Blur | Weichzeichner: Gauss, Box, Richtung (Winkel), Radial (Zoom) und Drehung um eine wählbare Mitte; alle Kanäle, nur Rot/Grün/Blau/Alpha, nur die Helligkeit oder nur die Farbe; Datenfehler: Überlauf (Summen springen zurück), Zeilenbreite (falsch gelesene Zeilenlänge schert das Bild), Bitfehler (kippende Bits in Streifen), Verschleppung (die laufende Summe wird zwischen den Zeilen nicht zurückgesetzt) |
| Scharfzeichnen | Schärfen, Unscharf maskieren (Stärke −300 bis 500 %, negativ weicht es auf; Radius, Schwelle) und Clarity (lokaler Kontrast in den Mitteltönen); RGB oder nur Helligkeit; „Übersteuern“ treibt die Schärfung ins Glitchige: an den Kanten schiesst die Helligkeit oder die Sättigung hoch, die Werte laufen über oder Rot und Blau laufen als bunte Säume auseinander |
| Farbkorrektur | *Belichtung und Tonemapping*: Belichtung in Blendenstufen im linearen Licht, Tonemapping Reinhard, Filmisch (ACES) oder Hable; Highlight-Kompression (−200 bis 200 %) ab einem Knie, je Kanal (positiv regelt die Lichter weich herunter und macht sie blasser, negativ dehnt sie, bis sie Kanal für Kanal ausbrennen und der Kontrast stark steigt – die Farbtöne kippen dabei); Tonwerte (Schwarzpunkt, Weisspunkt, Gamma), Kontrast, Lichter, Tiefen. *HSL*: Farbton, Helligkeit, Dynamik und Sättigung bis 1000 % – wie früher in Photoshop wird jeder Kanal vom Grau des Pixels weggeschoben, hohe Werte übersteuern in knallige, ausbrennende Farben; über die volle Sättigung hinaus werden die Kanäle abgeschnitten (hartes Übersteuern), oder die Sättigung beginnt wieder bei Grau (Überlauf, Farbbänder) bzw. läuft zurück (Spiegeln), der Farbton bleibt; Helligkeit über ihren Bereich hinaus wird abgeschnitten, läuft über (zu hell springt nach dunkel) oder wird zurückgespiegelt. Standard ist neutral (Kontrast und Sättigung 100 %). *Kurven*: Kurveneditor für RGB, Rot, Grün und Blau (klicken setzt einen Punkt, ziehen verschiebt, Rechtsklick entfernt; die Kurve läuft weich ohne Überschwinger). Stärke |
| Ramp | Färbt über einen Farbverlauf (Gradient Map) mit beliebig vielen Farbpunkten – Editor wie in Noiser: klicken fügt einen Punkt hinzu, ziehen verschiebt, Doppelklick wählt die Farbe, Rechtsklick entfernt; Vorlagen (Abendrot, Graustufen, Terrain, Feuer, Ozean, Neon, Toxisch, Duoton, Wärmebild, Regenbogen) und Umkehren. Ramp nach Helligkeit, Mittelwert, hellstem/dunkelstem Kanal, Sättigung, Buntheit, Farbton oder einem Kanal; Mischen: Ersetzen, Nur Farbe (Helligkeit bleibt), Weiches Licht, Overlay, Multiplizieren, Negativ multiplizieren, Differenz; Wiederholungen (gespiegelt oder mit harten Sprüngen), Verschieben, Stärke |
| LAB-Farben | Farben im CIELAB-Farbraum: L (Helligkeit) verschieben, Kontrast, umkehren; a (Grün ↔ Magenta) und b (Blau ↔ Gelb) verschieben und verstärken, negativ vertauscht die Gegenfarben; Buntheit (0 % = grau, Standard 150 %) und Farbton drehen in LCh – vor den Verschiebungen, so lässt sich ein entsättigtes Bild tönen (z. B. Sepia); Kanäle tauschen (a ↔ b, L ↔ a, L ↔ b, rotieren), in Stufen schneiden; Farben ausserhalb des Bildschirm-Farbraums abschneiden oder überlaufen lassen; Stärke |
| Flächen füllen | Findet Flächen im Bild und füllt jede anders. Verfahren: Gleiche Farbe (zusammenhängende Pixel mit ähnlicher Farbe – erkennt jede Kachel eines Generator-Musters; verglichen mit dem Nachbarpixel, so bleiben Verläufe in einer Kachel ganz, oder mit der Startfarbe der Fläche, besser für Fotos; Toleranz), Helligkeitsstufen, Farbton (HSL, Graues für sich, auf Wunsch auch nach Helligkeit getrennt), Blobs (zusammenhängende Stücke eines Schwellenbereichs) oder Kanten (was zwischen den Umrissen liegt); diagonal verbunden, Mindestfläche, kleine Flächen zur Nachbarfläche oder unverändert. Füllung: zufällige Grauwerte, zufällige Farben (Sättigung, Helligkeitsbereich), zufällig aus einem Verlauf, Verlauf nach Grösse oder Helligkeit der Fläche, Mittelwert der Fläche; ersetzen, multiplizieren oder nur Farbe (Helligkeit bleibt), Umrisse, Stärke. Freistellen: die grösste Fläche, alles am Bildrand (meist der Hintergrund), Flächen nach Farbe, die hellste, die dunkelste oder ein zufälliger Anteil werden transparent – oder umgekehrt nur diese bleiben. Kantenglättung (2 × 2 oder 4 × 4 wie beim Glas): an den Grenzen zwischen den Flächen und an freigestellten Rändern wird mehrfach abgetastet – jede Probe gehört zur angrenzenden Fläche mit der ähnlichsten Farbe – und gemittelt. Neu würfeln gibt neue Farben |
| Block-Glitch | Rechtecke verschieben, Kanäle tauschen, invertieren, verschmieren, als grosser Pixel oder mit Medianfilter |
| Bitcrush | Weniger Bits pro Kanal, Dithering, Pixelgrösse |
| Partikel | Vereinfacht das Bild zu Farbflächen (Blobs, Grad über „Abstraktion“) und baut es daraus mit geometrischen Partikeln neu auf (Kreise, Quadrate, Dreiecke, Sechsecke, Striche); alle Partikel einer Fläche sind gleich gross, grössere Flächen geben grössere Partikel; Partikelgrösse, Grössen-Zufall, Farbvielfalt (zufällige Originalfarben der Fläche), Dichte, Verteilung, Ausrichtung, Hintergrund inkl. Blob-Bild |
| Blob-Echo | Zerlegt das Bild in Flächen (wie Partikel) und kopiert alle Flächen – oder nur ein Tortenstück jeder Fläche (1–360°, Richtung wählbar) – in eine wählbare Richtung immer wieder über das Bild, von der grössten zur kleinsten Fläche, bis zum Rand oder in fester Anzahl; sehr grosse Flächen (meist der Hintergrund) lassen sich auslassen; Abstand, Verblassen, Kopien hinter der eigenen Fläche, über allem oder hinter allen Flächen |
| Displace | Verschiebt die Bildpixel entlang von Noise (Perlin, Fraktal, Ridged, Worley, Wert, Weiss); mit einer Verlaufsmaske stufenlos |
| UV-Textur | Texturplatzierung über UV-Koordinaten: zwei frei wählbare Kanäle (Rot, Grün, Blau, Alpha, Helligkeit, Farbton, Sättigung; je umkehrbar) sind X (U) und Y (V) von 0 bis 1 – wie ein UV-Pass aus 3D, ein Verlauf oder ein erzeugtes Feld. Darüber liegt ein Muster: Schachbrett, Streifen, Punkte, Gitter, Ringe, Wellen, Ziegel, Sechsecke, Noise, Verlauf oder das Bild selbst (UV-Remap); Wiederholungen, Linienbreite, zwei Farben oder ein Farbverlauf. Verschieben, Drehen und Skalieren der Platzierung. Glätten mittelt die UV-Kanäle vorher (weiche Verläufe, keine 8-Bit-Stufen); „Schwarz ist kein UV“ lässt den Hintergrund aus, ohne dass er die UVs daneben verfälscht. Mischen: ersetzen, multiplizieren, weiches Licht; Kantenglättung 2 × 2 oder 4 × 4 mit zwischen den Pixeln interpolierten UVs |
| Flow | Mit der Maus eine Flussrichtung ins Bild zeichnen (Pfeile auf der Leinwand, rechte Maustaste/Alt wischt Striche weg); das Bild wandert entlang der Richtung (Versatz −400 bis 400 %, 100 % = eine komplette Wiederholung) – ganz mit Wiederholung am Rand, in Abschnitten, die immer wieder von vorn beginnen, oder als Schleife in einem Band entlang der Pfeile (was an der Pfeilspitze ankommt, springt an den Anfang), mit harten oder weichen Kanten; der Strömung folgen oder direkt wie ein UV-Offset; Reichweite der Striche, abseits fortsetzen oder stillstehen, Grundrichtung ohne Striche |
| Erosion | Hydraulische Erosion mit Regentropfen auf dem Bild als Landschaft, auf Wunsch nur in eingezeichneten Bereichen (Pfeile wie bei Flow, mit Bereich-Breite; das Wasser kann auch den Pfeilen entlang fliessen; Flussrichtung auch „Zufall“ mit zufälliger Hügellandschaft): über mehrere Generationen fliessen Tropfen bergab – nach der Bildhöhe (hell oder dunkel ist oben) oder in einem festen Winkel, wobei das Relief des Bildes sie ablenkt –, waschen Material aus, verschmieren die Farben entlang ihres Weges und graben Rinnen, denen spätere Tropfen folgen; Stärke, Regen, Weglänge, Gelände glätten, Rinnen abdunkeln; Rechengenauigkeit 1, 2 oder 4 px (gröber ist viel schneller; wie viel Originaldetail in den erodierten Stellen bleibt, ist einstellbar) |
| Erosion Fast | Das Bild als Landschaft (Helligkeit = Höhe, in einem Winkel gekippt, den gezeichneten Pfeilen entlang oder eine zufällige Hügellandschaft), auf Wunsch nur in eingezeichneten Bereichen: ein verästeltes Flussnetz von feinen Bächen bis zu breiten Flüssen, das sich über mehrere Generationen tiefer eingräbt (Stream Power); die Farben werden flussabwärts gezogen, grosse Flüsse am weitesten, die erodierte Landschaft wird plastisch beleuchtet, die Flüsse lassen sich dunkel, hell oder farbig einzeichnen; wird auf einem verkleinerten Raster berechnet (Detail), dadurch schnell auch bei grossen Bildern |
| Grow | Ausbreitung wie eine Infektion, nach dem „Point Based Growth Solver“ von Entagma: die Startmaske (Pixel, deren Helligkeit, Sättigung, Farbton … zwischen zwei Schwellen liegt; „Startmaske zeigen“ zeigt sie schwarzweiss) ist angesteckt; in jedem Schritt sammeln die Pixel Ansteckung von ihren angesteckten Nachbarn (Radius, nach Abstand und Richtung gewichtet) – je nach Anfälligkeit: zufällige Immunität je Pixel, ein Noise-Feld und das Bild (Helligkeit, Sättigung … stecken sich leichter oder schwerer an). Tempo und Form: rund (Buchten füllen sich zuerst) bis verästelt (wo es eng wird, wird gebremst – Korallen, Labyrinthe, Kristallausläufer). Jeder Pixel merkt sich, wann, von wem und aus welchem Startpixel er angesteckt wurde. Inhalt: *Verschmieren* (Standard: wie mit dem Wischfinger zieht das Wachstum das ganze Bild in seine Richtung – jede gewachsene Stelle zieht so weit, wie sie gewachsen ist, das Zugfeld läuft über die Reichweite weich aus, so werden auch Motiv und Umgebung mitgezogen; Verschiebung, Reichweite, Wischspuren), *Bild aufdehnen* ( jeder gewachsene Pixel wird zu seinem Startpixel zurückverschoben, die Richtung geglättet – das Bild der Startmaske wird entlang der Wachstumswege auf die neue Fläche gezogen; Dehnung 100 % bis an die Front, weniger verschiebt nur; Glätte von kristallinen Facetten bis fliessend), transportierte Pixel (jeder übernimmt den Inhalt dessen, der ihn angesteckt hat – die Startpixel wachsen hinaus; Textur lässt ihre Textur mitwandern), Wachstumszeit als Farbverlauf (Verlaufseditor, Ringe) oder das Bild entlang der Front enthüllen; Richtung und Richtungsstärke lenken sie; weich und kantengeglättet gezeichnet); nicht Gewachsenes als Bild, schwarz oder transparent; Stärke; Rechengenauigkeit 1, 2 oder 4 px (gröber: schneller, dickere Äste). |
| Hörner | Aus dem Rand eines Bereichs – Schwellenbereich (Helligkeit, Sättigung, Farbton …), Ellipse (Position, Grösse; z. B. um einen Kopf) oder beides; „Startmaske zeigen“ – wachsen Stränge, senkrecht nach aussen oder in eine Richtung geneigt (Richtung, Richtungsstärke). Form *Horn*: sie rollen sich zur Spitze hin zu Spiralen ein; jeder nimmt den Bildquerschnitt an seinem Ansatz mit und zieht ihn als Streifen entlang der Kurve – Anzahl, Länge, Breite, Verjüngung, Einrollen bis drei Windungen, Spiralform von gleichmässigem Bogen bis erst gerade und an der Spitze eng, Drehsinn, Zufall, Textur mitziehen, Rundung, Kantenweiche, Ansatz weich (blendet das Horn am Ansatz weich ein); Hornform *Fächer* (wie ein Spaltblättling: der Strang wird breiter statt spitz, gabelt sich mehrfach in überlappende Äste, die Endlappen öffnen sich keilförmig mit rundem, gewelltem Saum; feine Lamellen laufen längs durch und fächern sich über alle Gabelungen hinweg auf – Auffächern, Verzweigungen, Äste je Gabel, Gabelwinkel, Lamellen, Lamellentiefe, Rüschen, Rüschenwellen, Tönung in einer Farbe mit der Bildhelligkeit als Struktur, eigenes Textur mitziehen; Form *Spaltung* (der ganze Bereich wächst hinaus: in jede Richtung (Stämme) spaltet er sich in zwei Hälften, die sich nach aussen einrollen, aus der Mitte der Spaltung wächst der nächste, grössere Kopf auf einem Stiel und spaltet sich wieder – Stufen, Wachstum je Stufe, Stiel, Kopfbreite; „Vorne“ legt fest, wie der Bereich gelesen wird, so sehen alle Stämme gleich aus – ein Stamm wird einmal gezeichnet und für jeden Stamm gedreht, skaliert und gespiegelt geklont, darum sind auch viele Stämme schnell (bis 200, gleichmässig rundherum oder in zufälligen Richtungen mit zufälliger Grösse; Stämme drehen neigt jeden um seinen Ansatz, Textur spiegeln vertauscht links/rechts, vorne/hinten oder beides); die Stränge tragen das Bild des Bereichs gedehnt entlang der Kurve, „Nur der Bereich“ lässt seine Umgebung weg) |
| Blasen | Schaum über dem Bild: die Startmaske (Pixel zwischen zwei Schwellen nach Helligkeit, Sättigung, Farbton …) verschmilzt zuerst zu einer grossen Fläche (Verschmelzen schliesst Lücken), auf der im Lauf der Schritte Blasen entstehen und wachsen (Anzahl, Blasenwachstum, Max. Radius, Über den Rand), bis sie sich aneinanderdrücken – sie überschneiden sich nie, sondern teilen sich gerade Wände (Wanddruck); ab und zu platzt eine Wand und zwei werden zu einer grösseren (Zusammenschluss). Danach füllen immer kleinere Blasen die Zwischenräume (Zwischenräume füllen, Kleinste Blase). Jede Blase wölbt das Bild wie eine Kugel bzw. der Aufblähen-/Zusammenziehen-Pinsel beim Verflüssigen – Mitte vergrössert, zu den Wänden gestaucht, nahtlos bis an die Wand – je grösser, desto stärker (Wölbung, Anteil umgekehrt, Grösse wirkt). Licht: jede Blase ist als Kugeloberfläche beleuchtet – Lichtrichtung und -höhe, Schattierung, Glanzpunkt (Glanz, Glanzgrösse) und Randlicht. Schillern: Farben wie ein dünner Film (Seifenblase, Öl) über einen eigenen Farbverlauf (Filmfarben), zum Rand hin stärker, mit Farbbändern und wirbelnden Schlieren. „Licht an“ schaltet Schattierung, Glanz und Randlicht auf einen Klick ab. Kantenglättung (aus, 2 × 2, 4 × 4) tastet Wände, Ränder und Glanzpunkte mehrfach ab; „Maske und Blasen zeigen“ zeigt Startmaske, Fläche und Blasenwände |
| Verwirbelung | Farben fliessen wie auf Wasser oder einem Seifenfilm: eine wirbelnde, sich langsam wandelnde Strömung (divergenzfrei – sie faltet und zieht aus, statt zu stauen) zieht die Farben zu feinen, verschlungenen Schlieren aus – Wirbelstärke, Wirbelgrösse, Feinheit, Durchläufe, Wandel; Richtung und Richtungsstärke lassen die Farbe zusätzlich in lange Bahnen treiben. Es fliesst das ganze Bild oder nur ein Schwellenbereich (Helligkeit, Sättigung, Farbton …), weich auslaufend (Weiche, „Bereich zeigen“). Die Schlieren bleiben scharf, weil nur die Herkunft jedes Pixels verfolgt und das Bild erst am Ende einmal gelesen wird; Kantenglättung (aus, 2 × 2, 4 × 4) liest dort mehrfach, wo Schlieren feiner als ein Pixel werden. Filmfarben legen Regenbogenschlieren wie ein Seifen- oder Ölfilm entlang der Helligkeitslinien darüber (eigener Verlauf, Filmbänder) |
| Differential Growth | Kurven wachsen und falten sich zu Wülsten, wie Hirnwindungen oder Pilze: kleine Ringe (Keime, Keimgrösse) auf dem Schwellenbereich (Verschmelzen schliesst Lücken) werden zu geschlossenen Kurven; jeder Punkt wird zur Mitte seiner Nachbarn gezogen (Glätte) und von allen Punkten in der Nähe weggedrückt, auch von anderen Kurven (Abstossung, Faltengrösse); zu lange Stücke teilen sich, einige zufällig (Wachstum) – nur wo noch Platz ist, so dass sich nichts überschneidet. Die Kurven bleiben in der Fläche (An Fläche halten), Max. Punkte begrenzt die Rechenzeit. Dargestellt als aufgeblähte, beleuchtete Wülste (Aufblähen bis sie aneinanderstossen, Wölbungsradius, Lichtrichtung, -höhe, Schattierung, Glanz) mit Metall in den Fugen (Fugen, Fugenverlauf – Kupfer, Fugenbreite); Oberfläche: das Bild mitgezogen (jede Stelle zeigt das Bild vom Ursprung ihres Kurvenstücks, weich gedehnt), das Bild selbst oder eine Farbe; Ränder kantengeglättet; „Maske und Kurven zeigen“ |
| Noise | Gerichteter Noise (Perlin, Fraktal, Ridged, Worley, Wert, Weiss, Voronoi) in einer Richtung 0–360°; entlang der Richtung ändern sich Grösse, Streckung, Detail und Kontrast von Anfangs- zu Endwerten. Der Noise ändert nur Pixelwerte, er verschiebt nichts (dafür gibt es Displace): Überblenden, Schwelle, Ausschneiden, Farbton/Sättigung/Helligkeit (HSL), Overlay, Differenz, Kanäle tauschen, Zufallswerte, RGB-Werte, Invertieren, Farbstufen. Wo gemischt wird, entscheidet das Bild (Helligkeit, Dunkelheit oder Sättigung über der Schwelle, die Kante vom Noise aufgeraut) oder der Noise selbst. „Bild formt Noise“ lässt das Noise-Muster den Bildformen folgen; die Noise-Farbe „Bildfarbe“ hellt die Pixel nur auf oder dunkelt sie ab |
| Farbmuster | Geometrische Muster aus den markantesten Bildfarben: Streifen, Schachbrett, Dreiecke, Sechsecke, Punkte, Rauten, Zickzack, Ringe, Truchet |
| Geometrisch | Op-Art-Muster, standardmässig schwarzweiss: Winkel, Karo gewebt, Mäander, Rauten und Quadrate verschachtelt, Dreiecksbänder, Würfel, Scherben, Labyrinth, Y-Muster; Anzahl der Bänder, Strichstärke und (Y-Muster) Strichlänge einstellbar, auch in den Bildfarben |

Farbmuster und Geometrisch glätten die Kanten (mehrere Stichproben je Pixel, einstellbar) und können
statt Bild- oder Schwarzweissfarben einen Verlauf zeigen: „Verlauf“ läuft über das Bild von Farbe 1 zu
Farbe 2 (Winkel einstellbar), „Verlauf je Band“ färbt verschachtelte Muster Band für Band durch; zwischen
den Linien liegt die Hintergrundfarbe. „Streifenverlauf“ lässt jedes Streifenpaar weich von Farbe 1
(Oberkante des dunklen Streifens) zu Farbe 2 (Unterkante des hellen Streifens) laufen.
| Moiré-Filter | Fügt Moiré so ein, wie es wirklich entsteht, oder verstärkt vorhandenes: *Bildschirm abfotografiert* (das Bild auf RGB-Subpixeln, von einer leicht gedrehten und skalierten Kamera abgetastet), *Druckraster* (Halbton aus Punkten, Linien, Gitter oder Ringen; farbig mit je einem Raster für Cyan, Magenta, Gelb in den klassischen Winkeln, deren Rasterweiten leicht abweichen), *Raster überlagern* (feines Linienraster über dem Bild, optional ein zweites, farbig je Kanal versetzt), *Aliasing* (ungefiltertes Abtasten auf gedrehtem Gitter faltet feine Texturen in grobe Bänder), *Vorhandenes Moiré verstärken* (Bandpass zwischen Rasterweite und Bandbreite, verstärkt; „Nur Moiré“ lässt die feinen Linien weg); Rasterweite auf 0,1 px, Verdrehung auf 0,01°, Abweichung, Kantenglättung aus für zusätzliches Aliasing, Weichheit, Stärke |
| Kaleidoskop | Faltet das Bild in 2–32 Segmente; Drehung, Quellwinkel, Versatz, Mitte, Zoom, Spiegeln; bis zu 6 Stufen „Faltung in der Faltung“ mit eigener Segmentzahl, Abstand, Drehung und Skalierung |
| Generativ | Generative Linienmuster im Creative-Coding-Stil: Bänder (wandernde, verbeulte Formen aus Haarlinien), Fliesslinien (entlang von Noise oder der Helligkeitskonturen des Bilds, auf Wunsch gleichmässig füllend und an dunklen Stellen dichter – Kupferstich-Look), Moiré-Ringe, Spiegelkacheln; Farben aus dem Bild, der Bildpalette, pastell-regenbogen oder eine Farbe |
| Raster | Rastermodule wie im Buch „Generative Gestaltung“ (P.2.1): Punktraster, konzentrische Kreise, Strahlen, Schraffur, Streifen, Moiré-Gitter; Dichte, Grösse oder Richtung folgen der Helligkeit jeder Zelle |
| Zeichen | Zeichenshader: das Bild aus ASCII (fein/einfach), Blöcken, Punkten, Strichen, Binär, Matrix-Katakana oder eigenem Text; Zellgrösse, Zeichenabstand (Kerning), Zeilenabstand, Schrift, Grösse nach Helligkeit, Farbe aus dem Bild oder einfarbig |
| Spektroskop | Gestapelte Linien wie auf dem Cover von „Unknown Pleasures“: jede Linie türmt sich mit der Bildhelligkeit (oder Dunkelheit, oder reinem Noise) auf und verdeckt die dahinter liegenden; Linienabstand, Höhe, Spitzen, Zacken, Punktabstand, Glätten, Mitte betonen, Linienstärke, Linien in einer Farbe oder in Bildfarbe, Hintergrund Farbe/transparent/Originalbild |
| Optik | Objektivfehler um eine wählbare optische Mitte: Wölbung (tonnen- oder kissenförmig, auf Wunsch bildfüllend), chromatische Aberration (Rot/Cyan oder spektral), Randunschärfe, Vignette (Stärke, Grösse, Weichheit); Glitches: zersprungene Linse (Scherben), zackige Risse, die sich wie Blitze verzweigen (von oben nach unten bis strahlenförmig aus dem Einschlag, Zackigkeit und Verzweigung einstellbar), Fresnel-Ringe, Facetten wie ein Insektenauge, farbige Geisterbilder heller Stellen; helle oder dunkle Bruchkanten in einstellbarer Stärke mit RGB-Verschiebung und Unschärfe zu den Kanten hin; Verlauf in jedem Glasstück zu den Kanten, zufällige Neigung jedes Stücks mit Beleuchtung (Lichtrichtung) und Brechung |
| Glas | Strukturglas, wie durch eine Glasbausteinwand oder Riffelglas gesehen: jede Glassorte ist ein Höhenprofil, dessen Neigung das Bild bricht und das Licht fängt – Glasbausteine (Kissen, zeigen das Bild verkleinert und gespiegelt), Wellenglas (Rundstäbe), Wellen, Prismen, Waben, Kristall (zufällig geneigte Facetten), Noppen, Scheiben (Architekturglas: hohe Scheiben unterschiedlicher Breite, leicht versetzt, mit Rahmen); Wellenglas ist voreingestellt. Farbglas lässt sich jeder Sorte beimischen: jedes Glasstück – Scheibe, Baustein, Stab, Wabe, Facette – bekommt eine Farbe aus einem Verlauf (Glasfarben, Farbigkeit), die Kanten leuchten in ihrer Farbe (Kantenleuchten), schräge Reflexe liegen darauf (Spiegelung); zwei Muster übereinander mischen ihre Farben wie überlappendes Farbglas; je Muster Grösse, Brechung (negativ: umgekehrt) und Winkel. Zwei Muster lassen sich kombinieren, ihre Neigungen addieren sich (z. B. Bausteine mit Riffeln, Scheiben mit Wellenglas). Versatz verschiebt das Bild in jedem Glasstück zufällig, vor allem entlang der Streifen, wie bei Architekturglas. Dazu Dispersion (Regenbogensäume), Mattierung, Glasfarbe und Tönung, Fugen hell oder dunkel, Licht mit Schattierung und Glanz; Fugen und Facettenkanten kantengeglättet |
| Metall | Macht alles zu Metall: die Helligkeit des Bilds wird zum Relief (hell ist hoch; Prägung, Rundung, umkehrbar), darauf eine Oberfläche – glatt, gebürstet (Richtung), gehämmert, körnig oder kreisgebürstet. Jeder Pixel spiegelt eine Umgebung entlang seiner Neigung, perspektivisch, so dass auch flache Stellen einen Verlauf haben: Chrom-Horizont (Himmel oben, dunkler Boden unten; Horizonthöhe, Drehung), Studio mit Softboxen, Streifen oder das Bild selbst. Metalle: Chrom, Silber, Gold, Kupfer, Bronze, Messing, Stahl, Angelaufen (Anlauffarben von erhitztem Stahl je nach Höhe) oder eine eigene Farbe; Bildfarbe färbt das Metall in den Farben des Bilds (wie eloxiert). Zu flachen Winkeln hin wird die Spiegelung heller und weisser (Fresnel); Rauheit macht sie unscharf und den Glanz breiter; Licht mit Glanz und Schattierung; Kantenglättung (2 × 2, 4 × 4 oder 8 × 8) dort, wo benachbarte Pixel stark verschieden sind – Horizont, Glanzpunkte, steile Kanten –, mit zwischen den Pixeln interpolierter Neigung, die Schwelle ist einstellbar; Transparenz bleibt |
| Z-Map | Schätzt aus dem Bild eine Tiefenkarte (weiss nah, schwarz fern), ohne KI-Modell: aus Schärfe (Scharfes ist nah), Lage (unten ist nah), Helligkeit, Farbigkeit (Fernes ist blass) und Mitte, einzeln gewichtet und auch negativ; kantenerhaltend geglättet (Guided Filter entlang der Bildkanten; Glätten, Kantentreue), Tiefenkontrast, umkehrbar. Dieselben Tiefenregler haben Nebel und Kamera wackeln, dort mit „Z-Map zeigen“ |
| Nebel | Nebel nach der geschätzten Tiefe: je weiter weg, desto mehr Nebelfarbe – linear oder exponentiell zwischen Beginn und Ende, Dichte, Bodennebel (unten oder oben dichter), Schwaden, Dunst-Unschärfe und Luftperspektive (Fernes verliert die Farbe) |
| Kamera wackeln | 2.5D nach der geschätzten Tiefe: die Kamera bewegt sich, Nahes verschiebt sich mehr als Fernes und verdeckt, was dahinter liegt; die Fokusebene bleibt stehen. Versetzt (eine Ansicht), Wackeln (Ansichten entlang einer Linie übereinander, mit Nachziehern), Kreisen (Ansichten rund um einen Kreis) oder Anaglyph für eine Rot/Cyan-Brille; Kamera X/Y (wie weit und wohin – auch mit dem Anfasser im Bild zu ziehen), Ansichten |
| Hologramm | Holografische Folie: das Bild wird in Flächen zerlegt (wie bei Partikel), jede Fläche bekommt eine zufällige Neigung und Tiefe; beim Drehen der Karte (Drehung, Drehachse) wandern die Folienfarben über die Flächen, tiefere Flächen schillern schneller, vordere und hintere verschieben sich gegeneinander (Parallaxe in px, am Flächenrand gespiegelt oder wiederholt, nie mit Teilen anderer Flächen), Kanten glätten; Regenbogen, Gold, Diamant oder eigener Verlauf aus drei Farben; Funkelsterne in allen Stilen, die mit ihrer Fläche wandern und beim Drehen aufblitzen (Menge bis dicht an dicht, Grösse); Streifen in verschiedenen Formen (Linien, Wellen, Ringe, Quadrate, Rauten, Sechsecke, Strahlen, Spirale), auf Wunsch als Kacheln wiederholt, Stärke, Mischen (Aufhellen, Overlay, Farbe), Glanzlichter, Prägekanten; geprägte Muster in den Flächen (Sterne, Kreuze, Kreise, Punkte, Rauten, gemischt) mit Grösse, Dichte und Stärke; Glow der hellen Teile in drei Stufen mit Radius, Farbe aus Folie und Bild, Farbsaum und Lichtstreifen (Länge, Winkel); filmisches Tonemapping mit Belichtung, Lichtern und Schatten |
| TV | Röhrenfernseher mit VHS-Band: Wölbung, runde Ecken, Vignette, Scanlines, Pixelraster (Streifen-, Loch- oder Schlitzmaske), Farbsaum zum Rand, Leuchten heller Stellen, Rauschen; VHS-Fehler: Farbbluten, Zeilenzittern, Wellen, Trackingfehler mit Schnee, Kopfumschaltung am unteren Rand |

Beim Farbton haben fast graue Pixel keinen Farbton und werden nie ausgewählt.

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
| Neue leere Leinwand (ohne Bild) | Strg+N |
| Bild oder Projekt öffnen (neues Dokument) | Strg+O |
| Bild als Ebene einfügen / aus Zwischenablage | Strg+I / Strg+V (oder ins Fenster ziehen) |
| Projekt speichern / speichern unter | Strg+S / Strg+Umschalt+S |
| Bild exportieren (PNG, JPEG) | Strg+E |
| Rückgängig / Wiederholen | Strg+Z / Strg+Y (oder Strg+Umschalt+Z) |
| Ergebnis kopieren (mit Transparenz) | Strg+Umschalt+C oder „Kopieren“ in der Werkzeugleiste |
| Ebene duplizieren / löschen | Strg+J / Entf |
| Ebene nach oben / unten | Strg+Bild↑ / Strg+Bild↓ |
| Maske anzeigen | Strg+M |
| Original zeigen | Strg+B |
| Zoom | Strg+Mausrad, Strg+Plus/Minus, Strg+0 (einpassen), Strg+1 (100 %) |
| Ansicht verschieben | Leertaste + Ziehen oder mittlere Maustaste |

Rückgängig umfasst alle Änderungen an Ebenen, Effekt-Einstellungen und Masken, auch Pinselstriche,
sowie Öffnen und Anwenden; ein Schieberegler-Zug oder ein Pinselstrich ist ein Schritt (bis zu 60 Schritte).

Kopieren legt das Ergebnis als PNG mit Transparenz in die Zwischenablage (das Format, das auch Browser,
Affinity, Photoshop und GIMP nutzen) und zusätzlich auf Weiss für Programme ohne PNG. Einfügen liest ebenfalls
zuerst PNG, so bleibt die Transparenz aus anderen Programmen erhalten.

„Ebene → Alle Ebenen aufs Bild anwenden“ ersetzt den Stapel durch eine Bildebene mit dem Ergebnis.
