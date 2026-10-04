package com.pmcl.core.news;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsHtmlTest {

    @Test
    void headingsParagraphsAndNestedListsStaySeparate() {
        String html = """
                <p>Brr! Feedback at <a href="https://aka.ms/x">aka.ms/x</a>.</p>
                <h2>Experimental Features</h2>
                <h3>Drop 4 of 2026</h3>
                <h4>Biomes</h4>
                <ul><li>The Ice Cave is new<ul><li>Features terrain</li><li>Icicles hang</li></ul>
                </li></ul>
                <p>Line one<br>Line two</p>
                <p>Rock &amp; Stone&#39;s pick</p>
                """;
        List<NewsHtml.Block> blocks = NewsHtml.parse(html);
        assertEquals(NewsHtml.Kind.PARAGRAPH, blocks.get(0).kind);
        assertEquals("Brr! Feedback at aka.ms/x.", blocks.get(0).text);
        assertEquals(NewsHtml.Kind.HEADING, blocks.get(1).kind);
        assertEquals(2, blocks.get(1).level);
        assertEquals("Experimental Features", blocks.get(1).text);
        assertEquals(3, blocks.get(2).level);
        assertEquals("Drop 4 of 2026", blocks.get(2).text);
        assertEquals(4, blocks.get(3).level);
        assertEquals("Biomes", blocks.get(3).text);
        assertEquals(NewsHtml.Kind.LIST_ITEM, blocks.get(4).kind);
        assertEquals("The Ice Cave is new", blocks.get(4).text);
        assertEquals(0, blocks.get(4).depth);
        assertEquals("Features terrain", blocks.get(5).text);
        assertEquals(1, blocks.get(5).depth);
        assertEquals("Icicles hang", blocks.get(6).text);
        assertEquals("Line one\nLine two", blocks.get(7).text);
        assertEquals("Rock & Stone's pick", blocks.get(8).text);
        assertEquals(9, blocks.size());
    }

    @Test
    void imageBecomesItsOwnBlock() {
        List<NewsHtml.Block> blocks = NewsHtml.parse(
                "<p>Before</p><img src=\"/content/ice.png\" alt=\"Ice cave\"><p>After</p>");
        assertEquals(3, blocks.size());
        assertEquals(NewsHtml.Kind.IMAGE, blocks.get(1).kind);
        assertEquals("https://www.minecraft.net/content/ice.png", blocks.get(1).url);
        assertEquals("Ice cave", blocks.get(1).alt);
        assertTrue(blocks.get(2).text.equals("After"));
    }
}
