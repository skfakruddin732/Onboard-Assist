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

    @Value("${gemini.chat.model:gemini-1.5-flash}")
    private String chatModel;

    @Value("${gemini.api.retry.max-attempts:2}")
    private int maxRetryAttempts;

    @Value("${gemini.api.retry.initial-delay-ms:1000}")
    private long initialDelayMs;

    private final WebClient geminiWebClient;

    // Supported candidate models to try as fallbacks
    private static final List<String> FALLBACK_MODELS = List.of(
            "gemini-1.5-flash",
            "gemini-1.5-flash-latest",
            "gemini-2.0-flash",
            "gemini-1.5-pro"
    );

    public GeminiService(WebClient geminiWebClient) {
        this.geminiWebClient = geminiWebClient;
    }

    public String generateResponse(String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("GEMINI_API_KEY is not configured or blank!");
            return "Gemini service is not configured with an API key. Please check your environment variables.";
        }

        Map<String, Object> body = new HashMap<>();
        Map<String, Object> contentMap = new HashMap<>();
        Map<String, String> partMap = new HashMap<>();
        partMap.put("text", prompt);
        contentMap.put("parts", List.of(partMap));
        body.put("contents", List.of(contentMap));

        // Build list of models to try: configured model first, followed by fallbacks
        List<String> modelsToTry = new ArrayList<>();
        if (chatModel != null && !chatModel.isBlank()) {
            modelsToTry.add(chatModel.trim());
        }
        for (String m : FALLBACK_MODELS) {
            if (!modelsToTry.contains(m)) {
                modelsToTry.add(m);
            }
        }

        Exception lastException = null;

        for (String currentModel : modelsToTry) {
            log.info("Attempting Gemini generation using model: '{}'", currentModel);

            for (int attempt = 1; attempt <= maxRetryAttempts; attempt++) {
                try {
                    Map response = geminiWebClient.post()
                            .uri(uriBuilder -> uriBuilder
                                    .path("/v1beta/models/{model}:generateContent")
                                    .queryParam("key", apiKey.trim())
                                    .build(currentModel))
                            .bodyValue(body)
                            .retrieve()
                            .bodyToMono(Map.class)
                            .timeout(Duration.ofSeconds(30))
                            .block();

                    if (response != null && response.containsKey("candidates")) {
                        List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
                        if (!candidates.isEmpty()) {
                            Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
                            if (content != null && content.containsKey("parts")) {
                                List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
                                if (!parts.isEmpty()) {
                                    String answer = (String) parts.get(0).get("text");
                                    log.info("Gemini response generated successfully using '{}' on attempt {}", currentModel, attempt);
                                    return answer;
                                }
                            }
                        }
                    }

                    // If response arrived but had no candidates (e.g. safety blocks)
                    log.warn("Gemini response from model '{}' contained no candidates: {}", currentModel, response);
                    break; // try next model or break

                } catch (WebClientResponseException e) {
                    lastException = e;
                    int statusCode = e.getStatusCode().value();
                    String errorBody = e.getResponseBodyAsString();
                    log.warn("Gemini generateContent with model '{}' (attempt {}/{}) failed with HTTP {}: {}",
                            currentModel, attempt, maxRetryAttempts, statusCode, errorBody);

                    if (statusCode == 404) {
                        // Model not found — stop retrying this model, move to next model immediately
                        log.info("Model '{}' returned 404 Not Found, switching to next fallback model.", currentModel);
                        break;
                    } else if (statusCode == 503 || statusCode == 429 || statusCode == 500) {
                        // Retryable error with backoff
                        if (attempt < maxRetryAttempts) {
                            long delayMs = initialDelayMs * (long) Math.pow(2, attempt - 1);
                            log.info("Retrying model '{}' in {}ms...", currentModel, delayMs);
                            sleep(delayMs);
                        }
                    } else if (statusCode == 400) {
                        log.error("Bad Request (HTTP 400) from Gemini API: {}", errorBody);
                        // If it's an invalid key or parameter, retrying the same model won't help
                        break;
                    } else {
                        log.error("Unexpected HTTP error {} from Gemini API: {}", statusCode, errorBody);
                        break;
                    }

                } catch (Exception e) {
                    lastException = e;
                    log.warn("Gemini call with model '{}' (attempt {}/{}) threw exception: {}",
                            currentModel, attempt, maxRetryAttempts, e.getMessage());

                    if (attempt < maxRetryAttempts) {
                        long delayMs = initialDelayMs * (long) Math.pow(2, attempt - 1);
                        sleep(delayMs);
                    }
                }
            }
        }

        if (lastException != null) {
            log.error("All Gemini model attempts failed. Last error: {}", lastException.getMessage());
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
