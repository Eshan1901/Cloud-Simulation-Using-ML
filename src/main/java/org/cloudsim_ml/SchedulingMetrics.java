package org.cloudsim_ml;

/**
 * Immutable DTO carrying the feature vector for the ML prediction endpoint.
 * Field names align with the required JSON payload specification.
 * All 13 trace-derived features are included (excluding "failed"
 * which is a label, not a feature).
 */
public final class SchedulingMetrics {
    private final double schedulingClass;
    private final double collectionType;
    private final double priority;
    private final double constraint;
    private final double verticalScaling;
    private final double scheduler;
    private final double sampleRate;
    private final double reqCpu;
    private final double reqMemory;
    private final double cpuMemoryRatio;
    private final double requestIntensity;
    private final int highPriorityFlag;
    private final int scalingFlag;

    public SchedulingMetrics(
            final double schedulingClass,
            final double collectionType,
            final double priority,
            final double constraint,
            final double verticalScaling,
            final double scheduler,
            final double sampleRate,
            final double reqCpu,
            final double reqMemory,
            final double cpuMemoryRatio,
            final double requestIntensity,
            final int highPriorityFlag,
            final int scalingFlag) {
        this.schedulingClass = schedulingClass;
        this.collectionType = collectionType;
        this.priority = priority;
        this.constraint = constraint;
        this.verticalScaling = verticalScaling;
        this.scheduler = scheduler;
        this.sampleRate = sampleRate;
        this.reqCpu = reqCpu;
        this.reqMemory = reqMemory;
        this.cpuMemoryRatio = cpuMemoryRatio;
        this.requestIntensity = requestIntensity;
        this.highPriorityFlag = highPriorityFlag;
        this.scalingFlag = scalingFlag;
    }

    public double getSchedulingClass() {
        return schedulingClass;
    }

    public double getCollectionType() {
        return collectionType;
    }

    public double getPriority() {
        return priority;
    }

    public double getConstraint() {
        return constraint;
    }

    public double getVerticalScaling() {
        return verticalScaling;
    }

    public double getScheduler() {
        return scheduler;
    }

    public double getSampleRate() {
        return sampleRate;
    }

    public double getReqCpu() {
        return reqCpu;
    }

    public double getReqMemory() {
        return reqMemory;
    }

    public double getCpuMemoryRatio() {
        return cpuMemoryRatio;
    }

    public double getRequestIntensity() {
        return requestIntensity;
    }

    public int getHighPriorityFlag() {
        return highPriorityFlag;
    }

    public int getScalingFlag() {
        return scalingFlag;
    }
}
