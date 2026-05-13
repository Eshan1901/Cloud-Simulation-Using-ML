package org.cloudsim_uncertainty;

import org.cloudsimplus.brokers.DatacenterBroker;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.vms.Vm;

import java.util.*;

public class UncertaintySchedulerBroker extends DatacenterBrokerSimple {
    private final UncertaintyClient uncertaintyClient;
    private final HashMap<Long, TaskTrace> traceByCloudletId;
    private final HashMap<Long, QuantileResponse> decisionByCloudletId;
    /**
     * Single unified set: a task is "failed" if and only if it violated the SLA.
     */
    private final Set<Long> slaViolationCloudletIds;
    // Tracks pending pre-flight allocations at t=0 when live queue sizes are all 0
    private final Map<Vm, Integer> allocatedTasks;

    private final ArrayList<TaskTrace> traceList;
    private final ArrayList<Cloudlet> cloudletList;

    // Data-driven adaptive thresholds computed from the actual batch P90
    // distribution
    private double p90HighThreshold = 0.70; // default fallback (overwritten after batch prefetch)
    private double p90LowThreshold = 0.35;

    public static final double WAIT_SLA_THRESHOLD_BASE = 25.0;

    private double firstSubmissionTime = Double.MAX_VALUE;
    private double lastFinishTime = 0.0;
    private double totalWaitingTime = 0.0;
    private long finishedCount = 0L;
    private long roundRobinCounter = 0L;

    public UncertaintySchedulerBroker(final CloudSimPlus simulation, final UncertaintyClient client) {
        super(simulation);
        this.uncertaintyClient = client;
        this.traceByCloudletId = new HashMap<>(131_072);
        this.decisionByCloudletId = new HashMap<>(131_072);
        this.slaViolationCloudletIds = new HashSet<>(32_768);
        this.allocatedTasks = new HashMap<>(128);
        this.traceList = new ArrayList<>(131_072);
        this.cloudletList = new ArrayList<>(131_072);
    }

    public void registerCloudletMetadata(final Cloudlet cloudlet, final TaskTrace trace) {
        traceList.add(trace);
        cloudletList.add(cloudlet);
        firstSubmissionTime = Math.min(firstSubmissionTime, cloudlet.getSubmissionDelay());
    }

    public void resolveCloudletIds() {
        for (int i = 0; i < cloudletList.size(); i++) {
            final Cloudlet c = cloudletList.get(i);
            final TaskTrace t = traceList.get(i);
            traceByCloudletId.put(Long.valueOf(c.getId()), t);
        }
    }

    public void onCloudletFinish(final Cloudlet cloudlet) {
        lastFinishTime = Math.max(lastFinishTime, cloudlet.getFinishTime());
        finishedCount++;

        final double waitingTime = Math.max(0.0, cloudlet.getStartWaitTime());
        final double executionTime = Math.max(0.0, cloudlet.getTotalExecutionTime());
        totalWaitingTime += waitingTime;

        final double CPU_SLA_THRESHOLD = 0.90;
        final double RAM_SLA_THRESHOLD = 0.95;

        final double waitThreshold = WAIT_SLA_THRESHOLD_BASE * TraceLoader.getAggregationFactor();

        final Vm vm = cloudlet.getVm();
        final double vmCpuUtil = (vm == null || vm == Vm.NULL) ? 0.0 : clamp01(vm.getCpuPercentUtilization());
        final double vmRamUtil = (vm == null || vm == Vm.NULL) ? 0.0 : clamp01(vm.getRam().getPercentUtilization());

        final boolean cpuViolation = vmCpuUtil > CPU_SLA_THRESHOLD;
        final boolean ramViolation = vmRamUtil > RAM_SLA_THRESHOLD;
        final boolean waitViolation = waitingTime > waitThreshold;

        final boolean slaViolated = cpuViolation || ramViolation || waitViolation;

        if (slaViolated) {
            // SLA violation == task failure: tracked in a single set.
            slaViolationCloudletIds.add(Long.valueOf(cloudlet.getId()));
        }

        final long vmId = (vm == null || vm == Vm.NULL) ? -1 : vm.getId();
        System.out.printf(Locale.US,
                "[CloudletFinish] ID=%d execTime=%.4f waitTime=%.4f vmId=%d slaViolated=%s%n",
                Long.valueOf(cloudlet.getId()), Double.valueOf(executionTime), Double.valueOf(waitingTime), Long.valueOf(vmId),
                slaViolated ? "YES" : "NO");

        if (vm != null && vm != Vm.NULL) {
            allocatedTasks.computeIfPresent(vm, (k, v) -> Integer.valueOf(v - 1));
        }
    }

    @Override
    public DatacenterBroker submitCloudletList(List<? extends Cloudlet> list) {
        DatacenterBroker result = super.submitCloudletList(list);
        resolveCloudletIds();

        final List<Cloudlet> pending = new ArrayList<>();
        final List<SchedulingMetrics> metricsBatch = new ArrayList<>();

        for (Cloudlet c : list) {
            if (!decisionByCloudletId.containsKey(Long.valueOf(c.getId()))) {
                pending.add(c);
                TaskTrace trace = traceByCloudletId.getOrDefault(Long.valueOf(c.getId()), TaskTrace.defaultTrace());
                metricsBatch.add(buildMetrics(trace));
            }
        }

        if (!metricsBatch.isEmpty()) {
            final int BATCH_SIZE = 1000;
            for (int i = 0; i < metricsBatch.size(); i += BATCH_SIZE) {
                final int endIndex = Math.min(i + BATCH_SIZE, metricsBatch.size());
                final List<SchedulingMetrics> subBatch = metricsBatch.subList(i, endIndex);
                System.out.println("[UncertaintySchedulerBroker] Pre-fetching ML predictions for " + subBatch.size()
                        + " incoming tasks via bulk API pass (batch " + (i / BATCH_SIZE + 1) + ")...");
                List<QuantileResponse> responses = uncertaintyClient.predictBatch(subBatch);
                for (int j = 0; j < responses.size(); j++) {
                    decisionByCloudletId.put(Long.valueOf(pending.get(i + j).getId()), responses.get(j));
                }
                if (i == 0 && !responses.isEmpty()) {
                    System.out.printf(
                            "[Diagnostic] Batch sample prediction #1 \u2192 P90 maxCpu=%.6f, P90 avgCpu=%.6f%n",
                            Double.valueOf(responses.get(0).getMaxCpuUsage().getP90()),
                            Double.valueOf(responses.get(0).getAvgCpuUsage().getP90()));
                }
            }
        }

        // Compute data-driven percentile thresholds from the actual batch predictions
        if (!decisionByCloudletId.isEmpty()) {
            final List<Double> p90Values = new ArrayList<>(decisionByCloudletId.size());
            for (QuantileResponse r : decisionByCloudletId.values())
                p90Values.add(Double.valueOf(r.getMaxCpuUsage().getP90()));
            Collections.sort(p90Values);
            final int n = p90Values.size();
            p90HighThreshold = p90Values.get((int) (n * 0.66));
            p90LowThreshold = p90Values.get((int) (n * 0.33));
            System.out.printf("[AdaptiveThreshold] P90 maxCpu distribution: P33=%.6f P66=%.6f (n=%d)%n",
                    Double.valueOf(p90LowThreshold), Double.valueOf(p90HighThreshold), Integer.valueOf(n));
        }

        return result;
    }

    /**
     * UNCERTAINTY-DRIVEN VM SELECTION
     * Evaluates the P90 tail risk of the task to perform risk-aware placement.
     */
    @Override
    protected Vm defaultVmMapper(final Cloudlet cloudlet) {
        if (cloudlet.isBoundToVm())
            return cloudlet.getVm();

        final List<Vm> vms = getVmExecList();
        if (vms.isEmpty())
            return Vm.NULL;

        QuantileResponse response = decisionByCloudletId.get(Long.valueOf(cloudlet.getId()));
        if (response == null) {
            final TaskTrace trace = traceByCloudletId.getOrDefault(Long.valueOf(cloudlet.getId()), TaskTrace.defaultTrace());
            final SchedulingMetrics metrics = buildMetrics(trace);
            response = uncertaintyClient.predict(metrics);
            decisionByCloudletId.put(Long.valueOf(cloudlet.getId()), response);
        }

        // ADAPTIVE P90 SCHEDULING: thresholds are data-driven from the actual batch
        // distribution
        // ensuring tasks are split into HIGH/MEDIUM/LOW regardless of absolute
        // prediction magnitudes.
        final double p90MaxCpu = response.getMaxCpuUsage().getP90();
        Vm selectedVm;

        if (p90MaxCpu >= p90HighThreshold) {
            // HIGH BURST RISK (top 34%): Isolate to least loaded VM
            selectedVm = selectLeastLoadedVm(vms, 0.80);
        } else if (p90MaxCpu >= p90LowThreshold) {
            // MEDIUM RISK (middle 33%): Fair load balancing
            selectedVm = selectLeastLoadedVm(vms, 1.0);
        } else {
            // LOW RISK (bottom 33%): Consolidate for energy efficiency
            selectedVm = selectConsolidationVm(vms, 0.95);
        }

        if (selectedVm == Vm.NULL)
            selectedVm = selectLeastLoadedVm(vms, 1.0);
        if (selectedVm == Vm.NULL)
            selectedVm = selectAbsoluteLowestVm(vms);
        if (selectedVm == Vm.NULL)
            selectedVm = vms.get((int) (roundRobinCounter % vms.size()));

        roundRobinCounter++;

        // Track pre-flight allocation so next mapper call sees this pending task
        if (selectedVm != Vm.NULL)
            allocatedTasks.merge(selectedVm, 1, Integer::sum);

        return selectedVm;
    }

    private Vm selectLeastLoadedVm(final List<Vm> vms, final double maxCpuUtil) {
        Vm best = Vm.NULL;
        double bestScore = Double.POSITIVE_INFINITY;
        for (Vm vm : vms) {
            if (vm == Vm.NULL || vm.getHost() == Host.NULL)
                continue;

            final int waitingTasks = allocatedTasks.getOrDefault(vm, Integer.valueOf(0))
                    + vm.getCloudletScheduler().getCloudletWaitingList().size();
            final double cpuUtil = clamp01(vm.getCpuPercentUtilization());
            final double ramUtil = clamp01(vm.getRam().getPercentUtilization());

            if (cpuUtil <= maxCpuUtil && ramUtil < 1.0) {
                // Heavily penalize VMs that already have a huge backlog in SpaceShared queuing
                final double score = 0.6 * cpuUtil + 0.4 * ramUtil + (waitingTasks * 2.0);
                if (best == Vm.NULL || score < bestScore - 1e-9) {
                    best = vm;
                    bestScore = score;
                }
            }
        }
        if (best != Vm.NULL && bestScore < 1e-9)
            return vms.get((int) (roundRobinCounter % vms.size()));
        return best;
    }

    private Vm selectConsolidationVm(final List<Vm> vms, final double maxCpuUtil) {
        Vm best = Vm.NULL;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Vm vm : vms) {
            if (vm == Vm.NULL || vm.getHost() == Host.NULL)
                continue;

            final int waitingTasks = allocatedTasks.getOrDefault(vm, Integer.valueOf(0))
                    + vm.getCloudletScheduler().getCloudletWaitingList().size();
            // Prohibit packing onto VMs that are already heavily backlogged
            if (waitingTasks >= 4) { // VM_PES is 4
                continue;
            }

            final double cpuUtil = clamp01(vm.getCpuPercentUtilization());
            final double ramUtil = clamp01(vm.getRam().getPercentUtilization());

            if (cpuUtil <= maxCpuUtil && ramUtil < 1.0) {
                // Higher score = better consolidation target. Subtract queue size to break
                // ties.
                final double score = 0.6 * cpuUtil + 0.4 * ramUtil - (waitingTasks * 0.1);
                if (best == Vm.NULL || score > bestScore + 1e-12
                        || (Math.abs(score - bestScore) <= 1e-12 && vm.getId() < best.getId())) {
                    best = vm;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    private Vm selectAbsoluteLowestVm(final List<Vm> vms) {
        Vm best = Vm.NULL;
        double bestScore = Double.POSITIVE_INFINITY;
        for (Vm vm : vms) {
            if (vm == Vm.NULL || vm.getHost() == Host.NULL)
                continue;

            final int waitingTasks = allocatedTasks.getOrDefault(vm, Integer.valueOf(0))
                    + vm.getCloudletScheduler().getCloudletWaitingList().size();
            final double cpuUtil = clamp01(vm.getCpuPercentUtilization());
            final double ramUtil = clamp01(vm.getRam().getPercentUtilization());

            final double score = 0.6 * cpuUtil + 0.4 * ramUtil + (waitingTasks * 2.0);

            if (best == Vm.NULL || score < bestScore - 1e-12
                    || (Math.abs(score - bestScore) <= 1e-12 && vm.getId() < best.getId())) {
                best = vm;
                bestScore = score;
            }
        }
        return best;
    }

    public long getTotalTasks() {
        return traceList.size();
    }

    public long getSlaViolationCount() {
        return slaViolationCloudletIds.size();
    }

    // Note: getFailedTasks() is intentionally NOT defined here.
    // True failures (tasks that never ran) = totalTasks - completedTasks,
    // computed in MainUncertaintySimulation after the simulation ends.

    public double getSlaViolationPercent() {
        final long total = getTotalTasks();
        if (total == 0)
            return 0.0;
        return 100.0 * getSlaViolationCount() / total;
    }

    public double getAverageWaitingTime() {
        if (finishedCount == 0)
            return 0.0;
        return totalWaitingTime / finishedCount;
    }

    public double getMakespan() {
        if (firstSubmissionTime == Double.MAX_VALUE)
            return 0.0;
        return Math.max(0.0, lastFinishTime - firstSubmissionTime);
    }

    private SchedulingMetrics buildMetrics(final TaskTrace trace) {
        return new SchedulingMetrics(
                safe(trace.getSchedulingClass()), safe(trace.getCollectionType()), safe(trace.getPriority()),
                safe(trace.getConstraint()), safe(trace.getVerticalScaling()), safe(trace.getScheduler()),
                safe(trace.getSampleRate()), safe(trace.getReqCpu()), safe(trace.getReqMemory()),
                safe(trace.getCpuMemoryRatio()), safe(trace.getRequestIntensity()), trace.getHighPriorityFlag(),
                trace.getScalingFlag());
    }

    private static double safe(final double value) {
        return (Double.isNaN(value) || Double.isInfinite(value)) ? 0.0 : value;
    }

    private static double clamp01(final double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0)
            return 0.0;
        if (value > 1.0)
            return 1.0;
        return value;
    }
}
