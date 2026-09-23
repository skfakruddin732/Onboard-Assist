package com.onboardassist.service;

import com.onboardassist.entity.KnowledgeChunk;
import com.onboardassist.entity.KnowledgeDocument;
import com.onboardassist.repository.KnowledgeChunkRepository;
import com.onboardassist.repository.KnowledgeDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService implements CommandLineRunner {

    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;

    @Value("${knowledge.base.reseed:false}")
    private boolean forceReseed;

    private static final int CHUNK_SIZE = 600; // characters per chunk
    private static final int CHUNK_OVERLAP = 80;

    @Override
    public void run(String... args) {
        seedDefaultKnowledgeIfEmpty();
    }

    public void seedDefaultKnowledgeIfEmpty() {
        boolean hasCognizantData = false;
        long docCount = documentRepository.count();

        if (docCount > 0) {
            List<KnowledgeDocument> existingDocs = documentRepository.findAll();
            hasCognizantData = existingDocs.stream()
                    .anyMatch(doc -> (doc.getContent() != null && doc.getContent().toLowerCase().contains("cognizant"))
                            || (doc.getTitle() != null && doc.getTitle().toLowerCase().contains("cognizant")));
        }

        if (!forceReseed && docCount > 0 && chunkRepository.count() > 0 && hasCognizantData) {
            log.info("Knowledge base already contains {} Cognizant documents ({} chunks). Skipping seeding.",
                    docCount, chunkRepository.count());
            return;
        }

        if (docCount > 0 && !hasCognizantData) {
            log.info("Existing {} documents do not contain Cognizant information. Upgrading knowledge base to Cognizant documents...", docCount);
        } else if (forceReseed) {
            log.info("Force reseed enabled. Refreshing knowledge base...");
        } else {
            log.info("Knowledge base is empty. Loading Cognizant documents...");
        }

        reseedKnowledgeBase();
    }

    @Transactional
    public void reseedKnowledgeBase() {
        try {
            log.info("Clearing existing knowledge chunks and documents...");
            chunkRepository.deleteAll();
            documentRepository.deleteAll();

            loadDocumentWithFallback(
                    "Cognizant Induction Program",
                    "InductionGuide.txt",
                    "InductionGuide.pdf",
                    "Cognizant HR Onboarding Policy"
            );

            loadDocumentWithFallback(
                    "Cognizant Assessment & Appraisal Policy",
                    "AssessmentPolicy.txt",
                    "AssessmentPolicy.pdf",
                    "Cognizant Performance Management"
            );

            loadDocumentWithFallback(
                    "Cognizant Leave Policy",
                    "LeavePolicy.txt",
                    "LeavePolicy.pdf",
                    "Cognizant India HR Policy"
            );

            loadDocumentWithFallback(
                    "Cognizant Training & Learning Guide",
                    "TrainingGuide.txt",
                    "TrainingGuide.pdf",
                    "Cognizant Academy & GenC Program"
            );

            loadDocumentWithFallback(
                    "Cognizant IT Support Guide",
                    "ITSupportGuide.txt",
                    "ITSupportGuide.pdf",
                    "Cognizant OneIT & Global Service Desk"
            );

            log.info("Knowledge base successfully initialized with {} documents and {} chunks.",
                    documentRepository.count(), chunkRepository.count());

        } catch (Exception e) {
            log.error("Failed to seed Cognizant documents into knowledge base", e);
        }
    }

    private void loadDocumentWithFallback(String title, String txtFileName, String pdfFileName, String source) {
        String content = "";

        // 1. Try reading the detailed text file first (UTF-8)
        try {
            ClassPathResource txtResource = new ClassPathResource("knowledge-base/" + txtFileName);
            if (txtResource.exists()) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(txtResource.getInputStream(), StandardCharsets.UTF_8))) {
                    content = reader.lines().collect(Collectors.joining("\n"));
                    log.info("Loaded '{}' from text resource: {}", title, txtFileName);
                }
            }
        } catch (Exception e) {
            log.warn("Could not read text file {}, will try PDF: {}", txtFileName, e.getMessage());
        }

        // 2. Fall back to PDF if text file was empty or missing
        if (content == null || content.isBlank()) {
            try {
                content = extractTextFromPdf(pdfFileName);
                log.info("Loaded '{}' from PDF resource: {}", title, pdfFileName);
            } catch (Exception e) {
                log.error("Failed to read both {} and {}: {}", txtFileName, pdfFileName, e.getMessage());
                return;
            }
        }

        if (!content.isBlank()) {
            addDocument(title, content, source);
        }
    }

    private String extractTextFromPdf(String pdfFileName) {
        try {
            ClassPathResource resource = new ClassPathResource("knowledge-base/" + pdfFileName);
            try (InputStream inputStream = resource.getInputStream();
                 PDDocument document = PDDocument.load(inputStream)) {
                PDFTextStripper stripper = new PDFTextStripper();
                return stripper.getText(document);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to read PDF: " + pdfFileName, e);
        }
    }

    public KnowledgeDocument addDocument(String title, String content, String source) {
        // 1. Save document
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setTitle(title);
        doc.setContent(content);
        doc.setSource(source);
        doc = documentRepository.save(doc);

        // 2. Split into chunks
        List<String> chunks = splitIntoChunks(content);
        log.info("Document '{}' split into {} chunks. Generating embeddings...", title, chunks.size());

        // 3. Generate embeddings and save each chunk
        for (String chunkContent : chunks) {
            String embedding = embeddingService.generateEmbeddingString(chunkContent);
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setDocument(doc);
            chunk.setContent(chunkContent);
            chunk.setEmbedding(embedding);
            chunkRepository.save(chunk);
        }

        return doc;
    }

    private List<String> splitIntoChunks(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;

        int length = text.length();
        int start = 0;
        while (start < length) {
            int end = Math.min(start + CHUNK_SIZE, length);
            if (end < length) {
                int lastNewline = text.lastIndexOf('\n', end);
                int lastPeriod = text.lastIndexOf('.', end);
                int splitPoint = Math.max(lastNewline, lastPeriod);
                if (splitPoint > start + CHUNK_SIZE / 2) {
                    end = splitPoint + 1;
                }
            }
            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            start = Math.max(start + CHUNK_SIZE - CHUNK_OVERLAP, end);
        }
        return chunks;
    }
}