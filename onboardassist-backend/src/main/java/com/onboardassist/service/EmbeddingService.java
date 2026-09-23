package com.onboardassist.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class EmbeddingService {

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.embedding.model}")
    private String embeddingModel;

    @Value("${gemini.api.retry.max-attempts:3}")
    private int maxRetryAttempts;

    @Value("${gemini.api.retry.initial-delay-ms:1000}")
    private long initialDelayMs;

    private final WebClient geminiWebClient;

    public EmbeddingService(WebClient geminiWebClient) {
        this.geminiWebClient = geminiWebClient;
    }

    public float[] generateEmbedding(String text) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", "models/" + embeddingModel);
        
        Map<String, Object> contentMap = new HashMap<>();
        Map<String, String> partMap = new HashMap<>();
        partMap.put("text", text);
        contentMap.put("parts", List.of(partMap));
        body.put("content", contentMap);

        Map response = null;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetryAttempts; attempt++) {
            try {
                log.info("Gemini embedContent attempt {}/{} using model '{}'", attempt, maxRetryAttempts, embeddingModel);

                response = geminiWebClient.post()
                        .uri(uriBuilder -> uriBuilder
                                .path("/v1beta/models/{model}:embedContent")
                                .queryParam("key", apiKey)
                                .build(embeddingModel))
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .timeout(Duration.ofSeconds(30))
                        .block();

                // Success — break out of retry loop
                log.info("Gemini embedContent succeeded on attempt {}", attempt);
                break;

            } catch (WebClientResponseException e) {
                lastException = e;
                int statusCode = e.getStatusCode().value();
                log.warn("Gemini embedContent attempt {}/{} failed with HTTP {} — {}",
                        attempt, maxRetryAttempts, statusCode, e.getStatusText());

                if (statusCode == 503 || statusCode == 429 || statusCode == 500) {
                    // Retryable error — wait with exponential backoff
                    if (attempt < maxRetryAttempts) {
                        long delayMs = initialDelayMs * (long) Math.pow(2, attempt - 1);
                        log.info("Retrying in {}ms...", delayMs);
                        sleep(delayMs);
                    }
                } else {
                    log.error("Non-retryable Gemini embedding error (HTTP {}): {}", statusCode, e.getResponseBodyAsString());
                    return new float[0];
                }

            } catch (Exception e) {
                lastException = e;
                log.warn("Gemini embedContent attempt {}/{} failed with exception: {}",
                        attempt, maxRetryAttempts, e.getMessage());

                if (attempt < maxRetryAttempts) {
                    long delayMs = initialDelayMs * (long) Math.pow(2, attempt - 1);
                    log.info("Retrying in {}ms...", delayMs);
                    sleep(delayMs);
                }
            }
        }

        // Parse successful response
        if (response != null && response.containsKey("embedding")) {
            Map<String, Object> embeddingNode = (Map<String, Object>) response.get("embedding");
            List<Double> values = (List<Double>) embeddingNode.get("values");
            float[] floatValues = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                floatValues[i] = values.get(i).floatValue();
            }
            return floatValues;
        }

        // All retries exhausted
        if (lastException != null) {
            log.error("Gemini embedContent failed after {} attempts. Last error: {}",
                    maxRetryAttempts, lastException.getMessage());
        }
        return new float[0];
    }

    public String generateEmbeddingString(String text) {
        float[] embedding = generateEmbedding(text);
        List<Float> floatList = new ArrayList<>();
        for (float f : embedding) {
            floatList.add(f);
        }
        return "[" + floatList.stream().map(String::valueOf).collect(Collectors.joining(",")) + "]";
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
