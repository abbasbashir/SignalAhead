# Signal Ahead 0.3.1

Restores the optional "Start journey on app open" setting, off by default.
Enabling it is consent to start a visible journey on a later app launch, once
foreground location permission has been granted. Manual Start remains available.
Pause is respected. Screen rotation, returning from a file picker, or returning
from Android permission screens does not trigger another automatic start.
There is no boot startup or always-on passive learning. Other settings are retained.

This supersedes 0.3's requirement to tap Start for every new journey.
Uses the same pinned testing certificate as the delivered 0.3 APK.
