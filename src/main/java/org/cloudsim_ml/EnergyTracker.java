package org.cloudsim_ml;

import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.listeners.EventInfo;

import java.util.List;

/**
 * Tracks energy consumption and resource utilisation across all hosts during a
 * CloudSim Plus simulation.
 *
 * <p>
 * Power model (linear):
 * 
 * <pre>
 *   P(cpu) = P_idle + (P_max - P_idle) × cpu_util
 * </pre>
 *
 * <p>
 * Fixes applied vs. original:
 * <ol>
 * <li>First tick initialises both {@code firstClock} and {@code lastClock}
 * and returns early — no energy interval is dropped.</li>
 * <li>CPU/RAM accumulators are weighted by {@code deltaSeconds} so the
 * time-averaged utilisation is correct regardless of tick frequency.</li>
 * <li>A separate {@code totalSimSeconds} field tracks elapsed wall-time for
 * the weighted average denominator.</li>
 * <li>Idle power is only charged to hosts that have at least one VM
 * (avoids inflating energy for powered-off / unused hosts).</li>
 * </ol>
 */
public final class EnergyTracker {

    private final List<Host> hosts;
    private final double idlePowerWatts;
    private final double maxPowerWatts;

    /** Simulation clock at the very first tick. */
    private double firstClock = -1.0;

    /** Simulation clock at the previous tick. */
    private double lastClock = -1.0;

    /** Accumulated energy across all hosts (Wh). */
    private double totalEnergyWh = 0.0;

    /**
     * Time-weighted CPU utilisation sum (fraction × seconds, summed per host).
     * Divide by {@code totalSimSeconds × hosts.size()} to get average.
     */
    private double cpuUtilWeightedSum = 0.0;

    /**
     * Time-weighted RAM utilisation sum (fraction × seconds, summed per host).
     */
    private double ramUtilWeightedSum = 0.0;

    /** Total simulated seconds elapsed (used as the time-weight denominator). */
    private double totalSimSeconds = 0.0;

    // -------------------------------------------------------------------------

    public EnergyTracker(final List<Host> hosts,
            final double idlePowerWatts,
            final double maxPowerWatts) {
        this.hosts = hosts;
        this.idlePowerWatts = idlePowerWatts;
        this.maxPowerWatts = maxPowerWatts;
    }

    // -------------------------------------------------------------------------

    /**
     * Called on every simulation clock tick.
     * Register this via
     * {@code simulation.addOnClockTickListener(tracker::onClockTick)}.
     */
    public void onClockTick(final EventInfo evt) {
        final double now = evt.getTime();

        // Fix 1 — initialise on the very first tick and return; no energy lost.
        if (lastClock < 0) {
            firstClock = now;
            lastClock = now;
            return;
        }

        final double deltaSeconds = now - lastClock;
        if (deltaSeconds <= 0)
            return;

        final double deltaHours = deltaSeconds / 3_600.0;

        for (final Host host : hosts) {

            // Fix 4 — skip hosts with no VMs (no idle energy for unused hardware).
            if (host.getVmList().isEmpty())
                continue;

            final double cpu = clamp01(host.getCpuPercentUtilization());
            final double ram = clamp01(host.getRam().getPercentUtilization());

            // Linear power model
            final double powerW = idlePowerWatts + (maxPowerWatts - idlePowerWatts) * cpu;
            totalEnergyWh += powerW * deltaHours;

            // Fix 2 — weight utilisation samples by interval length
            cpuUtilWeightedSum += cpu * deltaSeconds;
            ramUtilWeightedSum += ram * deltaSeconds;
        }

        // Fix 3 — accumulate real elapsed time for the denominator
        totalSimSeconds += deltaSeconds;
        lastClock = now;
    }

    // -------------------------------------------------------------------------
    // Accessors

    /** Total energy consumed by all active hosts, in kilowatt-hours (kWh). */
    public double getTotalEnergyKWh() {
        return totalEnergyWh / 1_000.0;
    }

    /**
     * Time-weighted average CPU utilisation across all hosts (0.0 – 1.0).
     * Accounts for variable tick intervals.
     */
    public double getAverageCpuUtilization() {
        if (totalSimSeconds <= 0 || hosts.isEmpty())
            return 0.0;
        return cpuUtilWeightedSum / (totalSimSeconds * hosts.size());
    }

    /**
     * Time-weighted average RAM utilisation across all hosts (0.0 – 1.0).
     * Accounts for variable tick intervals.
     */
    public double getAverageRamUtilization() {
        if (totalSimSeconds <= 0 || hosts.isEmpty())
            return 0.0;
        return ramUtilWeightedSum / (totalSimSeconds * hosts.size());
    }

    /** Total simulated duration in seconds (first tick → last tick). */
    public double getSimulationTime() {
        if (firstClock < 0 || lastClock < firstClock)
            return 0.0;
        return lastClock - firstClock;
    }

    // -------------------------------------------------------------------------

    private static double clamp01(final double value) {
        if (Double.isNaN(value) || Double.isInfinite(value))
            return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }
}
