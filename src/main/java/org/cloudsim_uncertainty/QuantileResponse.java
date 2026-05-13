package org.cloudsim_uncertainty;

/**
 * Immutable DTO representing the full prediction response from the FastAPI ML
 * endpoint.
 * Includes nested quantile metrics for cpu, memory, and duration usage.
 */
public final class QuantileResponse {
    private final QuantilePrediction avgCpuUsage;
    private final QuantilePrediction avgMemoryUsage;
    private final QuantilePrediction maxCpuUsage;
    private final QuantilePrediction durationSeconds;
    private final double processingTimeMs;

    public QuantileResponse(
            QuantilePrediction avgCpuUsage,
            QuantilePrediction avgMemoryUsage,
            QuantilePrediction maxCpuUsage,
            QuantilePrediction durationSeconds,
            double processingTimeMs) {
        this.avgCpuUsage = avgCpuUsage;
        this.avgMemoryUsage = avgMemoryUsage;
        this.maxCpuUsage = maxCpuUsage;
        this.durationSeconds = durationSeconds;
        this.processingTimeMs = processingTimeMs;
    }

    public static QuantileResponse fallback() {
        return new QuantileResponse(
                QuantilePrediction.fallback(),
                QuantilePrediction.fallback(),
                QuantilePrediction.fallback(),
                QuantilePrediction.fallback(),
                0.0);
    }

    public QuantilePrediction getAvgCpuUsage() {
        return avgCpuUsage;
    }

    public QuantilePrediction getAvgMemoryUsage() {
        return avgMemoryUsage;
    }

    public QuantilePrediction getMaxCpuUsage() {
        return maxCpuUsage;
    }

    public QuantilePrediction getDurationSeconds() {
        return durationSeconds;
    }

    public double getProcessingTimeMs() {
        return processingTimeMs;
    }
}
