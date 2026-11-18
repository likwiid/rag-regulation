package com.likwiid.ragregulation.retrieval;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.util.PairList;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.likwiid.ragregulation.config.AppProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rescoring with a multilingual cross-encoder run locally through ONNX Runtime. Unlike the embedding
 * model, a cross-encoder reads the question and the passage together, which is slower but more precise,
 * so it only sees the fused candidates.
 */
@Component
public class CrossEncoderReranker {

    private static final int BATCH = 8;

    private final AppProperties properties;
    private HuggingFaceTokenizer tokenizer;
    private OrtEnvironment environment;
    private OrtSession session;
    private Set<String> inputNames;

    public CrossEncoderReranker(AppProperties properties) {
        this.properties = properties;
    }

    private synchronized void load() {
        if (session != null) {
            return;
        }
        try {
            Path dir = Path.of(properties.modelsDir(), "reranker");
            Path model = ModelFiles.ensure(properties.reranker().modelUrl(), dir.resolve("model.onnx"));
            Path tokenizerFile = ModelFiles.ensure(properties.reranker().tokenizerUrl(), dir.resolve("tokenizer.json"));
            tokenizer = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(tokenizerFile)
                    .optMaxLength(properties.reranker().maxLength())
                    .optTruncation(true)
                    .optPadding(true)
                    .build();
            environment = OrtEnvironment.getEnvironment();
            session = environment.createSession(model.toString(), new OrtSession.SessionOptions());
            inputNames = session.getInputNames();
        } catch (IOException | OrtException e) {
            throw new IllegalStateException("Could not load the reranker model", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while loading the reranker model", e);
        }
    }

    /** Returns the candidates ordered by cross-encoder relevance, best first. */
    public List<Chunk> rerank(String question, List<Chunk> candidates) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        load();
        List<Chunk> scored = new ArrayList<>(candidates.size());
        for (int from = 0; from < candidates.size(); from += BATCH) {
            List<Chunk> batch = candidates.subList(from, Math.min(from + BATCH, candidates.size()));
            float[] scores = score(question, batch);
            for (int i = 0; i < batch.size(); i++) {
                scored.add(batch.get(i).withScore(scores[i]));
            }
        }
        scored.sort(Comparator.comparingDouble(Chunk::score).reversed().thenComparingLong(Chunk::id));
        return scored;
    }

    private float[] score(String question, List<Chunk> batch) {
        PairList<String, String> pairs = new PairList<>();
        for (Chunk chunk : batch) {
            pairs.add(question, chunk.articleTitle() + "\n" + chunk.text());
        }
        Encoding[] encodings = tokenizer.batchEncode(pairs);
        long[][] ids = new long[encodings.length][];
        long[][] mask = new long[encodings.length][];
        long[][] types = new long[encodings.length][];
        for (int i = 0; i < encodings.length; i++) {
            ids[i] = encodings[i].getIds();
            mask[i] = encodings[i].getAttentionMask();
            types[i] = encodings[i].getTypeIds();
        }
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try {
            inputs.put("input_ids", OnnxTensor.createTensor(environment, ids));
            inputs.put("attention_mask", OnnxTensor.createTensor(environment, mask));
            if (inputNames.contains("token_type_ids")) {
                inputs.put("token_type_ids", OnnxTensor.createTensor(environment, types));
            }
            try (OrtSession.Result result = session.run(inputs)) {
                float[][] logits = (float[][]) result.get(0).getValue();
                float[] scores = new float[logits.length];
                for (int i = 0; i < logits.length; i++) {
                    scores[i] = logits[i][0];
                }
                return scores;
            }
        } catch (OrtException e) {
            throw new IllegalStateException("Reranker inference failed", e);
        } finally {
            inputs.values().forEach(OnnxTensor::close);
        }
    }
}
