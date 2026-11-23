# PROTON AI

**PROTON AI** is a zero-dependency, single-file Java desktop AI assistant (`main.java`) built for hands-free voice control, live coding, and autonomous PC automation. Powered by Groq and NVIDIA NIM (Nemotron), it pairs a custom 60 FPS audio-reactive 3D wireframe orb UI with OS-level automation, real-time noise cancellation, and self-extending skills—requiring no external libraries or backend server.

## Key Features

- **Hybrid Multi-Model Engine:** Combines **Groq** (`gpt-oss-120b`, Whisper Large v3 Turbo, Orpheus TTS, Qwen Vision) for fast conversation and voice I/O with **NVIDIA NIM** (`Nemotron`) for deep reasoning, coding, and a live translucent thought-stream overlay.
- **Voice-First & Wake Activation:** Includes continuous hands-free Voice Mode, always-on `"Hey Proton"` wake-word detection (plus a global `Hold W` shortcut) with a floating Siri-style activation overlay, and a pure-Java real-time STFT noise suppressor.
- **Desktop Automation & Spatial Memory:** Controls Windows applications via UI Automation and vision fallback, remembers UI element coordinates across sessions (`~/.proton`), and smoothly morphs into a 150×150px corner mini-orb HUD during multi-step tasks.
- **Built-in PROTON IDE:** Writes complete code files in a read-only viewer with red/green line-by-line diffs on follow-up edits, then saves to `~/ProtonProjects` and syncs directly into active editors like VS Code, PyCharm, or IntelliJ.
- **Self-Extending Skills & Auto-Updater:** Generates and runs custom PowerShell/Python/Bash scripts behind a red/black permission gate, and checks GitHub for updates with isolated pre-flight compilation and hot-restart.

## Quick Start

```bash
javac main.java && java main

```

On first launch, choose your provider setup and paste your **Groq** (and optional **NVIDIA NIM**) API keys—stored locally in `~/.proton-ai.properties`.


---

### What Stands Out in the Codebase

- **Zero-Dependency Single-File Architecture:** Everything—custom `Graphics2D` shaders/gradients, 3D perspective sphere projection, audio capture, HTTP/SSE streaming, JSON parsing, and UI automation—is self-contained in `main.java` using only the standard JDK (`javax.swing`, `javax.sound.sampled`, `javax.tools.JavaCompiler`)[cite: 1].
- **Pure-Java DSP Audio Pipeline:** `NoiseSuppressor` implements a 90 Hz biquad high-pass filter and an STFT Wiener filter with minimum-statistics noise tracking in-place on raw PCM frames before voice activity detection and Whisper transcription[cite: 1].
- **Kinetic UI & Focus Management:** During desktop automation, `ProtonFrame` eases into a circular, non-focusable corner companion (`150×150px`) so `Robot` keystrokes and clicks never collide with PROTON's own window[cite: 1].
- **Safe Self-Modification:** `UpdateKit` pulls the latest commit from `Hellototech/Proton`, compiles `main.java` in an isolated temporary build directory first, and only swaps class files and restarts once compilation succeeds[cite: 1].

```
