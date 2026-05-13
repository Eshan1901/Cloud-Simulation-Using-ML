package org.cloudsim_ml;

import org.cloudsimplus.brokers.DatacenterBroker;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.vms.Vm;

import java.util.*;

/**
 * ML-driven broker that overrides only {@link #defaultVmMapper(Cloudlet)} to
 * select VMs based on predictions from an external ML endpoint.
 *
 * <p>
 * <b>Critical lifecycle rules obeyed:</b>
 * </p>
 * <ul>
 * <li>Uses {@link #getVmExecList()} — only running VMs that pass the superclass
 * {@code isCreated()} check.</li>
 * <li>Never returns {@link Vm#NULL} — always falls back to lowest-load VM.</li>
 * <li>Does NOT override any submission or event-processing methods — the
 * superclass handles the entire CloudSim event chain.</li>
 * </ul>
 */
public class MLSchedulerBroker extends DatacenterBrokerSimple {
    private final MLClient mlClient;
    private final HashMap<Long, TaskTrace> traceByCloudletId;
    private final HashMap<Long, MLDecision> decisionByCloudletId;
    /**
     * Tasks that completed but violated CPU/RAM/wait-time SLA thresholds.
     */
    private final Set<Long> slaViolationCloudletIds;
    // Note: true failures (tasks that never ran) are NOT tracked here.
    // Compute them in MainSimulation as: totalTasks - completedTasks.

    // Use a list to preserve trace metadata by insertion order.
    // Cloudlet IDs are -1 at registration time; actual mapping happens in
    // defaultVmMapper.
    private final ArrayList<TaskTrace> traceList;
    private final ArrayList<Cloudlet> cloudletList;

    // Base wait threshold increased to accommodate SpaceShared multi-thousand
    // second task scaling
    public static final double WAIT_SLA_THRESHOLD_BASE = 25.0;

    private double firstSubmissionTime = Double.MAX_VALUE;
    private double lastFinishTime = 0.0;
    private double totalWaitingTime = 0.0;
    private long finishedCount = 0L;
    private long roundRobinCounter = 0L;

    public MLSchedulerBroker(final CloudSimPlus simulation, final MLClient mlClient) {
        super(simulation);
        this.mlClient = mlClient;
        this.traceByCloudletId = new HashMap<>(131_072);
        this.decisionByCloudletId = new HashMap<>(131_072);
        this.slaViolationCloudletIds = new HashSet<>(32_768);
        // Note: true failures (tasks that never ran) are computed in MainSimulation
        // as: totalTasks - completedTasks, since they never trigger onCloudletFinish.
        this.traceList = new ArrayList<>(131_072);
        this.cloudletList = new ArrayList<>(131_072);
    }

    /**
     * Registers trace metadata for a cloudlet.
     * Called BEFORE submission, so cloudlet.getId() is still -1.
     * We store by insertion order and populate traceByCloudletId later.
     */
    public void registerCloudletMetadata(final Cloudlet cloudlet, final TaskTrace trace) {
        traceList.add(trace);
        cloudletList.add(cloudlet);
        firstSubmissionTime = Math.min(firstSubmissionTime, cloudlet.getSubmissionDelay());
    }

    /**
     * Called to map cloudlet IDs to their trace data.
     */
    public void resolveCloudletIds() {
        for (int i = 0; i < cloudletList.size(); i++) {
            final Cloudlet c = cloudletList.get(i);
            final TaskTrace t = traceList.get(i);
            traceByCloudletId.put(Long.valueOf(c.getId()), t);
        }
    }

    /**
     * Called when a cloudlet finishes execution.
     * Logs the cloudlet result and checks SLA violations.
     *
     * SLA violation defined as:
     * - VM CPU utilization > 90% OR
     * - VM RAM utilization > 95% OR
     * - Cloudlet waiting time exceeds ML-predicted threshold
     */
    public void onCloudletFinish(final Cloudlet cloudlet) {
        lastFinishTime = Math.max(lastFinishTime, cloudlet.getFinishTime());
        finishedCount++;

        final double waitingTime = Math.max(0.0, cloudlet.getStartWaitTime());
        final double executionTime = Math.max(0.0, cloudlet.getTotalExecutionTime());
        totalWaitingTime += waitingTime;

        // Constants for SLA evaluation (independent of ML prediction)
        final double CPU_SLA_THRESHOLD = 0.90;
        final double RAM_SLA_THRESHOLD = 0.95;

        // Scale wait threshold by aggregation factor
        final double waitThreshold = WAIT_SLA_THRESHOLD_BASE * TraceLoader.getAggregationFactor();

        // SLA checks against VM-level utilization (per spec)
        final Vm vm = cloudlet.getVm();
        final double vmCpuUtil = (vm == null || vm == Vm.NULL) ? 0.0 : clamp01(vm.getCpuPercentUtilization());
        final double vmRamUtil = (vm == null || vm == Vm.NULL) ? 0.0 : clamp01(vm.getRam().getPercentUtilization());

        final boolean cpuViolation = vmCpuUtil > CPU_SLA_THRESHOLD;
        final boolean ramViolation = vmRamUtil > RAM_SLA_THRESHOLD;
        final boolean waitViolation = waitingTime > waitThreshold;

        final boolean slaViolated = cpuViolation || ramViolation || waitViolation;

        if (slaViolated) {
            // SLA violation: task ran but exceeded CPU/RAM/wait threshold.
            // True failures (never ran) are tracked separately in MainSimulation.
            slaViolationCloudletIds.add(Long.valueOf(cloudlet.getId()));
        }

        // Log finished cloudlet
        final long vmId = (vm == null || vm == Vm.NULL) ? -1 : vm.getId();
        System.out.printf(Locale.US,
                "[CloudletFinish] ID=%d execTime=%.4f waitTime=%.4f vmId=%d slaViolated=%s%n",
                Long.valueOf(cloudlet.getId()), Double.valueOf(executionTime), Double.valueOf(waitingTime), Long.valueOf(vmId),
                slaViolated ? "YES" : "NO");
    }

    @Override
    public DatacenterBroker submitCloudletList(List<? extends Cloudlet> list) {
        // 1. Submit to superclass FIRST so CloudSim assigns valid runtime IDs
        DatacenterBroker result = super.submitCloudletList(list);

        // 2. Safely resolve the IDs now that they are guaranteed to exist
        resolveCloudletIds();

        // 3. Process ML Batch Predictions using the real IDs
        if (MLClient.ML_BATCH_MODE && !list.isEmpty()) {
            System.out.println(
                    "[MLSchedulerBroker] Processing ML batch predictions for " + list.size() + " cloudlets...");
            List<Cloudlet> currentBatch = new ArrayList<>();
            List<SchedulingMetrics> currentMetrics = new ArrayList<>();

            for (Cloudlet cloudlet : list) {
                currentBatch.add(cloudlet);
                // Safe to use getId() now because resolveCloudletIds() mapped them correctly
                currentMetrics
                        .add(buildMetrics(traceByCloudletId.getOrDefault(Long.valueOf(cloudlet.getId()), TaskTrace.defaultTrace())));

                if (currentBatch.size() >= MLClient.ML_BATCH_SIZE) {
                    processBatch(currentBatch, currentMetrics);
                    currentBatch.clear();
                    currentMetrics.clear();
                }
            }
            if (!currentBatch.isEmpty()) {
                processBatch(currentBatch, currentMetrics);
            }
            System.out.println("[MLSchedulerBroker] Finished ML batch predictions.");
        }
        return result;
    }

    private void processBatch(List<Cloudlet> cloudlets, List<SchedulingMetrics> metrics) {
        List<MLDecision> decisions = mlClient.predictBatch(metrics);
        for (int i = 0; i < cloudlets.size(); i++) {
            MLDecision decision = decisions.size() > i ? decisions.get(i) : MLDecision.fallback();
            decisionByCloudletId.put(Long.valueOf(cloudlets.get(i).getId()), decision);
        }
    }

    /**
     * ML-driven VM selection.
     *
     * <p>
     * For each cloudlet submission:
     * </p>
     * <ol>
     * <li>Collects VM CPU/RAM utilization and datacenter average load</li>
     * <li>Uses pre-computed ML decision from batching OR sends to ML endpoint if
     * missing</li>
     * <li>Based on decision: CONSERVATIVE → least loaded VM, NORMAL →
     * consolidation-friendly VM</li>
     * <li>Falls back to absolute lowest-load VM if no VM satisfies thresholds</li>
     * </ol>
     *
     * @return a valid VM — never {@link Vm#NULL}
     */
    @Override
    protected Vm defaultVmMapper(final Cloudlet cloudlet) {
        if (cloudlet.isBoundToVm()) {
            return cloudlet.getVm();
        }

        final List<Vm> vms = getVmExecList();
        if (vms.isEmpty()) {
            return Vm.NULL;
        }

        // Use cached decision from batch process, or fetch individually
        MLDecision decision = decisionByCloudletId.get(Long.valueOf(cloudlet.getId()));
        if (decision == null) {
            final TaskTrace trace = traceByCloudletId.getOrDefault(Long.valueOf(cloudlet.getId()), TaskTrace.defaultTrace());
            final SchedulingMetrics metrics = buildMetrics(trace);
            decision = mlClient.predict(metrics);
            decisionByCloudletId.put(Long.valueOf(cloudlet.getId()), decision);
        }

        // Check if all VMs have nearly-equal utilization (common at startup)
        // If so, use round-robin to distribute evenly
        double minCpu = Double.MAX_VALUE;
        double maxCpu = Double.MIN_VALUE;
        for (Vm vm : vms) {
            if (vm != Vm.NULL && vm.getHost() != Host.NULL) {
                final double cpu = clamp01(vm.getCpuPercentUtilization());
                minCpu = Math.min(minCpu, cpu);
                maxCpu = Math.max(maxCpu, cpu);
            }
        }

        // When utilization spread is tiny (< 1%), round-robin is better than
        // trying to pick the "least loaded" VM (which just picks VM 0)
        if (maxCpu - minCpu < 0.01) {
            final Vm rrVm = vms.get((int) (roundRobinCounter % vms.size()));
            roundRobinCounter++;
            return rrVm;
        }

        Vm selectedVm = decision.isConservative()
                ? selectLeastLoadedVm(vms, 0.65)
                : selectConsolidationVm(vms, 0.90);

        // Fallback chain: never return Vm.NULL
        if (selectedVm == Vm.NULL) {
            selectedVm = selectLeastLoadedVm(vms, 1.0);
        }
        if (selectedVm == Vm.NULL) {
            selectedVm = selectAbsoluteLowestVm(vms);
        }
        if (selectedVm == Vm.NULL) {
            selectedVm = vms.get((int) (roundRobinCounter % vms.size()));
        }
        roundRobinCounter++;
        return selectedVm;
    }

    // ---------- VM selection helpers ----------

    private Vm selectLeastLoadedVm(final List<Vm> vms, final double maxCpuUtil) {
        Vm best = Vm.NULL;
        double bestScore = Double.POSITIVE_INFINITY;

        for (Vm vm : vms) {
            if (vm == Vm.NULL || vm.getHost() == Host.NULL)
                continue;

            final double cpuUtil = clamp01(vm.getCpuPercentUtilization());
            final double ramUtil = clamp01(vm.getRam().getPercentUtilization());

            if (cpuUtil <= maxCpuUtil && ramUtil < 1.0) {
                final double score = 0.6 * cpuUtil + 0.4 * ramUtil;
                if (best == Vm.NULL || score < bestScore - 1e-9) {
                    best = vm;
                    bestScore = score;
                }
                // Tie-break: when scores are effectively equal, use round-robin
                // instead of always picking lowest ID (prevents VM 0 pile-up)
            }
        }

        // If all VMs tied at same score (e.g. 0% at startup), use round-robin
        if (best != Vm.NULL && bestScore < 1e-9) {
            // All VMs idle — distribute via round-robin
            return vms.get((int) (roundRobinCounter % vms.size()));
        }

        return best;
    }

    private Vm selectConsolidationVm(final List<Vm> vms, final double maxCpuUtil) {
        Vm best = Vm.NULL;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (Vm vm : vms) {
            if (vm == Vm.NULL || vm.getHost() == Host.NULL)
                continue;

            final double cpuUtil = clamp01(vm.getCpuPercentUtilization());
            final double ramUtil = clamp01(vm.getRam().getPercentUtilization());

            if (cpuUtil <= maxCpuUtil && ramUtil < 1.0) {
                final double score = 0.6 * cpuUtil + 0.4 * ramUtil;
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

            final double cpuUtil = clamp01(vm.getCpuPercentUtilization());
            final double ramUtil = clamp01(vm.getRam().getPercentUtilization());
            final double score = 0.6 * cpuUtil + 0.4 * ramUtil;

            if (best == Vm.NULL || score < bestScore - 1e-12
                    || (Math.abs(score - bestScore) <= 1e-12 && vm.getId() < best.getId())) {
                best = vm;
                bestScore = score;
            }
        }

        return best;
    }

    private Vm findLeastLoadedVm(final List<Vm> vms) {
        Vm best = Vm.NULL;
        double bestCpu = Double.POSITIVE_INFINITY;

        for (Vm vm : vms) {
            if (vm == Vm.NULL || vm.getHost() == Host.NULL)
                continue;
            final double cpu = clamp01(vm.getCpuPercentUtilization());
            if (cpu < bestCpu) {
                bestCpu = cpu;
                best = vm;
            }
        }

        return best;
    }

    private double averageDatacenterCpuLoad(final List<Vm> vms) {
        final Set<Host> hosts = new HashSet<>();
        for (Vm vm : vms) {
            if (vm.getHost() != Host.NULL) {
                hosts.add(vm.getHost());
            }
        }

        if (hosts.isEmpty())
            return 0.0;

        double sum = 0.0;
        for (Host host : hosts) {
            sum += clamp01(host.getCpuPercentUtilization());
        }
        return sum / hosts.size();
    }

    // ---------- Public metrics ----------

    public long getTotalTasks() {
        return traceList.size();
    }

    public long getSlaViolationCount() {
        return slaViolationCloudletIds.size();
    }

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

    // ---------- Utility ----------

    private SchedulingMetrics buildMetrics(final TaskTrace trace) {
        return new SchedulingMetrics(
                safe(trace.getSchedulingClass()),
                safe(trace.getCollectionType()),
                safe(trace.getPriority()),
                safe(trace.getConstraint()),
                safe(trace.getVerticalScaling()),
                safe(trace.getScheduler()),
                safe(trace.getSampleRate()),
                safe(trace.getReqCpu()),
                safe(trace.getReqMemory()),
                safe(trace.getCpuMemoryRatio()),
                safe(trace.getRequestIntensity()),
                trace.getHighPriorityFlag(),
                trace.getScalingFlag());
    }

    private static double safe(final double value) {
        if (Double.isNaN(value) || Double.isInfinite(value))
            return 0.0;
        return value;
    }

    private static double clamp01(final double value) {
        if (Double.isNaN(value) || Double.isInfinite(value))
            return 0.0;
        if (value < 0.0)
            return 0.0;
        if (value > 1.0)
            return 1.0;
        return value;
    }
}
