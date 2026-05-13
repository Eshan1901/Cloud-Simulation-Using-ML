package org.cloudsim_uncertainty;

/**
 * Immutable DTO representing a triplet of predicted quantiles for a specific
 * target.
 */
public final class QuantilePrediction {
    private final double p10;
    private final double p50;
    private final double p90;

    public QuantilePrediction(final double p10, final double p50, final double p90) {
        this.p10 = p10;
        this.p50 = p50;
        this.p90 = p90;
    }

    public static QuantilePrediction fallback() {
        return new QuantilePrediction(0.0, 0.0, 0.0);
    }

    public double getP10() {
        return p10;
    }

    public double getP50() {
        return p50;
    }

    public double getP90() {
        return p90;
    }

    @Override
    public String toString() {
        return String.format("QuantilePrediction{p10=%.4f, p50=%.4f, p90=%.4f}", Double.valueOf(p10), Double.valueOf(p50), Double.valueOf(p90));
    }
}
