package org.cloudsim_uncertainty;

import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.listeners.EventInfo;

import java.util.List;

public final class EnergyTracker {
    private final List<Host> hosts;
    private final double idlePowerWatts;
    private final double maxPowerWatts;

    private double lastClock = -1.0;
    private double firstClock = -1.0;
    private double totalEnergyWh = 0.0;

    private double cpuUtilAccum = 0.0;
    private double ramUtilAccum = 0.0;
    private long utilizationSamples = 0L;

    public EnergyTracker(final List<Host> hosts, final double idlePowerWatts, final double maxPowerWatts) {
        this.hosts = hosts;
        this.idlePowerWatts = idlePowerWatts;
        this.maxPowerWatts = maxPowerWatts;
    }

    public void onClockTick(final EventInfo evt) {
        final double now = evt.getTime();
        if (firstClock < 0)
            firstClock = now;

        if (lastClock < 0) {
            lastClock = now;
            return;
        }

        final double deltaSeconds = now - lastClock;
        if (deltaSeconds <= 0)
            return;

        for (Host host : hosts) {
            final double cpu = clamp01(host.getCpuPercentUtilization());
            final double ram = clamp01(host.getRam().getPercentUtilization());

            final double powerW = idlePowerWatts + (maxPowerWatts - idlePowerWatts) * cpu;
            totalEnergyWh += powerW * (deltaSeconds / 3600.0);

            cpuUtilAccum += cpu;
            ramUtilAccum += ram;
            utilizationSamples++;
        }
        lastClock = now;
    }

    public double getTotalEnergyKWh() {
        return totalEnergyWh / 1000.0;
    }

    public double getAverageCpuUtilization() {
        if (utilizationSamples == 0)
            return 0.0;
        return cpuUtilAccum / utilizationSamples;
    }

    public double getAverageRamUtilization() {
        if (utilizationSamples == 0)
            return 0.0;
        return ramUtilAccum / utilizationSamples;
    }

    public double getSimulationTime() {
        if (firstClock < 0 || lastClock < 0 || lastClock < firstClock)
            return 0.0;
        return lastClock - firstClock;
    }

    private static double clamp01(final double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0)
            return 0.0;
        if (value > 1.0)
            return 1.0;
        return value;
    }
}
