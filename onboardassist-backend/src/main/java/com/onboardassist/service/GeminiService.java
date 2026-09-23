package com.onboardassist.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.*;

@Slf4j
@Service
public class GeminiService {

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.chat.model}")
    private String chatModel;

    @Value("${gemini.api.retry.max-attempts:3}")
    private int maxRetryAttempts;

    @Value("${gemini.api.retry.initial-delay-ms:1000}")
    private long initialDelayMs;

    private final WebClient geminiWebClient;

    public GeminiService(WebClient geminiWebClient) {
        this.geminiWebClient = geminiWebClient;
    }

    public String generateResponse(String prompt) {
        Map<String, Object> body = new HashMap<>();
        Map<String, Object> contentMap = new HashMap<>();
        Map<String, String> partMap = new HashMap<>();
        partMap.put("text", prompt);
        contentMap.put("parts", List.of(partMap));
        body.put("contents", List.of(contentMap));

        Map response = null;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetryAttempts; attempt++) {
            try {
                log.info("Gemini generateContent attempt {}/{} using model '{}'", attempt, maxRetryAttempts, chatModel);

                response = geminiWebClient.post()
                        .uri(uriBuilder -> uriBuilder
                                .path("/v1beta/models/{model}:generateContent")
                                .queryParam("key", apiKey)
                                .build(chatModel))
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .timeout(Duration.ofSeconds(30))
                        .block();

                // Success — break out of retry loop
                log.info("Gemini generateContent succeeded on attempt {}", attempt);
                break;

            } catch (WebClientResponseException e) {
                lastException = e;
                int statusCode = e.getStatusCode().value();
                log.warn("Gemini generateContent attempt {}/{} failed with HTTP {} — {}",
                        attempt, maxRetryAttempts, statusCode, e.getStatusText());

                if (statusCode == 503 || statusCode == 429 || statusCode == 500) {
                    // Retryable error — wait with exponential backoff
                    if (attempt < maxRetryAttempts) {
                        long delayMs = initialDelayMs * (long) Math.pow(2, attempt - 1);
                        log.info("Retrying in {}ms...", delayMs);
                        sleep(delayMs);
                    }
                } else if (statusCode == 400) {
                    log.error("Bad request to Gemini API (not retryable): {}", e.getResponseBodyAsString());
                    return "Gemini service encountered an invalid request. Please try rephrasing your question.";
                } else {
                    log.error("Unexpected Gemini API error (HTTP {}): {}", statusCode, e.getResponseBodyAsString());
                    return "Gemini service is temporarily unavailable. Please try again later.";
                }

            } catch (Exception e) {
                lastException = e;
                log.warn("Gemini generateContent attempt {}/{} failed with exception: {}",
                        attempt, maxRetryAttempts, e.getMessage());

                if (attempt < maxRetryAttempts) {
                    long delayMs = initialDelayMs * (long) Math.pow(2, attempt - 1);
                    log.info("Retrying in {}ms...", delayMs);
                    sleep(delayMs);
                }
            }
        }

        // Parse successful response
        if (response != null && response.containsKey("candidates")) {
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
            if (!candidates.isEmpty()) {
                Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
                if (content != null && content.containsKey("parts")) {
                    List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
                    if (!parts.isEmpty()) {
                        return (String) parts.get(0).get("text");
                    }
                }
            }
        }

        // All retries exhausted
        if (lastException != null) {
            log.error("Gemini generateContent failed after {} attempts. Last error: {}",
                    maxRetryAttempts, lastException.getMessage());
        }
        return "Gemini service is temporarily unavailable. Please try again later.";
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
