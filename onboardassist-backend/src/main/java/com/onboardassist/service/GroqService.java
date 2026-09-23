package com.onboardassist.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.*;

@Slf4j
@Service
public class GroqService {

    @Value("${groq.api.key:}")
    private String apiKey;

    @Value("${groq.chat.model:llama-3.3-70b-versatile}")
    private String chatModel;

    @Value("${groq.api.base-url:https://api.groq.com/openai/v1}")
    private String baseUrl;

    private static final List<String> FALLBACK_MODELS = List.of(
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "mixtral-8x7b-32768",
            "gemma2-9b-it"
    );

    public boolean isConfigured() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    public String generateResponse(String prompt) {
        if (!isConfigured()) {
            log.warn("Groq API key is not configured, skipping Groq.");
            return null;
        }

        WebClient client = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim())
                .build();

        List<String> modelsToTry = new ArrayList<>();
        if (chatModel != null && !chatModel.isBlank()) {
            modelsToTry.add(chatModel.trim());
        }
        for (String m : FALLBACK_MODELS) {
            if (!modelsToTry.contains(m)) {
                modelsToTry.add(m);
            }
        }

        for (String model : modelsToTry) {
            try {
                log.info("Attempting Groq generation using model: '{}'", model);

                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("model", model);
                requestBody.put("messages", List.of(
                        Map.of(
                                "role", "system",
                                "content", "You are OnboardAssist, an intelligent and professional onboarding assistant for Cognizant employees. Answer questions accurately, clearly, and concisely based strictly on the provided context. Use bullet points and numbered steps for procedures."
                        ),
                        Map.of(
                                "role", "user",
                                "content", prompt
                        )
                ));
                requestBody.put("temperature", 0.4);
                requestBody.put("max_tokens", 1024);

                Map response = client.post()
                        .uri("/chat/completions")
                        .bodyValue(requestBody)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .timeout(Duration.ofSeconds(20))
                        .block();

                if (response != null && response.containsKey("choices")) {
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
                    if (!choices.isEmpty()) {
                        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                        if (message != null && message.containsKey("content")) {
                            String answer = (String) message.get("content");
                            log.info("Groq response generated successfully with model '{}'", model);
                            return answer;
                        }
                    }
                }

                log.warn("Groq response from model '{}' did not contain valid choices: {}", model, response);

            } catch (WebClientResponseException e) {
                int status = e.getStatusCode().value();
                String body = e.getResponseBodyAsString();
                log.warn("Groq request failed with model '{}' (HTTP {}): {}", model, status, body);

                if (status == 401) {
                    log.error("Groq API key is invalid or unauthorized! Check GROQ_API_KEY.");
                    return null;
                }
            } catch (Exception e) {
                log.warn("Groq request failed with model '{}': {}", model, e.getMessage());
            }
        }

        log.error("All Groq model attempts failed.");
        return null;
    }
}