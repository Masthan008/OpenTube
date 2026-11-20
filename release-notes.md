# OpenTube v3.1.0

**Release date:** 2026-11-21
**Status:** Stable
**Developer:** Masthan Valli ([@Masthan008](https://github.com/Masthan008))

Welcome to **OpenTube v3.1.0**!
A major feature release introducing real-time multi-device co-watching and listening ("Share Together"), the Origin Island dynamic capsule player, the OpenAnimation fluid launch reveal, and enhanced playback responsiveness.

## Highlights

- **Share Together (Room-Based Co-Watching & Listening)**:
  - Create and join rooms with simple 6-character room codes (`OT-XXXX`) or direct LAN/Wi-Fi connection.
  - Zero-config automatic local network discovery via UDP multicast/broadcast and embedded WebSocket peer-to-peer transport.
  - Synchronized YouTube video playback and music jam sessions across multiple devices with micro-speed drift correction.
  - Real-time in-room text chat with custom user profile names and animated avatar badges.
  - Live animated floating emoji reactions (❤️, 🔥, 👏, 🚀, 😂) with rising particle effects.
  - Low-latency voice calling with microphone mute/unmute, deafen controls, speaking halo animations, and dynamic audio ducking (reduces media volume to 70% while members are speaking).
  - Host DJ mode (host only controls playback) vs. Party mode (any member can queue and control playback).

- **Origin Island Capsule Player**:
  - Interactive top-screen dynamic capsule inspired by OriginOS and Dynamic Island.
  - Features spinning album vinyl discs, scrolling track titles, live animated 3-bar audio visualizer waves, and quick play/pause controls.
  - Automatically activates when media is playing and smoothly dismisses with pause grace period.
  - Configurable in Settings -> Appearance -> Origin Island.

- **OpenAnimation Launch Dive**:
  - Liquid geometric launch dive and reveal transition on cold starts.
  - Hardware-accelerated graphics layer with customizable animation speed.

- **Electric Sapphire & Obsidian Themes**:
  - New high-contrast sapphire blue and pitch black AMOLED color palettes for enhanced battery life on OLED displays.

- **Bug Fixes & Refinements**:
  - Fixed multi-device room synchronization where participants were joining isolated sessions.
  - Fixed Origin Island overlapping with active room indicators or staying on screen when paused.
  - Streamlined release packaging and signing pipeline.
