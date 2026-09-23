package com.onboardassist.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.*;

@Service
public class GeminiService {

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.chat.model}")
    private String chatModel;

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

        Map response;

try {
    response = geminiWebClient.post()
            .uri(uriBuilder -> uriBuilder
                    .path("/v1beta/models/{model}:generateContent")
                    .queryParam("key", apiKey)
                    .build(chatModel))
            .bodyValue(body)
            .retrieve()
            .bodyToMono(Map.class)
            .block();

} catch (Exception e) {
    e.printStackTrace();
    return "Gemini service is temporarily unavailable. Please try again later.";
}

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
        return "Sorry, I couldn't generate a response.";
    }
}
