package com.pmcl.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalModelScanTest {

    @TempDir
    Path home;

    @Test
    void readsOllamaManifestsAndSkipsGgufShards() throws Exception {
        Path tag = home.resolve(".ollama/models/manifests/registry.ollama.ai/library/llama3/latest");
        Files.createDirectories(tag.getParent());
        Files.writeString(tag, "{}");
        Path models = home.resolve(".lmstudio/models/demo");
        Files.createDirectories(models);
        Files.writeString(models.resolve("qwen.gguf"), "x");
        Files.writeString(models.resolve("qwen-00001-of-00002.gguf"), "x");

        List<LocalModelScan.Found> found = LocalModelScan.scan(home, url -> null);
        assertTrue(found.stream().anyMatch(item ->
                item.model.equals("llama3:latest") && item.baseUrl.equals("http://127.0.0.1:11434/v1")));
        assertTrue(found.stream().anyMatch(item -> item.model.equals("qwen") && item.source.equals(LocalModelScan.LM_STUDIO)));
        assertTrue(found.stream().noneMatch(item -> item.model.contains("00001")));
    }

    @Test
    void readsLmStudioMlxIndex() throws Exception {
        Path weights = home.resolve(".lmstudio/models/lmstudio-community/Qwen3-VL-4B-Instruct-MLX-4bit");
        Files.createDirectories(weights);
        Files.writeString(weights.resolve("model.safetensors"), "x");
        Path index = home.resolve(".lmstudio/.internal/model-index-cache.json");
        Files.createDirectories(index.getParent());
        Files.writeString(index, """
                {"models":[
                  {"domain":"llm","indexedModelIdentifier":"qwen/qwen3-vl-4b@lmstudio-community/Qwen3-VL-4B-Instruct-MLX-4bit","originalIndexedModelIdentifier":"lmstudio-community/Qwen3-VL-4B-Instruct-MLX-4bit","concreteModelDirAbsolutePath":"%s"},
                  {"domain":"llm","indexedModelIdentifier":"qwen/qwen3-vl-4b","concreteModelDirAbsolutePath":"%s"}
                ]}
                """.formatted(weights, weights));
        List<LocalModelScan.Found> found = LocalModelScan.scan(home, url -> null);
        List<LocalModelScan.Found> studio = found.stream().filter(item -> item.source.equals(LocalModelScan.LM_STUDIO)).toList();
        assertEquals(1, studio.size());
        assertEquals("qwen/qwen3-vl-4b", studio.get(0).model);
        assertEquals("http://127.0.0.1:1234/v1", studio.get(0).baseUrl);
    }

    @Test
    void runningServerReplacesTheDiskList() throws Exception {
        Path tag = home.resolve(".ollama/models/manifests/registry.ollama.ai/library/old/latest");
        Files.createDirectories(tag.getParent());
        Files.writeString(tag, "{}");
        List<LocalModelScan.Found> found = LocalModelScan.scan(home, url ->
                url.contains("11434") ? "{\"models\":[{\"name\":\"live:latest\"}]}" : null);
        assertEquals(1, found.stream().filter(item -> item.source.equals(LocalModelScan.OLLAMA)).count());
        assertEquals("live:latest", found.stream().filter(item -> item.source.equals(LocalModelScan.OLLAMA))
                .findFirst().orElseThrow().model);
    }
}
