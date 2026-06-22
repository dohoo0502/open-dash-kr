# Changelog

## 1.3 Beta 2

- Moved ride totals and recent ride history onto the Home screen.
- Improved ride distance accuracy by rejecting poor GPS fixes and stationary drift.
- Added GPS weak/lost indicators and heading-aware off-route detection.
- Added the upstream MapLibre page-switch lifecycle crash fix.
- Added now-playing and caller cards for the Tripper Dash.
- Added joystick controls for media tracks and incoming/active calls.
- Added notification-access and call-control settings.
- Reduced the arm64 release download from about 44.5 MB to about 14.4 MB with ABI-specific APKs.
- Kept dash handshake, authentication, ACK, route-card, socket, and RTP behavior unchanged.
- Documented the reviewed additive `05 0D` media and `05 22` call packet extension.

## 1.3

- Added app-wide OpenDash themes with Hanle Black as the default.
- Hardened release signing, location URL handling, encrypted dash configuration, and pairing confirmation.
- Added custom dash wallpaper galleries with image, GIF, and video support.
