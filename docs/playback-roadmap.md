# Playback Roadmap

Tracking notes for playback investigation. These items are not implemented yet.

## Diagnostics

- Add a playback diagnostics overlay for support and testing.
- Show whether playback is Direct Play, Direct Stream, or Transcode.
- Surface transcode reasons when the server provides them.

## Compatibility

- Investigate PGS subtitle behavior and Blu-ray rip compatibility.
- Keep Media3/ExoPlayer as the default playback backend.
- Research a possible future mpv/libmpv compatibility backend only if ExoPlayer limitations block important Pakflix media.

## Guardrails

- Keep playback changes small and testable.
- Avoid replacing the default backend without measured compatibility evidence.
- Document device-specific failures with sample media details, server transcode decisions, and Android TV model/version data.
