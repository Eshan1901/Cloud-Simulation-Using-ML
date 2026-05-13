package org.cloudsim_ml;

public final class MLDecision {
    public enum Mode {
        CONSERVATIVE,
        NORMAL
    }

    private final double failureProbability;
    private final double threshold;
    private final Mode mode;

    public MLDecision(final double failureProbability, final double threshold, final Mode mode) {
        this.failureProbability = failureProbability;
        this.threshold = threshold;
        this.mode = mode;
    }

    public static MLDecision fallback() {
        return new MLDecision(0.5, 5.0, Mode.NORMAL);
    }

    public double getFailureProbability() {
        return failureProbability;
    }

    public double getThreshold() {
        return threshold;
    }

    public Mode getMode() {
        return mode;
    }

    public boolean isConservative() {
        return mode == Mode.CONSERVATIVE;
    }
}
