# Lab

> Experimental features: auto IP switching, AI overlay translation, progressive manager, and more.

## Lab Home

| Name | Description |
|------|-------------|
| Auto IP Switch | Open IP switching settings |
| AI Overlay Translate | Open AI translation settings |
| Progressive Manager | Manage progressive downloads |

## Auto IP Switch

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Enable Auto IP Switch | Enable automatic IP switching | true / false | false |
| Manage Subscriptions | Manage proxy subscription sources | — | — |
| Per-IP Download Threshold | Maximum downloads per IP | 100 / 500 / 1000 / Unlimited (0) | 50 |
| Block Duration | Wait time after an IP is blocked | 240 / 360 / 720 / 1440 minutes | 30 minutes |
| Switch Strategy | IP switching strategy | `0` = Round-robin, `1` = Random, `2` = Priority, `3` = Lowest latency | `0` (Round-robin) |
| Subscription Refresh Interval | Auto-refresh interval for subscriptions | 30 / 60 / 120 / 360 / 720 / 1440 minutes | 60 minutes |
| Node List | View available IP nodes | — | — |
| IP Pool Status | View the current IP pool status | — | — |
| Clear IP Pool | Clear all cached IPs | — | — |

## AI Overlay Translation

### Basic Settings

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Enable AI Translate | Enable AI overlay translation | true / false | false |

### OCR Text Recognition

| Name | Description |
|------|-------------|
| LAN OCR Server | Configure the PaddleOCR LAN server URL |
| Test OCR Connection | Test the OCR server connection |

### Translation Service

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Translation Provider | Select the translation service provider | ollama / llamacpp / unsloth / deepseek / openai / custom | custom |
| API Key | Configure the translation service API key | — | (empty) |
| API URL | Configure the translation service API URL | — | (empty) |
| Model | Select the translation model | — | gpt-4o-mini |
| Test Translation | Test if the translation service works | — | — |

### Advanced Settings

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Auto Image Detection | Automatically detect text regions in images | true / false | true |
| Skip Animated Pages | Skip translation of GIF and other animated images | true / false | true |

### Display Settings

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Overlay Alpha | Transparency of the translation overlay | 100 / 150 / 180 / 200 / 220 / 240 | 180 |
| Font Size | Font size of translated text (sp) | 10 / 12 / 14 / 16 / 18 / 20 / 24 | 14 |
| Position | Where the translation overlay appears | `0` = Above, `1` = Below, `2` = Overlay | `0` (Above) |

### Cache Settings

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Enable Translation Cache | Cache translations to avoid repeated requests | true / false | true |
| Cache Expiry | How long cached translations are kept | 1 / 3 / 7 / 14 / 30 / 0 (Never) days | 7 days |
| Clear Translation Cache | Clear all cached translation results | — | — |
