package org.cloudsim_uncertainty;

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
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainUncertaintySimulation {
    private static final String CLEANED_TRACE_PATH = "models/data/cleaned/borg_cleaned.csv";

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

    public enum LoadMode {
        UNDERLOADED(0.3), STABLE(0.7), STRESSED(50.0);

        private final double targetUtilization;

        LoadMode(double targetUtilization) {
            this.targetUtilization = targetUtilization;
        }

        public double getTargetUtilization() {
            return targetUtilization;
        }
    }

    private static final LoadMode LOAD_MODE = LoadMode.STABLE;
    private static final int LOG_INTERVAL_SECONDS = 30;
    private static final long MIN_CLOUDLET_LENGTH = 500L;
    private static final long MAX_CLOUDLET_LENGTH = 500_000L;
    private static final double CPU_LENGTH_SCALE_FACTOR = 50.0;
    private static final double RAM_SCALE_FACTOR = 0.10;
    private static final double MIN_RAM_UTIL = 0.002;
    private static final double MAX_RAM_UTIL_PER_CLOUDLET = 0.10;

    private MainUncertaintySimulation() {
    }

    public static void main(String[] args) throws Exception {
        final String tracePath = args.length > 0 ? args[0] : CLEANED_TRACE_PATH;
        final String endpoint = System.getenv().getOrDefault("HF_PREDICT_URL",
                "https://js135-quantile-cloudsim.hf.space/predict");
        final String token = System.getenv().getOrDefault("HF_TOKEN", "hf_YOUR_TOKEN_HERE");

        System.out.println("---------------------------------------------------------");
        System.out.println("Starting CloudSim Uncertainty ML Framework (Risk-Aware P90 Scheduler)");
        System.out.println("FastAPI Endpoint: " + endpoint);
        System.out.println("---------------------------------------------------------");

        final TraceLoader traceLoader = new TraceLoader();
        final List<TaskTrace> traces = traceLoader.load(tracePath);

        final CloudSimPlus simulation = new CloudSimPlus();

        final List<Host> hosts = createHosts();
        final DatacenterSimple datacenter = new DatacenterSimple(simulation, hosts, new VmAllocationPolicySimple());
        datacenter.setSchedulingInterval(1.0);

        final UncertaintyClient uncertaintyClient = new UncertaintyClient(endpoint, token, Duration.ofSeconds(10),
                Duration.ofSeconds(60));
        final UncertaintySchedulerBroker broker = new UncertaintySchedulerBroker(simulation, uncertaintyClient);

        final List<Vm> vmList = createVms();
        broker.submitVmList(vmList);

        final List<Cloudlet> cloudlets = createCloudletsFromTrace(traces, broker);
        broker.submitCloudletList(cloudlets);

        final EnergyTracker energyTracker = new EnergyTracker(hosts, HOST_IDLE_POWER_W, HOST_MAX_POWER_W);
        simulation.addOnClockTickListener(energyTracker::onClockTick);

        final List<Double> times = new ArrayList<>();
        final List<Integer> runningTasksList = new ArrayList<>();
        final List<Integer> waitingTasksList = new ArrayList<>();
        final List<Double> cpuUtilsList = new ArrayList<>();
        final List<Double> ramUtilsList = new ArrayList<>();

        simulation.addOnClockTickListener(evt -> {
            final double time = evt.getTime();
            if (time > 0 && ((int) time) % LOG_INTERVAL_SECONDS == 0) {
                final long finished = broker.getCloudletFinishedList().size();
                long running = 0, waiting = 0;
                for (Cloudlet c : cloudlets) {
                    if (c.getStatus() == Cloudlet.Status.INEXEC)
                        running++;
                    else if (c.getStatus() == Cloudlet.Status.QUEUED || c.getStatus() == Cloudlet.Status.FROZEN)
                        waiting++;
                }

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

                System.out.printf(Locale.US, "[t=%.2f] finished=%d running=%d waiting=%d avgVmCPU=%.4f avgVmRAM=%.4f%n",
                        Double.valueOf(time),
                        Long.valueOf(finished), Long.valueOf(running), Long.valueOf(waiting), Double.valueOf(avgVmCpu), Double.valueOf(avgVmRam));
            }
        });

        System.out.println("=== Starting simulation in " + LOAD_MODE.name() + " mode ===");
        simulation.start();
        System.out.println("=== Simulation finished ===");

        System.out.println("\n=============== SUMMARY METRICS ===============");
        final long totalTasks = broker.getTotalTasks();
        final long completedTasks = broker.getCloudletFinishedList().size();
        // True failures = tasks that never ran (never triggered onCloudletFinish)
        final long trueFailures = totalTasks - completedTasks;
        final long slaViolations = broker.getSlaViolationCount();
        final long onTimeTasks = completedTasks - slaViolations;

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
        System.out.println("FastAPI calls made:    " + uncertaintyClient.getApiCallCount());
        System.out.printf(Locale.US, "Simulation time:       %.4f%n", Double.valueOf(energyTracker.getSimulationTime()));
        System.out.printf(Locale.US, "Makespan:              %.4f%n", Double.valueOf(broker.getMakespan()));
        System.out.println("================================================");

        org.cloudsimplus.util.SimulationGrapher.generateTasksChart("cloudsim_uncertainty", times, runningTasksList,
                waitingTasksList);
        org.cloudsimplus.util.SimulationGrapher.generateCpuChart("cloudsim_uncertainty", times, cpuUtilsList);
        org.cloudsimplus.util.SimulationGrapher.generateRamChart("cloudsim_uncertainty", times, ramUtilsList);

        List<Double> waitTimes = broker.getCloudletFinishedList().stream()
                .map(c -> (Double) Math.max(0.0, c.getStartWaitTime()))
                .collect(java.util.stream.Collectors.toList());
        org.cloudsimplus.util.SimulationGrapher.generateWaitTimeHistogram("cloudsim_uncertainty", waitTimes);

        org.cloudsimplus.util.SimulationGrapher.generateSummaryBarChart("cloudsim_uncertainty",
                totalTasks, completedTasks, trueFailures, slaViolations);

        // Save per-cloudlet wait times for the cross-simulation loss comparison chart
        org.cloudsimplus.util.SimulationGrapher.saveLossData("cloudsim_uncertainty", waitTimes);
        // Attempt to generate the combined comparison chart (succeeds if both CSVs
        // exist)
        org.cloudsimplus.util.SimulationGrapher.generateLossComparisonChart();
    }

    private static List<Host> createHosts() {
        final List<Host> hosts = new ArrayList<>(HOSTS);
        for (int i = 0; i < HOSTS; i++) {
            final List<Pe> peList = new ArrayList<>(HOST_PES);
            for (int pe = 0; pe < HOST_PES; pe++)
                peList.add(new PeSimple(HOST_PE_MIPS));
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

    private static List<Cloudlet> createCloudletsFromTrace(final List<TaskTrace> traces,
            final UncertaintySchedulerBroker broker) {
        final List<Cloudlet> cloudlets = new ArrayList<>(traces.size());
        final int aggFactor = TraceLoader.getAggregationFactor();

        double totalExecutionTimeSec = 0;
        for (TaskTrace trace : traces) {
            final double rawReqCpu = sanitize(trace.getReqCpu());
            long length = (long) Math.round(rawReqCpu * VM_MIPS * CPU_LENGTH_SCALE_FACTOR * aggFactor);
            length = Math.max(MIN_CLOUDLET_LENGTH, Math.min(MAX_CLOUDLET_LENGTH, length));
            final double maxCpuUtil = Math.max(0.05, clamp01(rawReqCpu));
            totalExecutionTimeSec += length / (maxCpuUtil * VM_MIPS);
        }

        final double averageExecutionTimeSec = totalExecutionTimeSec / traces.size();
        final double totalSystemCapacity = VMS * VM_PES;
        final double targetArrivalRate = (LOAD_MODE.getTargetUtilization() * totalSystemCapacity)
                / averageExecutionTimeSec;
        final double arrivalInterval = 1.0 / targetArrivalRate;

        int i = 0;
        for (TaskTrace trace : traces) {
            final double rawReqCpu = sanitize(trace.getReqCpu());
            final double rawReqMem = sanitize(trace.getReqMemory());
            long length = (long) Math.round(rawReqCpu * VM_MIPS * CPU_LENGTH_SCALE_FACTOR * aggFactor);
            length = Math.max(MIN_CLOUDLET_LENGTH, Math.min(MAX_CLOUDLET_LENGTH, length));
            final double ramUtil = clamp01(
                    Math.max(MIN_RAM_UTIL, Math.min(MAX_RAM_UTIL_PER_CLOUDLET, rawReqMem * RAM_SCALE_FACTOR)));

            final CloudletSimple cloudlet = new CloudletSimple(length, 1);
            cloudlet.setSizes(1024);
            cloudlet.setSubmissionDelay(i * arrivalInterval);
            cloudlet.setPriority(trace.getHighPriorityFlag() > 0 ? 2 : 1);

            final double maxCpuUtil = Math.max(0.05, clamp01(rawReqCpu));
            cloudlet.setUtilizationModelCpu(new UtilizationModelDynamic(Math.min(0.05, maxCpuUtil), maxCpuUtil));
            cloudlet.setUtilizationModelRam(new UtilizationModelDynamic(0, ramUtil));
            cloudlet.setUtilizationModelBw(new UtilizationModelDynamic(0.001));

            broker.registerCloudletMetadata(cloudlet, trace);
            cloudlet.addOnFinishListener(info -> broker.onCloudletFinish(info.getCloudlet()));
            cloudlets.add(cloudlet);
            i++;
        }
        return cloudlets;
    }

    private static double sanitize(final double v) {
        return (Double.isNaN(v) || Double.isInfinite(v)) ? 0.0 : Math.max(0.0, v);
    }

    private static double clamp01(final double v) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v < 0)
            return 0.0;
        if (v > 1)
            return 1.0;
        return v;
    }
}
