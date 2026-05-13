package org.cloudsim_uncertainty;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class TraceLoader {

    public enum TraceMode {
        FULL, WINDOWED, PEAK_ONLY
    }

    private static final TraceMode TRACE_MODE = TraceMode.WINDOWED;
    private static final int WINDOW_START = 0;
    private static final int WINDOW_LIMIT = 10000;
    private static final int AGGREGATION_FACTOR = 1;
    private static final double PEAK_PERCENTILE = 0.10;

    // Columns always present in both datasets
    private static final String[] BASE_COLUMNS = {
            "scheduling_class", "collection_type", "priority", "constraint",
            "vertical_scaling", "scheduler", "sample_rate", "req_cpu", "req_memory"
    };
    // Columns only in cleaned_google_trace.csv (pre-engineered)
    private static final String COL_CPU_MEM_RATIO = "cpu_memory_ratio";
    private static final String COL_REQUEST_INTENSITY = "request_intensity";
    private static final String COL_HIGH_PRIORITY_FLAG = "high_priority_flag";
    private static final String COL_SCALING_FLAG = "scaling_flag";
    // Extra columns present in borg_cleaned.csv (rich runtime data)
    private static final String COL_AVG_CPU_USAGE = "avg_cpu_usage";

    public List<TaskTrace> load(final String csvPath) throws IOException {
        final List<TaskTrace> rawTraces = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(Path.of(csvPath))) {
            final String header = reader.readLine();
            if (header == null)
                throw new IOException("CSV file is empty: " + csvPath);

            final List<String> cols = parseCsvLine(header);

            // Resolve base column indexes
            final int[] baseIdx = new int[BASE_COLUMNS.length];
            for (int i = 0; i < BASE_COLUMNS.length; i++) {
                baseIdx[i] = cols.indexOf(BASE_COLUMNS[i]);
                if (baseIdx[i] < 0)
                    throw new IOException("Missing required column: " + BASE_COLUMNS[i]);
            }

            // Detect schema: pre-engineered (google trace) vs raw (borg)
            final int idxCpuMemRatio = cols.indexOf(COL_CPU_MEM_RATIO);
            final int idxReqIntensity = cols.indexOf(COL_REQUEST_INTENSITY);
            final int idxHighPriority = cols.indexOf(COL_HIGH_PRIORITY_FLAG);
            final int idxScalingFlag = cols.indexOf(COL_SCALING_FLAG);
            final int idxAvgCpu = cols.indexOf(COL_AVG_CPU_USAGE);
            final boolean preEngineered = (idxCpuMemRatio >= 0 && idxReqIntensity >= 0
                    && idxHighPriority >= 0 && idxScalingFlag >= 0);
            final boolean isBorgSchema = (idxAvgCpu >= 0 && !preEngineered);

            System.out.printf("[TraceLoader] Detected schema: %s%n",
                    isBorgSchema ? "borg_cleaned (features derived on-the-fly)"
                            : preEngineered ? "google_trace (pre-engineered)" : "unknown (using defaults)");

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank())
                    continue;

                final List<String> v = parseCsvLine(line);

                final double schedulingClass = parseDouble(v, baseIdx[0], 0.0);
                final double collectionType = parseDouble(v, baseIdx[1], 0.0);
                final double priority = parseDouble(v, baseIdx[2], 0.0);
                final double constraint = parseDouble(v, baseIdx[3], 0.0);
                final double verticalScaling = parseDouble(v, baseIdx[4], 0.0);
                final double scheduler = parseDouble(v, baseIdx[5], 0.0);
                final double sampleRate = parseDouble(v, baseIdx[6], 1.0);
                final double reqCpu = parseDouble(v, baseIdx[7], 0.0001);
                final double reqMemory = parseDouble(v, baseIdx[8], 0.0001);

                final double cpuMemRatio;
                final double requestIntensity;
                final int highPriorityFlag;
                final int scalingFlag;

                if (preEngineered) {
                    cpuMemRatio = parseDouble(v, idxCpuMemRatio, 1.0);
                    requestIntensity = parseDouble(v, idxReqIntensity, 0.2);
                    highPriorityFlag = parseInt(v, idxHighPriority, 0);
                    scalingFlag = parseInt(v, idxScalingFlag, 0);
                } else {
                    // Derive engineered features from raw borg columns
                    final double avgCpu = parseDouble(v, idxAvgCpu, reqCpu);
                    cpuMemRatio = reqCpu / Math.max(reqMemory, 1e-9);
                    requestIntensity = avgCpu * cpuMemRatio;
                    highPriorityFlag = priority > 100 ? 1 : 0;
                    scalingFlag = verticalScaling > 0.5 ? 1 : 0;
                }

                rawTraces.add(new TaskTrace(
                        schedulingClass, collectionType, priority, constraint,
                        verticalScaling, scheduler, sampleRate,
                        0, // failed flag unused in quantile model
                        reqCpu, reqMemory, cpuMemRatio, requestIntensity,
                        highPriorityFlag, scalingFlag));
            }
        }

        System.out.printf("[TraceLoader] Read %d total trace rows from %s%n", Integer.valueOf(rawTraces.size()), csvPath);
        List<TaskTrace> windowedTraces = applyWindowing(rawTraces);
        List<TaskTrace> finalTraces = applyAggregation(windowedTraces);
        System.out.printf("[TraceLoader] Returning %d processed traces (Mode: %s, Aggregation: %d)%n",
                Integer.valueOf(finalTraces.size()), TRACE_MODE.name(), Integer.valueOf(AGGREGATION_FACTOR));

        return finalTraces;
    }

    private List<TaskTrace> applyWindowing(List<TaskTrace> raw) {
        if (raw.isEmpty() || TRACE_MODE == TraceMode.FULL)
            return raw;

        if (TRACE_MODE == TraceMode.WINDOWED) {
            int start = Math.max(0, Math.min(WINDOW_START, raw.size() - 1));
            int end = Math.min(start + WINDOW_LIMIT, raw.size());
            return new ArrayList<>(raw.subList(start, end));
        }

        if (TRACE_MODE == TraceMode.PEAK_ONLY) {
            int peakCount = Math.max(1, (int) (raw.size() * PEAK_PERCENTILE));
            List<TaskTrace> sorted = new ArrayList<>(raw);
            sorted.sort((a, b) -> Double.compare(b.getRequestIntensity(), a.getRequestIntensity()));
            double thresholdIntensity = sorted.get(peakCount - 1).getRequestIntensity();

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
        if (AGGREGATION_FACTOR <= 1 || windowed.isEmpty())
            return windowed;

        List<TaskTrace> aggregated = new ArrayList<>(windowed.size() / AGGREGATION_FACTOR + 1);

        for (int i = 0; i < windowed.size(); i += AGGREGATION_FACTOR) {
            int end = Math.min(i + AGGREGATION_FACTOR, windowed.size());
            List<TaskTrace> batch = windowed.subList(i, end);

            double sumReqCpu = 0, sumReqMem = 0, sumIntensity = 0;
            int maxHighPriority = 0, maxScaling = 0;

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
                    first.getSchedulingClass(), first.getCollectionType(), first.getPriority(),
                    first.getConstraint(), first.getVerticalScaling(), first.getScheduler(),
                    first.getSampleRate(), first.getFailed(),
                    sumReqCpu / count, sumReqMem / count, first.getCpuMemoryRatio(),
                    sumIntensity / count, maxHighPriority, maxScaling));
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
