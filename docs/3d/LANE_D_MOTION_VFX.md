# Lane D — premium motion + VFX contract

This module defines visual-only motion. It never changes authoritative Ludo state.

Use it for:
- pawn hop/landing/bounce
- subtle squash/stretch
- die spin and impact compression
- camera micro impulse
- capture flash/ring/particle burst

The existing LudoMotionPhysics remains valid and should not be replaced blindly. Lane A should compose this profile with the current renderer timing and state callbacks.

Mobile target:
- deterministic math
- no allocations in per-frame hot loops where possible
- particle count default: 18
- short-lived capture VFX only
