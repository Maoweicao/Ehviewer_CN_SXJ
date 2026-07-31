# Gallery Viewer

<!-- screenshot: docs/help/en/viewer-hero.png -->

The gallery viewer is the built-in image reader for viewing gallery pages. It supports three reading directions, multiple zoom modes, auto-page-turn, and a range of display options.

---

## Entering the Viewer

You can open a gallery in the reader from several places:

| Entry Point | Description |
|-------------|-------------|
| Gallery Detail Page | Tap the **Read** button |
| Download List | Tap a downloaded gallery |
| URL Link | Open an E-Hentai page URL (`/s/token/gid-page`) |
| Local Folder | Open images from a local directory |
| Archive File | Open a ZIP or CBR file |

---

## Reading Directions

The viewer supports three layout modes:

| Mode | Direction | Best For |
|------|-----------|----------|
| Right to Left (RTL) | ← swipe left to go forward | Manga/comics (default) |
| Left to Right (LTR) | → swipe right to go forward | Western comics, books |
| Top to Bottom (TTB) | ↓ scroll down to go forward | Webtoons, long-strip format |

**To change direction**: Tap the center of the screen to open the settings dialog, then select from the **Reading Direction** dropdown.

<!-- screenshot: docs/help/en/viewer-directions.png -->

---

## Page Navigation

### Gesture Controls

| Gesture | Action |
|---------|---------|
| Swipe left/right | Next/previous page (direction depends on layout mode) |
| Tap screen edge | Turn page (can be disabled) |
| Pinch | Zoom in/out |
| Double-tap | Toggle between fit and original size |

### Volume Keys

Enable **Volume Key Page Turn** in the reader settings to use your device's volume buttons for navigation. You can also **reverse** the volume key direction.

### Keyboard & Mouse

If using a keyboard or mouse (e.g. on a tablet or Chromebook):

| Input | Action |
|-------|--------|
| Page Up / Page Down | Previous / next page |
| Arrow keys | Navigate pages |
| Mouse scroll wheel | Turn pages (direction is configurable) |

---

## Zoom Modes

| Mode | Description |
|------|-------------|
| Original | Display at native resolution |
| Fit | Fit the entire page on screen |
| Fit Width | Scale to fill the screen width |
| Fit Height | Scale to fill the screen height |

**To change zoom**: Open the settings dialog (tap center of screen) and select from the **Page Zoom** dropdown.

---

## Auto Page Turn

The viewer can automatically advance pages at a set interval.

### How to Use

1. Tap the center of the screen to reveal the bottom control panel.
2. Tap the **Play** button to start auto-page-turn.
3. Tap **Pause** to stop.

### Settings

| Setting | Description | Default |
|---------|-------------|---------|
| Static Image Interval | Seconds between page turns for still images | 4 seconds |
| Animated Image Interval | Seconds between page turns for GIF/APNG | 8 seconds |
| Fast Reading Mode | Rapid page turn interval (0.1–1.0 seconds) | Off |
| Wait for Load | Don't advance until the current page is fully loaded | On |
| Show Countdown | Display a countdown timer on the last 15 seconds | Off |

---

## Reader Settings

Open the settings dialog by tapping the **center of the screen**.

### Display Options

| Setting | Type | Description |
|---------|------|-------------|
| Screen Rotation | Dropdown | Auto / Portrait / Landscape / Sensor |
| Reading Direction | Dropdown | L→R / R→L / Top→Bottom |
| Page Zoom | Dropdown | Original / Fit / Fit Width / Fit Height |
| Starting Position | Dropdown | Top-Left / Top-Right / Bottom-Left / Bottom-Right |

### Navigation Options

| Setting | Type | Description |
|---------|---------|-------------|
| Fast Page Turn Time | Slider (0.1–1.0s) | Step interval for fast reading |
| Static Image Turn Time | Number (seconds) | Auto-turn interval for stills |
| Animated Image Turn Time | Number (seconds) | Auto-turn interval for animations |
| Wait for Animation | Switch | Wait for GIF/APNG to finish before turning |
| Show Turn Countdown | Switch | Display countdown timer |
| Disable Tap Turn | Switch | Disable tapping screen edges to turn pages |
| Disable Swipe Turn | Switch | Disable swipe gestures for turning |

### Overlay Options

| Setting | Type | Description |
|---------|------|-------------|
| Keep Screen On | Switch | Prevent screen from sleeping while reading |
| Show Clock | Switch | Display time in the status bar area |
| Show Progress | Switch | Show page number / total pages |
| Show Battery | Switch | Display battery indicator |
| Show Page Gap | Switch | Display separator between pages |
| Volume Key Page Turn | Switch | Use volume buttons to turn pages |
| Reverse Volume Keys | Switch | Swap volume up/down direction |
| Fullscreen | Switch | Hide system UI for immersive reading |
| Custom Brightness | Switch + slider | Override device brightness (0–200%) |

---

## Bottom Control Panel

Tap the bottom area of the screen to reveal:

- **Progress SeekBar** — Drag to jump to any page. Shows "current / total" page numbers.
- **Auto-Turn Controls** — Play/pause button with countdown display.
- **Title Bar** — Shows gallery title with back and menu buttons.

<!-- screenshot: docs/help/en/viewer-controls.png -->

---

## Long-Press Actions

Long-press on any page to open a context menu:

| Action | Description |
|--------|-------------|
| Share Image | Share the current page via Android share sheet |
| Save Image | Save to your device gallery |
| Save As | Choose a custom save location |
| AI Translate | (Experimental) Translate text within the image |

---

## Picture-in-Picture (PiP) Mode

The viewer supports Android's Picture-in-Picture mode for a floating mini-window:

- **Mini controls**: Previous / next page, home, play/pause, and restore-to-fullscreen buttons.
- **Overlay info**: Thumbnail, title, and page progress.

To enter PiP: Use your device's standard PiP gesture (swipe up or press home while in the viewer).

<!-- screenshot: docs/help/en/viewer-pip.png -->

---

## AI Translation (Experimental)

The viewer includes an experimental **AI Translate** feature that can detect and translate text within images.

1. Long-press a page and select **AI Translate**.
2. The app processes the image and overlays translated text.
3. Animated images (GIF/APNG) can optionally be skipped for performance.

> **Note:** This feature is experimental and may not produce accurate results for all languages or image types.

---

## Data Sources

The viewer can display images from different sources:

| Source | Description |
|--------|-------------|
| E-Hentai Online | Stream pages directly from E-Hentai servers |
| Local Folder | View images stored on your device |
| ZIP/CBR Archive | View images from compressed comic archives |

---

> Last updated: July 2026
