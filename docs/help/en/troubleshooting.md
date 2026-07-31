# Troubleshooting

<!-- screenshot: docs/help/en/troubleshooting-hero.png -->

Common issues and their solutions. If your problem isn't listed here, check the [GitHub Issues](https://github.com/xiaojieonly/Ehviewer_CN_SXJ/issues) page or ask in the [Telegram group](https://t.me/+WyclP8pPlk-JfbwS).

---

## Connection & Login

### Cannot log in / "Login failed"

| Possible Cause | Solution |
|----------------|----------|
| Incorrect credentials | Double-check your username and password on the E-Hentai website first |
| ExHentai requires separate account | ExHentai access requires a separate account with sufficient account age |
| IP blocked | E-Hentai may temporarily block your IP — wait a few hours and try again |
| Cookie issues | Clear the app's data/cache in Android Settings → Apps → EhViewer → Storage |

### "Cannot connect" / Pages won't load

| Possible Cause | Solution |
|----------------|----------|
| No internet connection | Check your Wi-Fi or mobile data |
| DNS issues | Try switching between Wi-Fi and mobile data |
| Site is down | Check if e-hentai.org is accessible in a browser |
| Proxy/VPN needed | In some regions, you may need a proxy to access E-Hentai — configure one in Settings |
| Firewall blocking | Corporate or school networks may block E-Hentai — try a different network |

### Host IP Address Banned

E-Hentai occasionally bans IP addresses. Solutions:

1. Wait for the ban to expire (usually a few hours).
2. Switch to a different network (mobile data vs. Wi-Fi).
3. Use a VPN or proxy configured in the app's network settings.

---

## Downloads

### Downloads stuck at 0% / not starting

| Possible Cause | Solution |
|----------------|----------|
| Too many concurrent downloads | Reduce the concurrent download count in Settings → Download |
| Network interruption | Check your connection; downloads resume automatically when connectivity returns |
| Server throttling | E-Hentai rate-limits downloads — wait a few minutes and retry |
| Storage full | Check available device storage |

### Downloads fail repeatedly

1. Long-press the failed download and check the error message.
2. Common errors:
   - **403 Forbidden**: Your IP may be temporarily blocked. Wait and retry.
   - **404 Not Found**: The gallery may have been removed.
   - **Timeout**: Network issue — check connection and retry.
3. Use **Start All** from the toolbar to retry all failed downloads at once.

### Downloaded files are corrupt / won't open

- Try re-downloading the gallery.
- Check if your device storage is healthy (no filesystem errors).
- Ensure you have enough free space for the full gallery.

---

## Reader / Viewer

### Images are blurry or low quality

| Possible Cause | Solution |
|----------------|----------|
| Still loading | Wait for the image to fully load — a progress indicator may be visible |
| Zoom mode | Switch to **Original** or **Fit Width** zoom mode in reader settings |
| Source quality | Some galleries have inherently low-resolution source images |

### Reader is slow / laggy

| Possible Cause | Solution |
|----------------|----------|
| Large images | Very high-resolution images take longer to decode |
| Too many background downloads | Pause downloads while reading |
| Low device RAM | Close other apps to free memory |
| Animated images (GIF/APNG) | Animated images are heavier — the "wait for animation" setting may cause delays |

### Volume keys don't work for page turn

1. Open reader settings (tap center of screen).
2. Enable **Volume Key Page Turn**.
3. If keys work but in the wrong direction, enable **Reverse Volume Keys**.

### Screen turns off while reading

Enable **Keep Screen On** in the reader settings dialog.

---

## Favorites & History

### Favorites not syncing

| Possible Cause | Solution |
|----------------|----------|
| Not logged in | Ensure you're signed in to your E-Hentai account |
| Using local favorites | Only cloud favorites sync — local favorites are device-only |
| Network issue | Check connection and pull down to refresh |

### History not recording

- History recording must be enabled in **Settings → Privacy**.
- If the security lock is active, history may not be recorded depending on settings.

---

## Performance

### App is slow or crashes

| Possible Cause | Solution |
|----------------|----------|
| Large cache | Clear cache in Settings → Advanced → Clear Cache |
| Outdated app | Update to the latest version from GitHub Releases |
| Low storage | Free up device storage |
| Device too old | Older devices with limited RAM may struggle with large galleries |

### App uses too much storage

1. Go to **Settings → Advanced → Clear Cache** to remove temporary files.
2. Manage downloaded galleries — delete ones you no longer need.
3. Check the download directory size in Settings → Download.

---

## Search

### Search returns no results

| Possible Cause | Solution |
|----------------|----------|
| Too many filters | Remove category filters and advanced search restrictions |
| Typo in search term | Check spelling, especially for tag searches |
| Tag syntax incorrect | Use `namespace:value` format (e.g. `artist:wanyu`) |
| ExHentai-only content | Some content is only on ExHentai — switch sites if needed |

### Image search doesn't work

- Ensure the image is clear and representative of the content you're looking for.
- Very generic images (landscapes, abstract art) won't produce good results.
- Toggle **Similarity Scan** for broader matching.

---

## General Tips

1. **Keep the app updated**: New versions fix bugs and improve stability. Check [GitHub Releases](https://github.com/xiaojieonly/Ehviewer_CN_SXJ/releases) regularly.
2. **Clear cache periodically**: Accumulated cache can cause slowdowns.
3. **Check the FAQ**: The [official FAQ](https://github.com/xiaojieonly/Ehviewer_CN_SXJ/blob/BiLi_PC_Gamer/feedauthor/EhviewerIssue.md) covers many additional questions.
4. **Report bugs**: If you find a reproducible bug, report it on [GitHub Issues](https://github.com/xiaojieonly/Ehviewer_CN_SXJ/issues) with steps to reproduce.

---

## Getting Help

| Channel | Link |
|---------|------|
| GitHub Issues | [Report bugs and request features](https://github.com/xiaojieonly/Ehviewer_CN_SXJ/issues) |
| Telegram Group | [Community discussion](https://t.me/+WyclP8pPlk-JfbwS) |
| Telegram Channel | [Release announcements](https://t.me/Ehviewer_xiaojieonly_channel) |
| FAQ | [Common questions](https://github.com/xiaojieonly/Ehviewer_CN_SXJ/blob/BiLi_PC_Gamer/feedauthor/EhviewerIssue.md) |

---

> Last updated: July 2026
