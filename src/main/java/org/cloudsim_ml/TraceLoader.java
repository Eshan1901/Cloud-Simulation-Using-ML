package org.cloudsim_ml;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class TraceLoader {

    public enum TraceMode {
        FULL,
        WINDOWED,
        PEAK_ONLY
    }

    // Configurable parameters
    private static final TraceMode TRACE_MODE = TraceMode.WINDOWED;
    private static final int WINDOW_START = 0;
    private static final int WINDOW_LIMIT = 10000;
    private static final int AGGREGATION_FACTOR = 1; // 1 = no aggregation
    private static final double PEAK_PERCENTILE = 0.10; // Top 10% highest request_intensity

    private static final String[] REQUIRED_COLUMNS = {
            "scheduling_class",
            "collection_type",
            "priority",
            "constraint",
            "vertical_scaling",
            "scheduler",
            "sample_rate",
            "failed",
            "req_cpu",
            "req_memory",
            "cpu_memory_ratio",
            "request_intensity",
            "high_priority_flag",
            "scaling_flag"
    };

    public List<TaskTrace> load(final String csvPath) throws IOException {
        final List<TaskTrace> rawTraces = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(Path.of(csvPath))) {
            final String header = reader.readLine();
            if (header == null) {
                throw new IOException("CSV file is empty: " + csvPath);
            }

            final List<String> headerColumns = parseCsvLine(header);
            final int[] indexes = new int[REQUIRED_COLUMNS.length];

            for (int i = 0; i < REQUIRED_COLUMNS.length; i++) {
                indexes[i] = headerColumns.indexOf(REQUIRED_COLUMNS[i]);
                if (indexes[i] < 0) {
                    throw new IOException("Missing required column: " + REQUIRED_COLUMNS[i]);
                }
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                final List<String> values = parseCsvLine(line);
                rawTraces.add(new TaskTrace(
                        parseDouble(values, indexes[0], 0.0),
                        parseDouble(values, indexes[1], 0.0),
                        parseDouble(values, indexes[2], 0.0),
                        parseDouble(values, indexes[3], 0.0),
                        parseDouble(values, indexes[4], 0.0),
                        parseDouble(values, indexes[5], 0.0),
                        parseDouble(values, indexes[6], 1.0),
                        parseInt(values, indexes[7], 0),
                        parseDouble(values, indexes[8], 0.1),
                        parseDouble(values, indexes[9], 0.1),
                        parseDouble(values, indexes[10], 1.0),
                        parseDouble(values, indexes[11], 0.2),
                        parseInt(values, indexes[12], 0),
                        parseInt(values, indexes[13], 0)));
            }
        }

        System.out.printf("[TraceLoader] Read %d total trace rows from %s%n", Integer.valueOf(rawTraces.size()), csvPath);

        // Apply Trace Windowing
        List<TaskTrace> windowedTraces = applyWindowing(rawTraces);

        // Apply Aggregation
        List<TaskTrace> finalTraces = applyAggregation(windowedTraces);

        System.out.printf("[TraceLoader] Returning %d processed traces (Mode: %s, Aggregation: %d)%n",
                Integer.valueOf(finalTraces.size()), TRACE_MODE.name(), Integer.valueOf(AGGREGATION_FACTOR));

        return finalTraces;
    }

    private List<TaskTrace> applyWindowing(List<TaskTrace> raw) {
        if (raw.isEmpty() || TRACE_MODE == TraceMode.FULL) {
            return raw;
        }

        if (TRACE_MODE == TraceMode.WINDOWED) {
            int start = Math.max(0, Math.min(WINDOW_START, raw.size() - 1));
            int end = Math.min(start + WINDOW_LIMIT, raw.size());
            return new ArrayList<>(raw.subList(start, end));
        }

        if (TRACE_MODE == TraceMode.PEAK_ONLY) {
            int peakCount = Math.max(1, (int) (raw.size() * PEAK_PERCENTILE));
            // Create a sorted copy by request_intensity descending
            List<TaskTrace> sorted = new ArrayList<>(raw);
            sorted.sort((a, b) -> Double.compare(b.getRequestIntensity(), a.getRequestIntensity()));

            // Get the threshold intensity
            double thresholdIntensity = sorted.get(peakCount - 1).getRequestIntensity();

            // Filter original list to preserve chronological order
            List<TaskTrace> peakTraces = new ArrayList<>();
            for (TaskTrace t : raw) {
                if (t.getRequestIntensity() >= thresholdIntensity) {
                    peakTraces.add(t);
                    if (peakTraces.size() == peakCount)
                        break;
                }
            }
            return peakTraces;
        }

        return raw;
    }

    public static int getAggregationFactor() {
        return AGGREGATION_FACTOR;
    }

    private List<TaskTrace> applyAggregation(List<TaskTrace> windowed) {
        if (AGGREGATION_FACTOR <= 1 || windowed.isEmpty()) {
            return windowed;
        }

        List<TaskTrace> aggregated = new ArrayList<>(windowed.size() / AGGREGATION_FACTOR + 1);

        for (int i = 0; i < windowed.size(); i += AGGREGATION_FACTOR) {
            int end = Math.min(i + AGGREGATION_FACTOR, windowed.size());
            List<TaskTrace> batch = windowed.subList(i, end);

            double sumReqCpu = 0;
            double sumReqMem = 0;
            double sumIntensity = 0;
            int maxHighPriority = 0;
            int maxScaling = 0;

            for (TaskTrace t : batch) {
                sumReqCpu += t.getReqCpu();
                sumReqMem += t.getReqMemory();
                sumIntensity += t.getRequestIntensity();
                maxHighPriority = Math.max(maxHighPriority, t.getHighPriorityFlag());
                maxScaling = Math.max(maxScaling, t.getScalingFlag());
            }

            int count = batch.size();
            TaskTrace first = batch.get(0);

            aggregated.add(new TaskTrace(
                    first.getSchedulingClass(),
                    first.getCollectionType(),
                    first.getPriority(),
                    first.getConstraint(),
                    first.getVerticalScaling(),
                    first.getScheduler(),
                    first.getSampleRate(),
                    first.getFailed(),
                    sumReqCpu / count, // MEAN (Distribution-preserving)
                    sumReqMem / count, // MEAN (Distribution-preserving)
                    first.getCpuMemoryRatio(),
                    sumIntensity / count, // MEAN (Distribution-preserving)
                    maxHighPriority, // MAX
                    maxScaling)); // MAX
        }

        return aggregated;
    }

    private static double parseDouble(final List<String> values, final int idx, final double fallback) {
        if (idx < 0 || idx >= values.size())
            return fallback;
        final String s = values.get(idx);
        if (s == null || s.isBlank())
            return fallback;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(final List<String> values, final int idx, final int fallback) {
        if (idx < 0 || idx >= values.size())
            return fallback;
        final String s = values.get(idx);
        if (s == null || s.isBlank())
            return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static List<String> parseCsvLine(final String line) {
        final List<String> parts = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            final char c = line.charAt(i);

            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
                continue;
            }

            if (c == ',' && !inQuotes) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }

        parts.add(current.toString());
        return parts;
    }
}
