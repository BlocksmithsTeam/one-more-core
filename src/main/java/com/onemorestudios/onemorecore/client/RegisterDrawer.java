package com.onemorestudios.onemorecore.client;

/** Local drawer motion in seconds; reversing starts at the current pose. */
final class RegisterDrawer {
    static final float DURATION = 0.35F;
    private boolean opening;
    private float from;
    private float elapsed = DURATION;
    private float progress;

    void target(boolean open) {
        if (opening == open) return;
        opening = open;
        from = progress;
        elapsed = 0;
    }

    void advance(float seconds) {
        elapsed = Math.min(DURATION, elapsed + Math.max(0, seconds));
        float remaining = 1 - elapsed / DURATION;
        float eased = 1 - remaining * remaining * remaining;
        progress = from + ((opening ? 1 : 0) - from) * eased;
    }

    float progress() { return progress; }
}
