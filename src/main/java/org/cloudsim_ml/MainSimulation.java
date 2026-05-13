package org.cloudsim_ml;

import org.cloudsimplus.allocationpolicies.VmAllocationPolicySimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.power.models.PowerModelHostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudsimplus.schedulers.vm.VmSchedulerTimeShared;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MainSimulation — orchestrator for  ML-driven CloudSim Plus
 * simulation.
 *
 * <p>
 * Flow:
 * </p>
 * <ol>
 * <li>Load Google trace CSV via {@link TraceLoader}</li>
 * <li>Create Datacenter with Hosts</li>
 * <li>Create VMs with {@code CloudletSchedulerTimeShared}</li>
 * <li>Create Cloudlets from trace rows (chronological order, with
 * submissionDelay)</li>
 * <li>Register clock tick listener for periodic logging</li>
 * <li>Start simulation</li>
 * <li>Print summary metrics</li>
 * </ol>
 */
public final class MainSimulation {
    private static final String CLEANED_TRACE_PATH = "src/main/java/org/cloudsim_ml/cleaned_google_trace.csv";

    // --- Infrastructure sizing ---
    private static final int HOSTS = 12;
    private static final int HOST_PES = 32;
    private static final double HOST_PE_MIPS = 10_000;
    private static final long HOST_RAM_MB = 262_144;
    private static final long HOST_BW = 10_000_000;
    private static final long HOST_STORAGE = 10_000_000;

    private static final int VMS = 36;
    private static final int VM_PES = 4;
    private static final double VM_MIPS = 8_000;
    private static final long VM_RAM_MB = 16_384;
    private static final long VM_BW = 1_000_000;
    private static final long VM_SIZE = 100_000;

    private static final double HOST_IDLE_POWER_W = 120.0;
    private static final double HOST_MAX_POWER_W = 400.0;

    // --- Load configuration ---
    public enum LoadMode {
        UNDERLOADED(0.3),
        STABLE(0.7),
        STRESSED(50.0);

        private final double targetUtilization;

        LoadMode(double targetUtilization) {
            this.targetUtilization = targetUtilization;
        }

        public double getTargetUtilization() {
            return targetUtilization;
        }
    }

    // Set desired load mode here:
    private static final LoadMode LOAD_MODE = LoadMode.STABLE;

    // --- Logging & Optimization ---
    private static final int LOG_INTERVAL_SECONDS = 30;

    // --- Cloudlet creation controls ---
    private static final long MIN_CLOUDLET_LENGTH = 500L;
    private static final long MAX_CLOUDLET_LENGTH = 500_000L;
    private static final double CPU_LENGTH_SCALE_FACTOR = 50.0; // Scaled up so tasks take longer

    // --- Utilization scaling ---
    // req_cpu and req_memory are normalized fractions [0,1] from the Google trace.
    // CPU: Use UtilizationModelFull (100%) per Google examples — TimeShared
    // scheduler divides available capacity proportionally among cloudlets.
    // Cloudlet "length" alone controls how much work each task needs.
    // RAM: Scale factor converts normalized fraction to per-cloudlet VM RAM usage.
    // Values kept small because TimeShared SUMS utilization across
    // concurrent cloudlets. With ~6 concurrent cloudlets per VM,
    // per-cloudlet RAM must stay under ~15% for sum to remain under 100%.
    private static final double RAM_SCALE_FACTOR = 0.10;
    private static final double MIN_RAM_UTIL = 0.002;
    private static final double MAX_RAM_UTIL_PER_CLOUDLET = 0.10;

    private MainSimulation() {
    }

    public static void main(String[] args) throws Exception {
        final String tracePath = args.length > 0 ? args[0] : CLEANED_TRACE_PATH;
        final String endpoint = System.getenv().getOrDefault("HF_PREDICT_URL",
                "https://js135-cloudml.hf.space/predict");
        final String token = System.getenv().getOrDefault("HF_TOKEN",
                "hf_YOUR_TOKEN_HERE");

        // 1) Load trace
        final TraceLoader traceLoader = new TraceLoader();
        final List<TaskTrace> traces = traceLoader.load(tracePath);

        // 1.5) Prompt user for ML Model selection
        System.out.println("---------------------------------------------------------");
        System.out.println("Select ML Model for this simulation run:");
        System.out.println("(1) LightGBM (lgbm) [Default]");
        System.out.println("(2) Logistic Regression (lr)");
        System.out.println("(3) Random Forest (rf)");
        System.out.println("(4) XGBoost (xgb)");
        System.out.print("Enter choice (1-4): ");

        String modelName = "lgbm";
        try (java.util.Scanner scanner = new java.util.Scanner(System.in)) {
            String input = scanner.nextLine().trim();
            switch (input) {
                case "2":
                    modelName = "lr";
                    break;
                case "3":
                    modelName = "rf";
                    break;
                case "4":
                    modelName = "xgb";
                    break;
                default:
                    modelName = "lgbm";
                    break;
            }
        } catch (Exception e) {
            System.out.println("Input error, defaulting to lgbm");
            modelName = "lgbm";
        }
        System.out.println("Selected Model: " + modelName.toUpperCase());
        System.out.println("---------------------------------------------------------");

        // 2) Create simulation
        final CloudSimPlus simulation = new CloudSimPlus();

        // 3) Create datacenter with hosts
        final List<Host> hosts = createHosts();
        final DatacenterSimple datacenter = new DatacenterSimple(simulation, hosts, new VmAllocationPolicySimple());
        datacenter.setSchedulingInterval(1.0);

        // 4) Create ML client and broker
        final MLClient mlClient = new MLClient(
                endpoint, token, modelName, Duration.ofSeconds(10), Duration.ofSeconds(60));
        final MLSchedulerBroker broker = new MLSchedulerBroker(simulation, mlClient);

        // 5) Create and submit VMs
        final List<Vm> vmList = createVms();
        broker.submitVmList(vmList);

        // 6) Create cloudlets from trace and submit
        final List<Cloudlet> cloudlets = createCloudletsFromTrace(traces, broker);
        broker.submitCloudletList(cloudlets);

        // 7) Register energy tracker
        final EnergyTracker energyTracker = new EnergyTracker(hosts, HOST_IDLE_POWER_W, HOST_MAX_POWER_W);
        simulation.addOnClockTickListener(energyTracker::onClockTick);

        final List<Double> times = new ArrayList<>();
        final List<Integer> runningTasksList = new ArrayList<>();
        final List<Integer> waitingTasksList = new ArrayList<>();
        final List<Double> cpuUtilsList = new ArrayList<>();
        final List<Double> ramUtilsList = new ArrayList<>();

        // 8) Register periodic logging (every LOG_INTERVAL_SECONDS simulated seconds)
        simulation.addOnClockTickListener(evt -> {
            final double time = evt.getTime();
            if (time > 0 && ((int) time) % LOG_INTERVAL_SECONDS == 0) {
                final long finished = broker.getCloudletFinishedList().size();

                // Count running and waiting from the cloudlet lists
                long running = 0;
                long waiting = 0;
                for (Cloudlet c : cloudlets) {
                    if (c.getStatus() == Cloudlet.Status.INEXEC)
                        running++;
                    else if (c.getStatus() == Cloudlet.Status.QUEUED
                            || c.getStatus() == Cloudlet.Status.FROZEN)
                        waiting++;
                }

                // Average VM CPU utilization
                double totalVmCpu = 0.0;
                double totalVmRam = 0.0;
                int activeVms = 0;
                for (Vm vm : vmList) {
                    if (vm.getHost() != Host.NULL) {
                        totalVmCpu += clamp01(vm.getCpuPercentUtilization());
                        totalVmRam += clamp01(vm.getRam().getPercentUtilization());
                        activeVms++;
                    }
                }
                final double avgVmCpu = activeVms > 0 ? totalVmCpu / activeVms : 0.0;
                final double avgVmRam = activeVms > 0 ? totalVmRam / activeVms : 0.0;

                times.add(Double.valueOf(time));
                runningTasksList.add(Integer.valueOf((int) running));
                waitingTasksList.add(Integer.valueOf((int) waiting));
                cpuUtilsList.add(Double.valueOf(avgVmCpu));
                ramUtilsList.add(Double.valueOf(avgVmRam));

                System.out.printf(Locale.US,
                        "[t=%.2f] finished=%d running=%d waiting=%d avgVmCPU=%.4f avgVmRAM=%.4f%n",
                        Double.valueOf(time), Long.valueOf(finished), Long.valueOf(running), Long.valueOf(waiting), Double.valueOf(avgVmCpu), Double.valueOf(avgVmRam));
            }
        });

        // 9) Start simulation
        System.out.println("=== Starting simulation in " + LOAD_MODE.name() + " mode (Target ρ="
                + LOAD_MODE.getTargetUtilization() + ") ===");
        simulation.start();
        System.out.println("=== Simulation finished ===");

        // 10) Print summary
        final long totalTasks = broker.getTotalTasks();
        final long completedTasks = broker.getCloudletFinishedList().size();
        // True failures = tasks that never ran at all (no onCloudletFinish event)
        final long trueFailures = totalTasks - completedTasks;
        final long slaViolations = broker.getSlaViolationCount();
        // Tasks that ran and met SLA = completed minus those that violated it
        final long onTimeTasks = completedTasks - slaViolations;

        System.out.println();
        System.out.println("=============== SUMMARY METRICS ===============");
        System.out.println("Total tasks:           " + totalTasks);
        System.out.println("Completed on time:     " + onTimeTasks + "  (ran and met SLA)");
        System.out.println("SLA violations:        " + slaViolations + "  (ran but exceeded threshold)");
        System.out.println("True failures:         " + trueFailures + "  (never executed)");
        System.out.printf(Locale.US, "SLA violation %%:       %.4f%n", Double.valueOf(broker.getSlaViolationPercent()));
        System.out.printf(Locale.US, "True failure rate %%:   %.4f%n",
                Double.valueOf(totalTasks > 0 ? 100.0 * trueFailures / totalTasks : 0.0));
        System.out.printf(Locale.US, "Average waiting time:  %.4f%n", Double.valueOf(broker.getAverageWaitingTime()));
        System.out.printf(Locale.US, "Energy (kWh):          %.6f%n", Double.valueOf(energyTracker.getTotalEnergyKWh()));
        System.out.printf(Locale.US, "Avg CPU utilization:   %.4f%n", Double.valueOf(energyTracker.getAverageCpuUtilization()));
        System.out.printf(Locale.US, "Avg RAM utilization:   %.4f%n", Double.valueOf(energyTracker.getAverageRamUtilization()));
        System.out.println("ML API call count:     " + mlClient.getApiCallCount());
        System.out.printf(Locale.US, "Avg failure_prob:      %.6f%n", Double.valueOf(mlClient.getAverageFailureProbability()));
        System.out.printf(Locale.US, "Simulation time:       %.4f%n", Double.valueOf(energyTracker.getSimulationTime()));
        System.out.printf(Locale.US, "Makespan:              %.4f%n", Double.valueOf(broker.getMakespan()));
        System.out.println("================================================");

        org.cloudsimplus.util.SimulationGrapher.generateTasksChart("cloudsim_ml", times, runningTasksList,
                waitingTasksList);
        org.cloudsimplus.util.SimulationGrapher.generateCpuChart("cloudsim_ml", times, cpuUtilsList);
        org.cloudsimplus.util.SimulationGrapher.generateRamChart("cloudsim_ml", times, ramUtilsList);

        List<Double> waitTimes = broker.getCloudletFinishedList().stream()
                .map(c -> (Double) Math.max(0.0, c.getStartWaitTime()))
                .collect(java.util.stream.Collectors.toList());
        org.cloudsimplus.util.SimulationGrapher.generateWaitTimeHistogram("cloudsim_ml", waitTimes);

        org.cloudsimplus.util.SimulationGrapher.generateSummaryBarChart("cloudsim_ml",
                totalTasks, completedTasks, trueFailures, slaViolations);

        // Save per-cloudlet wait times for the cross-simulation loss comparison chart
        org.cloudsimplus.util.SimulationGrapher.saveLossData("cloudsim_ml", waitTimes);
        // Attempt to generate the combined comparison chart (succeeds if both CSVs
        // exist)
        org.cloudsimplus.util.SimulationGrapher.generateLossComparisonChart();
    }

    private static List<Host> createHosts() {
        final List<Host> hosts = new ArrayList<>(HOSTS);

        for (int i = 0; i < HOSTS; i++) {
            final List<Pe> peList = new ArrayList<>(HOST_PES);
            for (int pe = 0; pe < HOST_PES; pe++) {
                peList.add(new PeSimple(HOST_PE_MIPS));
            }

            final HostSimple host = new HostSimple(HOST_RAM_MB, HOST_BW, HOST_STORAGE, peList);
            host.setVmScheduler(new VmSchedulerTimeShared());
            host.setPowerModel(new PowerModelHostSimple(HOST_MAX_POWER_W, HOST_IDLE_POWER_W));
            hosts.add(host);
        }

        return hosts;
    }

    private static List<Vm> createVms() {
        final List<Vm> vms = new ArrayList<>(VMS);

        for (int i = 0; i < VMS; i++) {
            final VmSimple vm = new VmSimple(VM_MIPS, VM_PES);
            vm.setRam(VM_RAM_MB).setBw(VM_BW).setSize(VM_SIZE);
            vm.setCloudletScheduler(new CloudletSchedulerSpaceShared());
            vms.add(vm);
        }

        return vms;
    }

    private static List<Cloudlet> createCloudletsFromTrace(
            final List<TaskTrace> traces,
            final MLSchedulerBroker broker) {
        final List<Cloudlet> cloudlets = new ArrayList<>(traces.size());

        final int aggFactor = TraceLoader.getAggregationFactor();

        // 1) Calculate true average execution time across all traces
        double totalExecutionTimeSec = 0;
        for (TaskTrace trace : traces) {
            final double rawReqCpu = sanitize(trace.getReqCpu());
            // Workload scales strictly here
            long length = (long) Math.round(rawReqCpu * VM_MIPS * CPU_LENGTH_SCALE_FACTOR * aggFactor);
            length = Math.max(MIN_CLOUDLET_LENGTH, Math.min(MAX_CLOUDLET_LENGTH, length));

            // Cloudlet speed is bounded by its dynamic utilization of VM_MIPS
            final double maxCpuUtil = Math.max(0.05, clamp01(rawReqCpu));
            final double expectedTimeSec = length / (maxCpuUtil * VM_MIPS);
            totalExecutionTimeSec += expectedTimeSec;
        }

        // E[S] = Average execution time in seconds
        final double averageExecutionTimeSec = totalExecutionTimeSec / traces.size();

        // 2) Compute total system capacity (concurrent execution slots)
        // With TimeShared, all VMs can run concurrently. Capacity = VMS * VM_PES
        final double totalSystemCapacity = VMS * VM_PES;

        // 3) Calculate required arrival rate for target utilization:
        // ρ = (λ × E[S]) / Capacity => λ = (ρ × Capacity) / E[S]
        final double targetArrivalRate = (LOAD_MODE.getTargetUtilization() * totalSystemCapacity)
                / averageExecutionTimeSec;
        final double arrivalInterval = 1.0 / targetArrivalRate;

        System.out.printf(
                "[MainSimulation] Load configuration: Mode=%s, Target ρ=%.2f, E[S]=%.2fs, λ=%.4f req/s, Interval=%.4fs%n",
                LOAD_MODE.name(), Double.valueOf(LOAD_MODE.getTargetUtilization()), Double.valueOf(averageExecutionTimeSec), Double.valueOf(targetArrivalRate),
                Double.valueOf(arrivalInterval));

        int i = 0;

        for (TaskTrace trace : traces) {
            final double rawReqCpu = sanitize(trace.getReqCpu());
            final double rawReqMem = sanitize(trace.getReqMemory());

            // PEs: Individual traced tasks are lightweight compared to aggregate tasks
            // Each task requires 1 PE to allow queue theoretical 144 capacity
            final int pes = 1;

            // Length: proportional to req_cpu * VM_MIPS * AGGREGATION_FACTOR, bounded
            long length = (long) Math.round(rawReqCpu * VM_MIPS * CPU_LENGTH_SCALE_FACTOR * aggFactor);
            length = Math.max(MIN_CLOUDLET_LENGTH, Math.min(MAX_CLOUDLET_LENGTH, length));

            // RAM utilization: use two-arg constructor (initial=0, max=ramUtil)
            // per the Google trace examples — prevents initial RAM spike
            final double ramUtil = clamp01(
                    Math.max(MIN_RAM_UTIL, Math.min(MAX_RAM_UTIL_PER_CLOUDLET, rawReqMem * RAM_SCALE_FACTOR)));

            final CloudletSimple cloudlet = new CloudletSimple(length, pes);
            cloudlet.setSizes(1024);
            cloudlet.setSubmissionDelay(i * arrivalInterval);
            cloudlet.setPriority(trace.getHighPriorityFlag() > 0 ? 2 : 1);

            // CPU: Utilization proportional to requested CPU (avoids artificial 100% load
            // spikes which falsely trigger SLA CPU limits under aggregation)
            final double maxCpuUtil = Math.max(0.05, clamp01(rawReqCpu));
            cloudlet.setUtilizationModelCpu(new UtilizationModelDynamic(Math.min(0.05, maxCpuUtil), maxCpuUtil));
            cloudlet.setUtilizationModelRam(new UtilizationModelDynamic(0, ramUtil));
            cloudlet.setUtilizationModelBw(new UtilizationModelDynamic(0.001));

            broker.registerCloudletMetadata(cloudlet, trace);
            cloudlet.addOnFinishListener(info -> broker.onCloudletFinish(info.getCloudlet()));

            cloudlets.add(cloudlet);
            i++;
        }

        System.out.printf("[MainSimulation] Created %d cloudlets from trace%n", Integer.valueOf(cloudlets.size()));
        return cloudlets;
    }

    private static double sanitize(final double v) {
        if (Double.isNaN(v) || Double.isInfinite(v))
            return 0.0;
        return Math.max(0.0, v);
    }

    private static double clamp01(final double v) {
        if (Double.isNaN(v) || Double.isInfinite(v))
            return 0.0;
        if (v < 0)
            return 0;
        if (v > 1)
            return 1;
        return v;
    }
}
