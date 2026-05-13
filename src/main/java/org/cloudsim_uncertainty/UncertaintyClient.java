package org.cloudsim_uncertainty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.ArrayList;

public final class UncertaintyClient {

    private final HttpClient httpClient;
    private final String endpoint;
    private final String token;
    private final Duration requestTimeout;
    private final Map<String, QuantileResponse> cache;
    private final LongAdder apiCallCount;

    // Regex to match our exact Quantile FastAPI JSON Schema independently of JSON
    // libraries
    private static final Pattern BLOCK_PATTERN = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\{([^{}]*)\\}");
    private static final Pattern P10_PATTERN = Pattern.compile("\"p10\"\\s*:\\s*([-+]?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?)");
    private static final Pattern P50_PATTERN = Pattern.compile("\"p50\"\\s*:\\s*([-+]?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?)");
    private static final Pattern P90_PATTERN = Pattern.compile("\"p90\"\\s*:\\s*([-+]?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?)");

    public UncertaintyClient(final String endpoint, final String token, final Duration connectTimeout,
            final Duration requestTimeout) {
        this.endpoint = endpoint;
        this.token = token;
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        this.cache = new ConcurrentHashMap<>(16_384);
        this.apiCallCount = new LongAdder();
    }

    public QuantileResponse predict(final SchedulingMetrics metrics) {
        final String key = createCacheKey(metrics);
        final QuantileResponse cached = cache.get(key);
        if (cached != null)
            return cached;

        final QuantileResponse decision = callApiSingle(metrics);
        cache.put(key, decision);
        return decision;
    }

    public long getApiCallCount() {
        return apiCallCount.sum();
    }

    private QuantileResponse callApiSingle(final SchedulingMetrics m) {
        if (!endpoint.startsWith("http"))
            return QuantileResponse.fallback();
        final String payload = buildPayload(m);

        try {
            final HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload));

            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }

            final HttpRequest request = builder.build();

            apiCallCount.increment();
            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                System.err.printf("[UncertaintyClient] HTTP %d: %s%n", Integer.valueOf(response.statusCode()), response.body());
                return QuantileResponse.fallback();
            }

            return parseQuantileResponse(response.body());
        } catch (Exception e) {
            System.err.println("[UncertaintyClient] ERROR: " + e.getMessage());
            return QuantileResponse.fallback();
        }
    }

    private static QuantileResponse parseQuantileResponse(final String body) {
        final List<QuantileResponse> singleList = parseBatchResponse(body);
        if (singleList.isEmpty())
            return QuantileResponse.fallback();
        return singleList.get(0);
    }

    private static List<QuantileResponse> parseBatchResponse(final String body) {
        final List<QuantileResponse> list = new ArrayList<>();
        QuantilePrediction avgCpu = null, avgMem = null, maxCpu = null, duration = null;

        Matcher blockMatcher = BLOCK_PATTERN.matcher(body);
        while (blockMatcher.find()) {
            String blockName = blockMatcher.group(1);
            String blockContent = blockMatcher.group(2);
            QuantilePrediction parsedBlock = parsePredictionBlock(blockContent);

            switch (blockName) {
                case "avg_cpu_usage":
                    avgCpu = parsedBlock;
                    break;
                case "avg_memory_usage":
                    avgMem = parsedBlock;
                    break;
                case "max_cpu_usage":
                    maxCpu = parsedBlock;
                    break;
                case "duration_seconds":
                    duration = parsedBlock;
                    break;
            }

            if (avgCpu != null && avgMem != null && maxCpu != null && duration != null) {
                list.add(new QuantileResponse(avgCpu, avgMem, maxCpu, duration, 0.0));
                avgCpu = null;
                avgMem = null;
                maxCpu = null;
                duration = null;
            }
        }
        return list;
    }

    private static QuantilePrediction parsePredictionBlock(final String block) {
        double p10 = extractDouble(block, P10_PATTERN, 0.0);
        double p50 = extractDouble(block, P50_PATTERN, 0.0);
        double p90 = extractDouble(block, P90_PATTERN, 0.0);
        return new QuantilePrediction(p10, p50, p90);
    }

    private static double extractDouble(final String body, final Pattern pattern, final double fallback) {
        final Matcher matcher = pattern.matcher(body);
        if (!matcher.find())
            return fallback;
        try {
            return Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String buildPayload(final SchedulingMetrics m) {
        return String.format(Locale.US,
                "{"
                        + "\"scheduling_class\":%.6f,"
                        + "\"collection_type\":%.6f,"
                        + "\"priority\":%.6f,"
                        + "\"constraint\":%.6f,"
                        + "\"vertical_scaling\":%.6f,"
                        + "\"scheduler\":%.6f,"
                        + "\"sample_rate\":%.6f,"
                        + "\"req_cpu\":%.6f,"
                        + "\"req_memory\":%.6f,"
                        + "\"cpu_memory_ratio\":%.6f,"
                        + "\"request_intensity\":%.6f,"
                        + "\"high_priority_flag\":%d,"
                        + "\"scaling_flag\":%d"
                        + "}",
                Double.valueOf(m.getSchedulingClass()), Double.valueOf(m.getCollectionType()), Double.valueOf(m.getPriority()),
                Double.valueOf(m.getConstraint()), Double.valueOf(m.getVerticalScaling()), Double.valueOf(m.getScheduler()),
                Double.valueOf(m.getSampleRate()), Double.valueOf(m.getReqCpu()), Double.valueOf(m.getReqMemory()),
                Double.valueOf(m.getCpuMemoryRatio()), Double.valueOf(m.getRequestIntensity()),
                Integer.valueOf(m.getHighPriorityFlag()), Integer.valueOf(m.getScalingFlag()));
    }

    public List<QuantileResponse> predictBatch(final List<SchedulingMetrics> batch) {
        final List<QuantileResponse> fallback = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++)
            fallback.add(QuantileResponse.fallback());

        if (!endpoint.startsWith("http") || batch.isEmpty())
            return fallback;

        final StringBuilder builder = new StringBuilder(batch.size() * 256).append("[");
        for (int i = 0; i < batch.size(); i++) {
            builder.append(buildPayload(batch.get(i)));
            if (i < batch.size() - 1)
                builder.append(",");
        }
        builder.append("]");

        try {
            final String batchEndpoint = endpoint.replace("/predict", "/predict_batch");
            final HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(batchEndpoint))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(builder.toString()));

            if (token != null && !token.isBlank())
                reqBuilder.header("Authorization", "Bearer " + token);

            apiCallCount.add(batch.size());
            final HttpResponse<String> response = httpClient.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                System.err.printf("[UncertaintyClient] HTTP %d: %s%n", Integer.valueOf(response.statusCode()), response.body());
                return fallback;
            }
            final List<QuantileResponse> parsed = parseBatchResponse(response.body());
            if (parsed.size() != batch.size()) {
                System.err.println(
                        "[UncertaintyClient] BATCH ERROR: Response size does not match request size! Falling back.");
                return fallback;
            }
            return parsed;
        } catch (Exception e) {
            System.err.println("[UncertaintyClient] BATCH CRASH: " + e.getMessage());
            return fallback;
        }
    }

    private String createCacheKey(final SchedulingMetrics m) {
        return round3(m.getSchedulingClass()) + "|" + round3(m.getCollectionType()) + "|" +
                round3(m.getReqCpu()) + "|" + round3(m.getReqMemory()) + "|" +
                round3(m.getRequestIntensity());
    }

    private static double round3(final double value) {
        return Math.rint(value * 1000.0) / 1000.0;
    }
}
