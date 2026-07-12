# E-Hentai Search Syntax Guide

E-Hentai's search system is based on a **NameSpace + Tags** design.

## Namespace Prefixes

| Prefix | Namespace | Description |
|--------|-----------|-------------|
| `a:` | artist | Artist/Creator |
| `c:` | character | Character |
| `cos:` | cosplayer | Cosplayer |
| `f:` | female | Female tags |
| `g:` | group | Group/Circle |
| `l:` | language | Language |
| `m:` | male | Male tags |
| `x:` | mixed | Mixed |
| `o:` | other | Other |
| `p:` | parody | Parody/Series |
| `r:` | reclass | Reclass |

---

## 1. Tag Search vs Title Search

- `f:milf` (equivalent to `female:milf`) — searches **tags only** for `female:milf`
- `milf` — searches **both tags and titles** containing `milf`

> Searching titles will additionally return galleries with `milf` in the title but not in tags.

---

## 2. AND Logic (Intersection, Default)

Search terms separated by **spaces** form an intersection (commas do NOT work as separators).

```
f:milf m:muscle
```

Finds galleries that have **both** `f:milf` and `m:muscle`.

---

## 3. NOT Logic (Exclusion)

The `-` prefix excludes results.

```
pokemon -furry
```

Searches for `pokemon` but excludes all galleries with `furry` in tags or titles.

---

## 4. Multi-Word Tags

Use double quotes `""` for tags with multiple words.

```
f:"big breasts" f:"sex toys"
```

Exactly matches the `big breasts` and `sex toys` tags.

> Without quotes, `f:big breasts` is parsed as two separate conditions: `f:big` AND `breasts`.

---

## 5. Exact Match Suffix

The `$` suffix forces an exact match.

```
c:sakura$
```

Matches only the tag `c:sakura`, excluding variants like `c:sakura kinomoto`.

```
f:big$
```

Matches exactly `big`, not `big breasts` etc.

---

## 6. Wildcards

- `*` matches zero, one, or multiple arbitrary characters
- `?` and `_` match exactly one arbitrary character

```
comic*xo*4
```

Matches `comicxo4`, `comicYYYYYYYxoKKKKKKK4`, etc.

---

## 7. Search Modes

This app supports the following search modes:

- **Normal Search** — search both tags and titles
- **Tag Search** — search specific tags only
- **Uploader Search** — search galleries by a specific uploader
- **Subscription Search** — search subscribed galleries

---

## 8. Advanced Search Options

- Limit search scope: gallery name, tags, description, torrent filenames, etc.
- Set minimum rating and page count range
- Filter by category (Doujinshi, Manga, CG, etc.)

---

## 9. Tag Chips

- When typing keywords, the system automatically suggests matching tags
- Tap a suggestion to add it as a **tag chip**
- Chips display translated names but search using the original English terms
- Tap the **×** on a chip to remove it
- Free text input supports arbitrary search queries
- All chips are combined with free text when searching
