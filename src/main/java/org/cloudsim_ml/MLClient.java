package org.cloudsim_ml;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MLClient {
    // Configuration
    public static final boolean ML_BATCH_MODE = true;
    public static final int ML_BATCH_SIZE = 1000;

    private static final Pattern FAILURE_PROB_PATTERN = Pattern
            .compile("\"failure_probability\"\\s*:\\s*([-+]?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?)");
    private static final Pattern THRESHOLD_PATTERN = Pattern
            .compile("\"threshold\"\\s*:\\s*([-+]?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?)");
    private static final Pattern DECISION_PATTERN = Pattern.compile("\"decision\"\\s*:\\s*\"([A-Za-z_]+)\"");

    private final HttpClient httpClient;
    private final String endpoint;
    private final String token;
    private final String modelName;
    private final Duration requestTimeout;
    private final Map<String, MLDecision> cache;

    private final LongAdder apiCallCount;
    private final LongAdder returnedDecisionCount;
    private final DoubleAdder failureProbabilityAccumulator;

    public MLClient(
            final String endpoint,
            final String token,
            final String modelName,
            final Duration connectTimeout,
            final Duration requestTimeout) {
        this.modelName = modelName == null || modelName.isBlank() ? "lgbm" : modelName.trim();
        this.endpoint = endpoint + (endpoint.contains("?") ? "&" : "?") + "model_name=" + this.modelName;
        this.token = token == null ? "" : token.trim();
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();

        this.cache = new ConcurrentHashMap<>(16_384);
        this.apiCallCount = new LongAdder();
        this.returnedDecisionCount = new LongAdder();
        this.failureProbabilityAccumulator = new DoubleAdder();
    }

    public MLDecision predict(final SchedulingMetrics metrics) {
        final String key = createCacheKey(metrics);
        final MLDecision cached = cache.get(key);
        if (cached != null) {
            trackDecision(cached);
            return cached;
        }

        final MLDecision decision = callApiSingle(metrics);
        cache.put(key, decision);
        trackDecision(decision);
        return decision;
    }

    /**
     * Batch prediction for maximum throughput.
     */
    public List<MLDecision> predictBatch(final List<SchedulingMetrics> metricsList) {
        if (metricsList == null || metricsList.isEmpty()) {
            return Collections.emptyList();
        }

        if (!ML_BATCH_MODE) {
            // Fallback to single per item if batch is disabled
            List<MLDecision> results = new ArrayList<>(metricsList.size());
            for (SchedulingMetrics m : metricsList) {
                results.add(predict(m));
            }
            return results;
        }

        // 1. Build JSON Array string
        StringBuilder batchPayload = new StringBuilder("[");
        for (int i = 0; i < metricsList.size(); i++) {
            batchPayload.append(buildPayload(metricsList.get(i)));
            if (i < metricsList.size() - 1) {
                batchPayload.append(",");
            }
        }
        batchPayload.append("]");

        // 2. Call API
        List<MLDecision> decisions = callApiBatch(batchPayload.toString(), metricsList.size());

        // 3. Track and return
        for (MLDecision d : decisions) {
            trackDecision(d);
        }
        return decisions;
    }

    public long getApiCallCount() {
        return apiCallCount.sum();
    }

    public double getAverageFailureProbability() {
        final long count = returnedDecisionCount.sum();
        if (count == 0)
            return 0.0;
        return failureProbabilityAccumulator.sum() / count;
    }

    private void trackDecision(final MLDecision decision) {
        returnedDecisionCount.increment();
        failureProbabilityAccumulator.add(sanitize01(decision.getFailureProbability()));
    }

    private MLDecision callApiSingle(final SchedulingMetrics m) {
        if (!endpoint.startsWith("http")) {
            System.err.println("[MLClient] ERROR: endpoint is not a valid URL: " + endpoint);
            return MLDecision.fallback();
        }

        final String payload = buildPayload(m);

        try {
            final HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload));

            if (!token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }

            apiCallCount.increment();
            final HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());

            final int status = response.statusCode();
            final String body = response.body();

            if (status < 200 || status >= 300) {
                System.err.printf("[MLClient] ERROR: HTTP %d from %s%n  Response: %s%n",
                        Integer.valueOf(status), endpoint, body);
                return MLDecision.fallback();
            }

            return parseSingleResponse(body);
        } catch (HttpTimeoutException e) {
            System.err.println("[MLClient] ERROR: Request timed out: " + e.getMessage());
            return MLDecision.fallback();
        } catch (IOException e) {
            System.err.println("[MLClient] ERROR: IO error: " + e.getMessage());
            return MLDecision.fallback();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[MLClient] ERROR: Interrupted: " + e.getMessage());
            return MLDecision.fallback();
        } catch (Exception e) {
            System.err.println("[MLClient] ERROR: Unexpected error: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage());
            return MLDecision.fallback();
        }
    }

    private List<MLDecision> callApiBatch(final String jsonArrayPayload, final int expectedSize) {
        if (!endpoint.startsWith("http")) {
            System.err.println("[MLClient] ERROR: endpoint is not a valid URL: " + endpoint);
            return fallbackList(expectedSize);
        }

        try {
            final HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonArrayPayload));

            if (!token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }

            apiCallCount.increment();
            final HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());

            final int status = response.statusCode();
            final String body = response.body();

            if (status < 200 || status >= 300) {
                System.err.printf("[MLClient] ERROR: HTTP %d from %s%n  Response: %s%n",
                        Integer.valueOf(status), endpoint, body);
                return fallbackList(expectedSize);
            }

            return parseBatchResponse(body, expectedSize);
        } catch (HttpTimeoutException e) {
            System.err.println("[MLClient] ERROR: Batch request timed out: " + e.getMessage());
            return fallbackList(expectedSize);
        } catch (IOException e) {
            System.err.println("[MLClient] ERROR: Batch IO error: " + e.getMessage());
            return fallbackList(expectedSize);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[MLClient] ERROR: Batch interrupted: " + e.getMessage());
            return fallbackList(expectedSize);
        } catch (Exception e) {
            System.err.println("[MLClient] ERROR: Batch unexpected error: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage());
            return fallbackList(expectedSize);
        }
    }

    private List<MLDecision> fallbackList(int size) {
        List<MLDecision> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++)
            result.add(MLDecision.fallback());
        return result;
    }

    /**
     * Builds the JSON payload containing all 13 trace-derived features
     * required by the Hugging Face /predict endpoint.
     * The "failed" column is excluded (it is a label, not a feature).
     * VM-level utilization fields are NOT sent (API rejects them).
     */
    private static String buildPayload(final SchedulingMetrics m) {
        return String.format(
                Locale.US,
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
                Double.valueOf(m.getSchedulingClass()),
                Double.valueOf(m.getCollectionType()),
                Double.valueOf(m.getPriority()),
                Double.valueOf(m.getConstraint()),
                Double.valueOf(m.getVerticalScaling()),
                Double.valueOf(m.getScheduler()),
                Double.valueOf(m.getSampleRate()),
                Double.valueOf(m.getReqCpu()),
                Double.valueOf(m.getReqMemory()),
                Double.valueOf(m.getCpuMemoryRatio()),
                Double.valueOf(m.getRequestIntensity()),
                Integer.valueOf(m.getHighPriorityFlag()),
                Integer.valueOf(m.getScalingFlag()));
    }

    private static MLDecision parseSingleResponse(final String responseBody) {
        final double failureProb = extractDouble(responseBody, FAILURE_PROB_PATTERN, -1.0);
        final double threshold = extractDouble(responseBody, THRESHOLD_PATTERN, -1.0);
        final String decisionText = extractString(responseBody, DECISION_PATTERN, null);

        if (failureProb < 0.0 || threshold < 0.0 || decisionText == null) {
            System.err.printf("[MLClient] ERROR: Schema mismatch in response: %s%n"
                    + "  Parsed: failure_probability=%.4f, threshold=%.4f, decision=%s%n",
                    responseBody, Double.valueOf(failureProb), Double.valueOf(threshold), decisionText);
            return MLDecision.fallback();
        }

        final MLDecision.Mode mode = "CONSERVATIVE".equalsIgnoreCase(decisionText)
                ? MLDecision.Mode.CONSERVATIVE
                : MLDecision.Mode.NORMAL;

        return new MLDecision(sanitize01(failureProb), Math.max(0.0, threshold), mode);
    }

    private static List<MLDecision> parseBatchResponse(final String body, int expectedSize) {
        if (body == null || body.isBlank()) {
            return fallbackListStatic(expectedSize);
        }

        List<MLDecision> decisions = new ArrayList<>(expectedSize);

        Matcher probMatcher = FAILURE_PROB_PATTERN.matcher(body);
        Matcher threshMatcher = THRESHOLD_PATTERN.matcher(body);
        Matcher decMatcher = DECISION_PATTERN.matcher(body);

        while (probMatcher.find() && threshMatcher.find() && decMatcher.find() && decisions.size() < expectedSize) {
            try {
                double prob = Double.parseDouble(probMatcher.group(1));
                double thresh = Double.parseDouble(threshMatcher.group(1));
                String decText = decMatcher.group(1);

                MLDecision.Mode mode = "CONSERVATIVE".equalsIgnoreCase(decText)
                        ? MLDecision.Mode.CONSERVATIVE
                        : MLDecision.Mode.NORMAL;

                decisions.add(new MLDecision(sanitize01(prob), Math.max(0.0, thresh), mode));
            } catch (Exception e) {
                decisions.add(MLDecision.fallback());
            }
        }

        while (decisions.size() < expectedSize) {
            decisions.add(MLDecision.fallback());
        }

        return decisions;
    }

    private static List<MLDecision> fallbackListStatic(int size) {
        List<MLDecision> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++)
            result.add(MLDecision.fallback());
        return result;
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

    private static String extractString(final String body, final Pattern pattern, final String fallback) {
        final Matcher matcher = pattern.matcher(body);
        if (!matcher.find())
            return fallback;
        return matcher.group(1);
    }

    private String createCacheKey(final SchedulingMetrics m) {
        return this.modelName + "|"
                + round3(m.getSchedulingClass()) + "|"
                + round3(m.getCollectionType()) + "|"
                + round3(m.getPriority()) + "|"
                + round3(m.getConstraint()) + "|"
                + round3(m.getVerticalScaling()) + "|"
                + round3(m.getScheduler()) + "|"
                + round3(m.getSampleRate()) + "|"
                + round3(m.getReqCpu()) + "|"
                + round3(m.getReqMemory()) + "|"
                + round3(m.getCpuMemoryRatio()) + "|"
                + round3(m.getRequestIntensity()) + "|"
                + m.getHighPriorityFlag() + "|"
                + m.getScalingFlag();
    }

    private static double round3(final double value) {
        return Math.rint(value * 1000.0) / 1000.0;
    }

    private static double sanitize01(final double v) {
        if (Double.isNaN(v) || Double.isInfinite(v))
            return 0.0;
        if (v < 0.0)
            return 0.0;
        if (v > 1.0)
            return 1.0;
        return v;
    }
}
