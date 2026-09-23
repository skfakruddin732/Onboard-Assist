package com.onboardassist.service;

import com.onboardassist.entity.KnowledgeChunk;
import com.onboardassist.repository.KnowledgeChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {
    
    private final EmbeddingService embeddingService;
    private final KnowledgeChunkRepository chunkRepository;
    private final GeminiService geminiService;
    private final GroqService groqService;
    
    public String processQuery(String question) {
        // 1. Generate embedding for question
        float[] questionVector = embeddingService.generateEmbedding(question);
        
        // 2. Fetch chunks and calculate cosine similarity
        List<KnowledgeChunk> allChunks = chunkRepository.findAll();
        
        List<KnowledgeChunk> similarChunks;
        if (allChunks.isEmpty() || questionVector.length == 0) {
            // Fallback: if embedding failed, try keyword-based search
            if (questionVector.length == 0 && !allChunks.isEmpty()) {
                log.warn("Embedding generation failed. Falling back to keyword search for: '{}'", question);
                similarChunks = keywordFallbackSearch(question, allChunks);
            } else {
                similarChunks = Collections.emptyList();
            }
        } else {
            similarChunks = allChunks.stream()
                .map(chunk -> new AbstractMap.SimpleEntry<>(chunk, calculateCosineSimilarity(questionVector, parseEmbedding(chunk.getEmbedding()))))
                .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
                .limit(5)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        }
        
        // 3. Build context from chunks
        String context = similarChunks.stream()
            .map(KnowledgeChunk::getContent)
            .collect(Collectors.joining("\n\n"));
        
        // 4. Build prompt
        String prompt = buildPrompt(question, context);
        
        // 5. Try Groq LLM first if configured (Ultra-fast, Llama 3)
        if (groqService.isConfigured()) {
            log.info("Groq is configured. Calling Groq LLM for answer generation...");
            String groqAnswer = groqService.generateResponse(prompt);
            if (groqAnswer != null && !groqAnswer.isBlank()) {
                log.info("Successfully received answer from Groq LLM.");
                return groqAnswer;
            }
            log.warn("Groq LLM returned null or failed. Falling back to Gemini...");
        }
        
        // 6. Call Gemini LLM (Primary if Groq not set, or secondary fallback)
        log.info("Calling Gemini LLM for answer generation...");
        String response = geminiService.generateResponse(prompt);
        
        // 7. If Gemini failed but we have context, provide a context-based fallback
        if (isGeminiFailureResponse(response) && !context.isBlank()) {
            log.warn("Both LLMs unavailable. Providing context-based fallback.");
            return "I'm having trouble connecting to the AI service right now, but here's what I found in the knowledge base:\n\n" 
                + context.substring(0, Math.min(context.length(), 1000))
                + "\n\nPlease try again in a moment for a more detailed answer.";
        }
        
        return response;
    }

    /**
     * Simple keyword-based fallback when embedding generation fails.
     * Searches chunk content for question keywords.
     */
    private List<KnowledgeChunk> keywordFallbackSearch(String question, List<KnowledgeChunk> allChunks) {
        String[] keywords = question.toLowerCase().split("\\s+");
        
        return allChunks.stream()
            .map(chunk -> {
                String contentLower = chunk.getContent().toLowerCase();
                long matchCount = Arrays.stream(keywords)
                    .filter(kw -> kw.length() > 3) // skip short words like "the", "is", "a"
                    .filter(contentLower::contains)
                    .count();
                return new AbstractMap.SimpleEntry<>(chunk, matchCount);
            })
            .filter(e -> e.getValue() > 0)
            .sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue()))
            .limit(5)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    private boolean isGeminiFailureResponse(String response) {
        return response != null && (
            response.contains("temporarily unavailable") ||
            response.contains("encountered an error") ||
            response.contains("invalid request") ||
            response.contains("not configured with an API key")
        );
    }

    private float[] parseEmbedding(String embeddingStr) {
        if (embeddingStr == null || embeddingStr.isBlank()) return new float[0];
        String cleaned = embeddingStr.replace("[", "").replace("]", "").trim();
        if (cleaned.isEmpty()) return new float[0];
        String[] parts = cleaned.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                vector[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException e) {
                vector[i] = 0f;
            }
        }
        return vector;
    }

    private double calculateCosineSimilarity(float[] v1, float[] v2) {
        if (v1.length == 0 || v2.length == 0 || v1.length != v2.length) return 0.0;
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < v1.length; i++) {
            dotProduct += v1[i] * v2[i];
            normA += v1[i] * v1[i];
            normB += v2[i] * v2[i];
        }
        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
    
    private String buildPrompt(String question, String context) {
        return """
            You are OnboardAssist, an AI-powered onboarding assistant for Cognizant employees.
            Answer the user's question using ONLY the provided context.
            If the context does not contain relevant information, respond with:
            "I couldn't find relevant information in the available onboarding knowledge base. Please contact your HR or onboarding coordinator for further assistance."
            Keep answers clear, concise, and professional.
            Use numbered steps when explaining procedures.
            
            Context:
            """ + context + """
            
            Question: """ + question + """
            
            Answer:
            """;
    }
}
