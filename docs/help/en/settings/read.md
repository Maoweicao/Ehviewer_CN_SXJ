# Reading Settings

> Screen orientation, reading direction, page scaling, page turn timing, page turn behavior, screen display, and brightness.

## Screen Orientation

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Screen rotation | Screen rotation mode during reading | `0` = Default, `1` = Portrait, `2` = Landscape, `3` = Sensor | `0` (Default) |

> **Recommended**: Sensor (3) — automatically adapts to how you hold the device.

## Reading Direction

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Reading direction | Page flip direction | `0` = Left to right, `1` = Right to left, `2` = Top to bottom | `1` (Right to left) |

> **Recommended**: Right to left (standard manga reading direction).

## Page Scaling

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Page scaling | How images are scaled to fit the screen | `0` = Original size, `1` = Fit width, `2` = Fit height, `3` = Fit screen, `4` = Fixed ratio | `3` (Fit screen) |

> **Recommended**: Fit screen (3) — balances width and height.

## Start Position

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Start position | Initial scroll position when opening a page | `0` = Top-left, `1` = Top-right, `2` = Bottom-left, `3` = Bottom-right, `4` = Center | `1` (Top-right) |

> **Recommended**: Top-right (1) — suits right-to-left manga reading.

## Page Turn Timing

> Note: Values are stored in tenths of a second (e.g. 5 = 0.5s).

| Name | Description | Range | Default |
|------|-------------|-------|---------|
| Fast transfer time | Fast page-flip interval | 1–10 (0.1–1.0s) | 5 (0.5s) |
| Static transfer time | Auto page-turn interval for static images | 10–1200 (1.0–120s) | 40 (4.0s) |
| Animated transfer time | Auto page-turn interval for animated images | 10–1200 (1.0–120s) | 80 (8.0s) |

## Page Turn Behavior

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Wait for animation | Wait for GIF animation to finish before auto page turn | true / false | true |
| Show transfer countdown | Display countdown timer for auto page turn | true / false | false |
| Disable click to flip page | Disable tapping to turn pages | true / false | false |
| Disable gesture to flip page | Disable swipe gesture to turn pages | true / false | false |

## Screen & Display

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Keep screen on | Prevent screen from turning off during reading | true / false | false |
| Show clock | Display clock in the reading view | true / false | true |
| Show progress | Display page progress in the reading view | true / false | true |
| Show battery | Display battery info in the reading view | true / false | true |
| Show page interval | Display page turn interval | true / false | true |

> **Recommended**: Enable Keep screen on for long reading sessions.

## Page Flip & Brightness

| Name | Description | Possible Values | Default |
|------|-------------|-----------------|---------|
| Volume key page flip | Use volume keys to turn pages | true / false | false |
| Reverse volume key | Reverse volume key page-flip direction (requires volume key page flip) | true / false | false |
| Reading fullscreen | Hide system navigation bar during reading | true / false | true |
| Custom screen brightness | Use in-app brightness control | true / false | false |
| Screen brightness | Custom screen brightness (requires custom brightness) | 0–200% | 50% |

> **Recommended**: Enable Reading fullscreen. Use system brightness control by default.
