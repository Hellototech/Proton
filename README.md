<div align="center">

# ⚡ P R O T O N &nbsp; A I

**A 60 FPS 3D-orb desktop AI assistant packed into a single, zero-dependency Java file.**  
*Voice control · Live coding · Autonomous Windows automation · 100% Free BYOK*

<p>
  <a href="https://tryprotonai.pages.dev">
    <img src="https://img.shields.io/badge/Website-tryprotonai.pages.dev-A855F7?style=for-the-badge&logo=cloudflarepages&logoColor=white" alt="Website" />
  </a>
  <a href="https://github.com/Hellototech/Proton-AI/releases/latest">
    <img src="https://img.shields.io/badge/Download-Windows%20Installer-3D7BFF?style=for-the-badge&logo=windows11&logoColor=white" alt="Download Windows Installer" />
  </a>
  <img src="https://img.shields.io/badge/Java-17%2B%20|%200%20Deps-FF4DD0?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 17+ Zero Dependencies" />
  <img src="https://img.shields.io/badge/1%20File-main.java-04030A?style=for-the-badge" alt="Single File" />
</p>

<!-- Drop your 30–45s demo GIF or GitHub video link right below -->
<!-- ![PROTON AI Demo](demo.gif) -->

</div>

---

## Why PROTON?

Most desktop AI assistants ship bloated Electron runtimes, subscriptions, and heavy dependencies. **PROTON AI** fits an entire voice-first OS copilot into **one standard-JDK `main.java` file**—zero Maven, zero Gradle, zero external JARs.

- **🌌 60 FPS 3D Wireframe UI** — Pure Java2D orbital HUD with dedicated Assistant & hands-free Voice modes, `"Hey Proton"` wake word, and global `Hold W` floating overlay.
- **🎙️ Real-Time Voice & Pure-Java DSP** — Built-in 90 Hz high-pass + STFT Wiener noise suppressor, streamed PCM audio playback, instant barge-in, 429 circuit breaker, and a warm local Windows TTS fallback.
- **🤖 Autonomous Windows Control** — Operates desktop apps via Windows UI Automation + Vision fallback, caches learned layouts locally, and morphs into a `150×150px` corner orb during tasks.
- **💻 PROTON IDE & Self-Extending Skills** — Generates complete code with live red/green diffs synced to VS Code, PyCharm, or IntelliJ, and runs custom PowerShell/Python/Bash skills behind an explicit approval gate.
- **🔄 Install Once, Self-Updates Forever** — Pulls new commits from `main`, compiles in an isolated folder, and hot-restarts cleanly on its own.

---

## ⚡ Quick Start

### Option 1: Windows Installer (Recommended — No Java Required)
1. Download **`ProtonSetup.exe`** from **[tryprotonai.pages.dev](https://tryprotonai.pages.dev)** or **[GitHub Releases](https://github.com/Hellototech/Proton-AI/releases/latest)**.
2. Run the setup wizard *(bundles a trimmed `jlink` runtime into `%LOCALAPPDATA%\PROTON AI`, no admin rights needed)*.
3. Launch **PROTON AI** and connect **either** free API key (or both) on first start.

### Option 2: Run From Source (JDK 17+)
```bash
git clone https://github.com/Hellototech/Proton-AI.git
cd Proton-AI
javac main.java && java main
```

---

## 🔑 100% Free BYOK Architecture

Bring **one or both** free-tier keys—PROTON automatically routes every capability and falls back seamlessly on rate limits. Keys stay strictly local in `~/.proton-ai.properties`.

| Provider | Free Models Used | Capabilities | Get Free Key |
| :--- | :--- | :--- | :--- |
| **Google AI Studio** | `gemini-3.8-flash`, `gemini-3.5-flash-lite`, `gemini-3.5-transcribe`, `gemini-3.8-flash-lite-tts` | Chat, Reasoning, Vision, Voice STT, TTS | [aistudio.google.com](https://aistudio.google.com) |
| **OpenRouter** | `nvidia/nemotron-3.5-super-vl:free`, `fish-audio/s2.1-pro-free:free` | Deep Reasoning, Coding, Vision, Primary Neural TTS | [openrouter.ai/keys](https://openrouter.ai/keys) |
| **Windows Local** | Built-in `System.Speech` (Warm Worker) | Zero-latency offline TTS fallback (unlimited) | *Built-in* |

---

<div align="center">

**Built for speed. Zero bloat. 100% open source.**  

**Built by Dev365**

If PROTON AI blew your mind, drop a ⭐ on the repo!

</div>
