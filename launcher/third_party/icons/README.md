# Vendored line icons

Consistent 24x24, 2px round-stroke icon set converted to Android VectorDrawables.

## Sources and licenses
- Lucide (ISC): https://github.com/lucide-icons/lucide - see `LICENSE-lucide`
- Tabler Icons (MIT), only `laurel-wreath` (outline): https://github.com/tabler/tabler-icons - see `LICENSE-tabler`

## Layout
- `svg/` original SVGs (Tabler HTML comment header stripped)
- `vd/` generated `ic_<name>.xml` VectorDrawables (stroke white, width 2, round caps/joins, no fill)
- `preview.html` human contact sheet of the SVGs

## Regenerate
```
python3 tools/svg2vd.py
```
To (re)generate the app's drawables (single source of truth, no hand-edited copies in res/drawable):
```
python3 tools/svg2vd.py --out launcher/app/src/main/res/drawable
```
copies exactly the icons listed in `app-icons.txt` (stale `ic_*` from this set are removed).

(stdlib only; run from repo root). Circles/ellipses become two arcs, rects (incl. rx) and lines/polylines become path data.

## Icons
- cloud-drizzle
- cloud-fog
- cloud-hail
- cloud-lightning
- cloud-moon-rain
- cloud-moon
- cloud-off
- cloud-rain-wind
- cloud-rain
- cloud-snow
- cloud-sun-rain
- cloud-sun
- cloud
- ethernet-port
- laurel-wreath
- layout-grid
- moon
- settings
- shield-lock
- star
- sun
- wifi-high
- wifi-low
- wifi-off
- wifi-zero
- wifi
