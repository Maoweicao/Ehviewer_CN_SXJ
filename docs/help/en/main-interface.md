# Main Interface

<!-- screenshot: docs/help/en/main-interface-hero.png -->

The main interface is where you spend most of your time — browsing, searching, and discovering galleries. This page covers the gallery list, search system, display modes, and navigation.

---

## Navigation Structure

The app uses a **drawer-based navigation** with a left-side navigation panel and optional right-side drawers that change depending on which screen you're on.

### Left Navigation Drawer

Swipe from the left edge or tap the hamburger icon (☰) to open it.

| Section | Description |
|---------|-------------|
| Homepage | The main gallery feed (default landing page) |
| Subscription | Galleries matching your subscribed tags |
| What's Hot | Currently trending galleries |
| Top Lists | Gallery rankings by popularity |
| Favorites | Your saved galleries (cloud + local) |
| History | Recently viewed galleries |
| Downloads | Download queue manager |
| Settings | App configuration |

At the bottom of the drawer:

- **Quota display** — Shows your current E-Hentai API quota (refreshed each time you open the drawer).
- **Theme button** — Cycles through Light → Dark → AMOLED Black themes.

<!-- screenshot: docs/help/en/main-interface-drawer.png -->

### Right-Side Drawers

Some screens have a secondary drawer on the right:

| Screen | Right Drawer Content |
|--------|---------------------|
| Gallery List | Bookmarks (saved searches) and Subscriptions manager |
| Favorites | Favorites folder selector (10 cloud + 1 local) |
| Downloads | Download label/tag list |

---

## Gallery List

The gallery list is the primary browsing view. It appears on the Homepage, Subscription, What's Hot, and search result screens.

### Display Modes

The gallery list uses a **staggered grid layout** (waterfall/masonry style) that adapts to your screen size. Each card shows:

| Element | Description |
|---------|-------------|
| Thumbnail | Cover image of the gallery |
| Title | Gallery title (may be in original language) |
| Uploader | Username of the person who uploaded it |
| Rating | Star rating (1–5 stars) |
| Category | Color-coded category tag (e.g. Doujinshi, Manga) |
| Posted Date | When the gallery was published |
| Language | Language indicator |

<!-- screenshot: docs/help/en/main-interface-gallery-cards.png -->

### Adjusting Grid Size

Go to **Settings → Read → List Mode** to control the thumbnail size in the grid. Larger thumbnails show more detail; smaller ones let you see more galleries at once.

### Scrolling & Fast Scroll

- **Scroll** vertically to browse.
- **Fast scroll handle** appears on the right edge — drag it to jump quickly through long lists.
- **Pull down** to refresh the current list.

---

## Search

The search bar sits at the top of the gallery list. Tap it to activate.

### Basic Search

Simply type keywords and press Enter. The app searches gallery titles and tags.

### Tag Search

Use E-Hentai's tag syntax for precise results:

| Syntax | Example | What It Does |
|--------|---------|--------------|
| `tag:value` | `artist:wanyu` | Search within a specific tag namespace |
| `"exact phrase"` | `"my hero academia"` | Match the exact phrase |
| `tag1 tag2` | `female:big breasts` | AND logic (both must match) |
| `male:yaoi -female:netorare` | — | Exclude tags with `-` prefix |

### Advanced Search

Tap the **filter icon** in the search bar to expand advanced options:

| Option | Description |
|--------|-------------|
| Category Checkboxes | Filter by 11 categories (Doujinshi, Manga, Artist CG, Game CG, etc.) |
| Search Mode | Switch between normal search and subscription search |
| Minimum Rating | Only show galleries with a certain star rating |
| Advanced Search Table | Fine-tune with options like search name vs. search tags |

### Search Modes

| Mode | How to Activate | Description |
|------|-----------------|-------------|
| Normal | Type in search bar | Keyword/tag search |
| Uploader | Prefix with `uploader:` | Search by uploader name |
| Tag | Prefix with `tag:` | Search by tag |
| Image Search | Use the image icon | Upload an image to find similar galleries |
| Subscription | Switch in search panel | Search within your subscribed tags |

### Image Search

1. Tap the **image search** icon in the search panel.
2. Select an image from your gallery or take a photo.
3. Toggle **Similarity Scan** for more thorough matching.
4. Results are ranked by visual similarity.

<!-- screenshot: docs/help/en/main-interface-search.png -->

---

## Quick Search Bookmarks

The right-side drawer on the gallery list screen lets you save and manage **quick search bookmarks** — named search queries you can access with a single tap.

- **Add a bookmark**: Perform a search, then save it from the drawer.
- **Use a bookmark**: Open the right drawer and tap any saved search.
- **Manage bookmarks**: Long-press to edit or delete.

---

## Subscriptions

The **Subscription** section shows galleries matching tags you've subscribed to on E-Hentai. Manage your subscriptions from the right-side drawer on the Subscription screen.

---

## What's Hot

Shows the current most popular galleries across E-Hentai. No search needed — just browse what's trending.

---

## Top Lists

Access gallery rankings from the navigation drawer:

- Popular by day, week, month, or all time
- Different category breakdowns

---

## Multi-Select Mode

On the gallery list screen, **long-press the floating action button (FAB)** to enter multi-select mode:

1. Tap gallery cards to select/deselect them.
2. Use the FAB menu to perform batch actions (download, favorite, etc.).
3. Long-press the FAB again to exit multi-select mode.

<!-- screenshot: docs/help/en/main-interface-multiselect.png -->

---

## Floating Action Button (FAB)

The FAB provides quick access to common actions:

| Action | Description |
|--------|-------------|
| Tap FAB | Expand to show action options (favorite, download, etc.) |
| Long-press FAB | Enter/exit multi-select mode |
| Scroll behavior | FAB auto-hides when scrolling down and reappears when scrolling up |

---

## Clipboard Monitoring

When you copy an E-Hentai URL (e.g. `https://e-hentai.org/g/12345/abcdef/`), the app detects it automatically and shows a **Snackbar** at the bottom offering to open the gallery. This works even when the app is in the background.

---

## Profile Customization

- **Avatar**: Tap the profile image in the navigation drawer header to change it.
- **Wallpaper**: Tap the background area behind the profile image to set a custom wallpaper. GIF images are supported as animated wallpapers.

---

> Last updated: July 2026
