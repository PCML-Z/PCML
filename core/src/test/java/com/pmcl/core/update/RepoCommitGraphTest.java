package com.pmcl.core.update;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepoCommitGraphTest {

    @Test
    void mergeUsesASecondLane() {
        RepoCommitGraph.Commit a = commit("aaaaaaaa", "merge", "bbbbbbbb", "cccccccc");
        RepoCommitGraph.Commit b = commit("bbbbbbbb", "left", "dddddddd");
        RepoCommitGraph.Commit c = commit("cccccccc", "right", "dddddddd");
        RepoCommitGraph.Commit d = commit("dddddddd", "base");
        RepoCommitGraph.Graph graph = RepoCommitGraph.layout("main", List.of("main"), List.of(a, b, c, d));
        assertEquals(0, graph.getCommits().get(0).getLane());
        assertEquals(1, graph.getCommits().get(2).getLane());
        assertEquals(0, graph.getCommits().get(3).getLane());
        assertTrue(graph.getMaxLane() >= 1);
        assertTrue(graph.getEdges().stream().anyMatch(edge ->
                edge.getFromRow() == 2 && edge.getFromLane() == 1
                        && edge.getToRow() == 3 && edge.getToLane() == 0));
    }

    @Test
    void linearHistoryStaysOnOneLane() {
        RepoCommitGraph.Graph graph = RepoCommitGraph.layout("main", List.of("main"), List.of(
                commit("aaaaaaaa", "c3", "bbbbbbbb"),
                commit("bbbbbbbb", "c2", "cccccccc"),
                commit("cccccccc", "c1")));
        assertEquals(0, graph.getMaxLane());
        assertEquals(2, graph.getEdges().size());
        assertTrue(graph.getEdges().stream().allMatch(edge -> edge.getFromLane() == 0 && edge.getToLane() == 0));
    }

    @Test
    void laterBranchStopsAtKnownHistory() {
        Map<String, RepoCommitGraph.Node> map = new LinkedHashMap<>();
        List<RepoCommitGraph.Parsed> main = RepoCommitGraph.parseCommitArray("""
                [
                  {"sha":"aaaaaaaa","commit":{"message":"a","author":{"date":"2026-08-04T00:00:00Z"}},"parents":[{"sha":"bbbbbbbb"}]},
                  {"sha":"bbbbbbbb","commit":{"message":"b","author":{"date":"2026-08-03T00:00:00Z"}},"parents":[]}
                ]
                """);
        org.junit.jupiter.api.Assertions.assertFalse(RepoCommitGraph.absorb(map, "main", main, "", true));
        List<RepoCommitGraph.Parsed> feature = RepoCommitGraph.parseCommitArray("""
                [
                  {"sha":"cccccccc","commit":{"message":"c","author":{"date":"2026-08-05T00:00:00Z"}},"parents":[{"sha":"aaaaaaaa"}]}
                ]
                """);
        org.junit.jupiter.api.Assertions.assertFalse(RepoCommitGraph.absorb(map, "feature", feature, "", true));
        org.junit.jupiter.api.Assertions.assertTrue(RepoCommitGraph.absorb(map, "feature", List.of(main.get(0)), "", false));
        org.junit.jupiter.api.Assertions.assertTrue(map.get("bbbbbbbb").sources.contains("feature"));
        org.junit.jupiter.api.Assertions.assertEquals(List.of("feature"), map.get("cccccccc").branches);
        org.junit.jupiter.api.Assertions.assertEquals(List.of("main"), map.get("aaaaaaaa").branches);
    }

    @Test
    void parsesSubjectAuthorAndDropsBadParent() {
        String json = """
                [{
                  "sha": "abcdef1234567890",
                  "commit": {"message": "docs: 更新说明\\n\\n正文", "author": {"name": "PMCL", "date": "2026-08-04T14:51:00Z"}},
                  "author": {"login": "peddlejumper"},
                  "parents": [{"sha": "../evil"}, {"sha": "1234abcd"}]
                }]
                """;
        List<RepoCommitGraph.Parsed> parsed = RepoCommitGraph.parseCommitArray(json);
        assertEquals(1, parsed.size());
        assertEquals("docs: 更新说明", parsed.get(0).commit.getSubject());
        assertEquals("正文", parsed.get(0).commit.getBody());
        assertEquals("peddlejumper", parsed.get(0).commit.getAuthor());
        assertEquals(List.of("1234abcd"), parsed.get(0).commit.getParents());
        assertThrows(Exception.class, () -> RepoCommitGraph.readDefaultBranch("{\"default_branch\":\"../x\"}"));
    }

    private static RepoCommitGraph.Commit commit(String sha, String subject, String... parents) {
        return new RepoCommitGraph.Commit(sha, subject, "", "dev", "2026/8/4 22:51",
                List.of(parents), List.of(), List.of("main"), 0L, 0);
    }
}
