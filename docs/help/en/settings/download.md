# Download Settings

> Storage path, speed control, logging, update strategy, download threads, image quality, timeout, sorting, and maintenance.

## Storage

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Download location | Set the storage path for downloaded files | — | — |
| Media scan | Allow the system media scanner to index downloaded files | true / false | false |
| Reset media scan | Re-trigger the system media scan | — | — |

> **Recommended**: Keep media scan off — hides downloads from gallery and other apps.

## Speed Control

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Enable min download speed | Enable minimum download speed detection | true / false | false |
| Min download speed | Reconnect if speed falls below this (KB/s) | 64–1048576 | 64 |

## Logging

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Enable download logging | Record detailed download logs | true / false | false |

> **Recommended**: Enable for debugging, disable for daily use.

## Update Strategy

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Incremental update | Only download new pages instead of re-downloading everything | true / false | false |
| Show folder time | Display folder creation time on the download card | true / false | false |
| Show folder size | Display folder size on the download card | true / false | false |

## Download Threads

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Multi-thread download | Number of concurrent image download threads | 1 / 3 / 5 / 7 | 3 |
| Preload image count | Number of images to preload | 3 / 5 / 7 / 11 / 13 / 17 | 5 |

> **Recommended**: Threads 3–5, preload 5–7 (balances speed and stability).

## Download List

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Download list pagination | Paginate the download list | true / false | true |

## Image Quality

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Image resolution | Resolution of downloaded images | `a` = Auto, `780`, `1280`, `1600`, `2400` | `a` (Auto) |
| Download original image | Download images at original resolution (requires EX privilege) | true / false | false |

> **Recommended**: Auto (default) works well. Original image download requires an EX account.

## Timeout

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Enable download timeout | Enable download timeout detection | true / false | false |
| Download timeout | Download timeout duration in seconds | 60–300 | 60 |

## Sorting & Network

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Download order ascending | Sort the download list in ascending order | true / false | true |
| Sync download while reading | Download galleries while reading them | true / false | false |
| Metered network policy | Behavior on metered (cellular) networks | `0` = Pause, `1` = Continue | `0` (Pause) |

## Maintenance Operations

| Name | Description |
|------|-------------|
| Restore download items | Restore interrupted download tasks |
| Clean redundancy | Remove redundant download files |
| Merge on download | Auto-merge same gallery during download (default off) |
| Merge duplicate gallery | Merge already-downloaded duplicate galleries |
| Scan download files | Scan the download directory and update records |
| View download logs | View download log records |
| Clean download logs | Clear all download logs |
| Clean invalid download | Remove invalid download records |
| Repair downloaded gallery | Repair downloaded gallery data |
| Repair unknown category | Fix galleries with unknown categories |
| Rebuild download records | Rebuild the download database records |
