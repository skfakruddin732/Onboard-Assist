package com.onboardassist.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class EmbeddingService {

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.embedding.model}")
    private String embeddingModel;

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

        Map response;

try {
    response = geminiWebClient.post()
            .uri(uriBuilder -> uriBuilder
                    .path("/v1beta/models/{model}:embedContent")
                    .queryParam("key", apiKey)
                    .build(embeddingModel))
            .bodyValue(body)
            .retrieve()
            .bodyToMono(Map.class)
            .block();

} catch (Exception e) {
    e.printStackTrace();
    System.out.println("Embedding Error: " + e.getMessage());
    return new float[0];
}

        if (response != null && response.containsKey("embedding")) {
            Map<String, Object> embeddingNode = (Map<String, Object>) response.get("embedding");
            List<Double> values = (List<Double>) embeddingNode.get("values");
            float[] floatValues = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                floatValues[i] = values.get(i).floatValue();
            }
            return floatValues;
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
}
