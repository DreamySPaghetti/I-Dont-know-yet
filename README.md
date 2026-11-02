# SmartCam

Android camera (Kotlin, Camera2) that picks settings for you:

- **ISO + shutter**: uses the camera's light meter, then re-splits exposure into the lowest ISO whose shutter is still fast enough for your hand shake (gyroscope), subject motion and zoom.
- **Highlight protection**: darkens up to -1.5 EV when >2% of the frame is blown out.
- **Auto zoom**: frames detected faces at ~45% of the frame; pinch to take over, tap "Auto zoom" to hand back.
- **Focus + sharpest of 3**: locks focus, shoots a 3-frame burst, keeps the sharpest.
- High-quality noise reduction / sharpening and OIS when the phone supports them.

**Screen**
- Bottom-left thumbnail shows your last photo; tap it to open it in your gallery app.
- Tap the preview to focus/meter there, long-press to return to auto focus, pinch to zoom.
- ⚙ Options: smart exposure, highlight protection, auto zoom, grid, volume-key shutter, burst (off/3/5), max ISO, self-timer (3s/10s), exposure compensation (±2 EV). Settings are remembered.

Every push to `main` builds `SmartCam.apk` and publishes it under Releases.
