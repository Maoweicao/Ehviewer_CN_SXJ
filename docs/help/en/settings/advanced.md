# Advanced Settings

> Local galleries, debugging, cache, performance monitoring, traffic capture, language & proxy, hosts/DNS, download sorting, and data management.

## Local Gallery

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Scan local galleries on startup | Automatically scan local gallery files on app start | true / false | false |
| Local gallery cache expiry | How long scan cache results are kept | 1 / 3 / 7 / 14 days | 3 days |
| Use cached scan results | Use cached scan results for faster loading | true / false | true |

## Debugging

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Save parse error body | Save pages that fail to parse | true / false | (true in beta builds) |
| User agent | Set the HTTP User-Agent string | — | Chrome 91 UA |
| Save crash log | Save application crash logs | true / false | false |
| Show FAB function name | Show function name on the floating action button | true / false | false |
| Dump logcat | Export system logcat | — | — |
| Export database | Export the application database | — | — |
| Export path | Set the export path for compressed galleries | — | — |
| Compress split size | Split size when exporting compressed archives | 0 / 512 / 1024 / 2048 / 4096 MB | 1024 MB |

## Cache

| Name | Description |
|------|-------------|
| Clear download path cache | Clear cached download path data |
| Clear memory cache | Clear the in-memory cache |

## Performance Monitor

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Enable performance monitor | Enable the app performance monitoring feature | true / false | false |
| Performance monitor log | View performance monitor logs (requires above setting) | — | — |

## Traffic Capture

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Enable traffic capture | Enable network traffic capture | true / false | false |
| Traffic capture page | View captured traffic data (requires above setting) | — | — |

## Cache Size

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Read cache size | Image cache size during reading | 40 / 80 / 160 / 320 / 640 / 1280 / 2560 / 5120 / Unlimited MB | 160 MB |

> **Recommended**: 160–320 MB (adjust based on device storage).

## Language & Proxy

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| App language | Set the app interface language | system / de / en / es / fr / ja / ko / th / zh-CN / zh-HK / zh-TW | zh-CN |
| Proxy | Configure network proxy settings | — | — |

## Hosts & DNS

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Built-in hosts.txt | Use the built-in hosts file | true / false | true |
| Built-in exhentai hosts.txt | Use the built-in ExHentai hosts file | true / false | true |
| Custom hosts.txt | Use a custom hosts file | — | — |
| Secure DNS (DoH) | Use DNS over HTTPS | true / false | false |
| Domain fronting | Enable domain fronting technique | true / false | true |

> **Recommended**: Users in China should enable built-in hosts and domain fronting. Enable Secure DNS when experiencing DNS issues.

## Advanced Download Sort

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Advanced download sort | Enable advanced download sorting | true / false | false |
| Download queue order | Download queue sort order | `0` = Default, `1` = Fewest pages first, `2` = Most pages first, `3` = Type priority | `0` (Default) |
| Gallery type priority order | Drag to reorder type priorities | — | — |
| Secondary sort | Secondary sort when primary is equal | `0` = None, `1` = Fewest pages first, `2` = Most pages first | `0` (None) |

## Download Behavior

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Treat removed as completed | Mark server-removed downloads as completed | true / false | false |
| Prefetch page counts before sort | Fetch page counts before sorting the download queue | true / false | true |
| Prefetch concurrency limit | Maximum concurrent page-count prefetches | 1–10 | 5 |
| Inherit page count from source | Inherit page count from the source gallery | true / false | true |

## Data Management

| Name | Description |
|------|-------------|
| Export data | Export download lists and other app data |
| Import data | Import previously exported data |
| WiFi Send | Send data to other devices over the local network |
| WiFi Receive | Receive data from other devices over the local network |
| Background tasks | View and manage background tasks |
| Background concurrent tasks | Maximum concurrent background tasks (1–32, default 10) |
| Transfer Service | Manage device-to-device transfer service |
| Network Diagnostic | Test network connectivity |
| Record network activity logs | Log network activity (default off) |
| Database Viewer | View application database contents |
| Local Gallery Management | Manage local gallery files |
| Recycle Bin | Manage the recycle bin |
