package com.example.remoteservice;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The statement "comments complete" ({@code completeParentIds}) of the
 * template's tool: for each artifact named there, Mirador deletes the
 * comments that are missing. When a call with {@code elementIds} delivers
 * only some of the comments, an artifact with omitted comments must not count
 * as complete.
 */
public class TestInMemoryTool {

    @Test
    public void onlyArtifactsWithoutAnOmittedCommentAreComplete() {
        InMemoryTool tool = new InMemoryTool();
        String a = create(tool, "A");
        String b = create(tool, "B");
        String c = create(tool, "C");
        String onA = comment(tool, a);
        comment(tool, b);

        //Full run: every artifact, also one without a comment
        assertEquals(List.of(a, b, c), tool.readComments(Json.parseObject("{\"cursor\":null}"))
                .get("completeParentIds"));

        //Only the comment on A: B has an omitted comment, A and C have none
        Map<String, Object> named = tool.readComments(Json.parseObject("{\"cursor\":null,\"elementIds\":[\""
                + onA + "\"]}"));
        assertEquals(1L, named.get("readCount"));
        assertEquals(List.of(a, c), named.get("completeParentIds"));
    }

    private static String create(InMemoryTool tool, String name) {
        return (String) tool.createArtifact(Json.parseObject("{\"artifact\":{\"name\":\"Requirement\",\"fields\":"
                + "[{\"name\":\"Name\",\"type\":\"STRING\",\"value\":\"" + name + "\"}]}}")).get("externalId");
    }

    private static String comment(InMemoryTool tool, String parent) {
        return (String) tool.createComment(Json.parseObject("{\"comment\":{\"commentToItemId\":\"" + parent
                + "\",\"fields\":[{\"name\":\"Body\",\"type\":\"STRING\",\"value\":\"a remark\"}]}}"))
                .get("externalId");
    }
}
