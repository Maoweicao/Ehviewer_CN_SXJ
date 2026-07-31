# Browsing History

<!-- screenshot: docs/help/en/history-hero.png -->

The History section keeps a record of galleries you've viewed. It's automatically maintained and provides quick access to galleries you've recently browsed.

---

## Accessing History

Open the **navigation drawer** and tap **History** (🕘 icon).

The screen displays your browsing history in a **staggered grid layout**, sorted from most recent to oldest.

---

## History Entry Details

Each history entry shows:

| Element | Description |
|---------|-------------|
| Thumbnail | Gallery cover image |
| Title | Gallery title |
| Uploader | Username of the uploader |
| Rating | Star rating (1–5 stars) |
| Category | Color-coded category tag |
| Posted Date | Original publication date |
| Language | Language indicator |

<!-- screenshot: docs/help/en/history-entries.png -->

---

## Interactions

### Tap

Opens the **gallery detail page** with a smooth thumbnail transition animation.

### Long-Press

Opens a context menu with quick actions:

| Action | Description |
|--------|-------------|
| Download | Add the gallery to your download queue |
| Add to Favorites | Save to a favorites folder |

### Swipe to Delete

**Swipe left** on any history entry to remove it from your history. A brief animation confirms the deletion.

---

## Clearing All History

To delete your entire browsing history:

1. Tap the **menu icon** (⋮) in the toolbar.
2. Select **Clear All History**.
3. Confirm the action in the dialog that appears.

> **Warning:** This action cannot be undone. All browsing history will be permanently deleted.

---

## Empty State

If you have no browsing history (or just cleared it), the screen shows a large icon with a message indicating that no history is available.

---

## Technical Details

- History is stored locally in a SQLite database using GreenDAO.
- Entries are lazy-loaded for smooth scrolling even with large history databases.
- **Fast Scroller** is available for quick navigation through long lists.
- **Shared element transitions** provide smooth animations when opening galleries from the history list.

---

## Tips

- **Privacy**: If you share your device, consider clearing history regularly or using the app's security lock feature.
- **Quick access**: History is often the fastest way to return to a gallery you were reading — no need to search again.
- **Combine with downloads**: Long-press a history entry to quickly download it before you lose track of it.

---

> Last updated: July 2026
