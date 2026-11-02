# Ant Trail

An ad-free colour-sorting puzzle for Android, written natively in Kotlin.

Tap a cube in the tray to move it into a waiting slot. Its ants march out of the nest along trails through open ground, carry tiles of that colour home, and count the cube down. Ants can only reach tiles touching open ground, and there are only 5 slots, so the order you send cubes in matters. Clear every tile to reveal the picture.

- `Logic.kt` is pure Kotlin: board, level generator, solver, difficulty measurement and the real-time engine.
- `GameView.kt` draws the board, ants, slots and tray on a native Canvas. `MainActivity.kt` is the Compose UI.
- `app/src/test/.../Harness.kt` is the self-test. CI runs it on every push (`gradle testDebugUnitTest`).
- Settings → Debug view shows reachable tiles and the solver's answer.
- My Pictures turns any photo into a pixel-map puzzle (k-means colour quantisation, then the same solver-checked deal search as the campaign).

## Install on your phone

1. On your Android phone, open https://github.com/DreamySPaghetti/I-Dont-know-yet/releases/latest
2. Tap **AntTrail.apk** to download it, then open it and tap **Install**.
