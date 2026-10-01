package com.pmcl.music.source;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionalMusicSourcesTest {

    @Test
    void cachesResolvedStreamsAndCanRefreshThem() throws Exception {
        AudioSourceResolver resolver = new AudioSourceResolver();
        String url = "https://93.184.216.34/audio.mp3";

        AudioStreamInfo first = resolver.resolve(url);
        assertSame(first, resolver.resolve(url));

        AudioStreamInfo fresh = resolver.resolveFresh(url);
        assertNotSame(first, fresh);
        assertSame(fresh, resolver.resolve(url));

        resolver.invalidate(url);
        assertNotSame(fresh, resolver.resolve(url));
    }

    @Test
    void optionalSourcesStayOffUntilEnabled() {
        AudioSourceResolver resolver = new AudioSourceResolver();
        IOException kuaishou = assertThrows(IOException.class,
                () -> resolver.resolve("https://www.kuaishou.com/short-video/3x5jvmsmmiahx3m"));
        assertTrue(kuaishou.getMessage().contains("未开启"));

        IOException douyin = assertThrows(IOException.class,
                () -> resolver.resolve("https://www.douyin.com/video/7123456789012345678"));
        assertTrue(douyin.getMessage().contains("未开启"));

        IOException youtube = assertThrows(IOException.class,
                () -> resolver.resolve("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertTrue(youtube.getMessage().contains("未开启"));
    }

    @Test
    void enabledYoutubeWithoutVideoIdDoesNotCallNetwork() {
        AudioSourceResolver resolver = new AudioSourceResolver();
        resolver.setOptionalSources(false, false, true);
        IOException error = assertThrows(IOException.class,
                () -> resolver.resolve("https://www.youtube.com/feed/trending"));
        assertTrue(error.getMessage().contains("无法识别"));
    }

    @Test
    void hostMatchRejectsLookalikeDomains() {
        assertTrue(new KuaishouAudioSource().matches("https://www.kuaishou.com/short-video/3xabc"));
        assertTrue(new KuaishouAudioSource().matches("https://v.kuaishou.com/abc"));
        assertFalse(new KuaishouAudioSource().matches("https://notkuaishou.com/short-video/3xabc"));
        assertTrue(new DouyinAudioSource().matches("https://v.douyin.com/iABCDEF/"));
        assertFalse(new DouyinAudioSource().matches("https://example.com/video/7123456789012345678"));
        assertTrue(new YoutubeAudioSource().matches("https://music.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertTrue(new YoutubeAudioSource().matches("https://youtu.be/dQw4w9WgXcQ"));
        assertFalse(new YoutubeAudioSource().matches("https://example.com/watch?v=dQw4w9WgXcQ"));
    }

    @Test
    void extractsIds() {
        assertEquals("3x5jvmsmmiahx3m",
                KuaishouAudioSource.photoId("https://www.kuaishou.com/short-video/3x5jvmsmmiahx3m?cc=share"));
        assertEquals("3x5jvmsmmiahx3m",
                KuaishouAudioSource.photoId("https://www.kuaishou.com/f/token?shareObjectId=3x5jvmsmmiahx3m"));
        assertEquals("7123456789012345678",
                DouyinAudioSource.videoId("https://www.douyin.com/video/7123456789012345678"));
        assertEquals("7123456789012345678",
                DouyinAudioSource.videoId("https://www.douyin.com/discover?modal_id=7123456789012345678"));
        assertNull(DouyinAudioSource.videoId("https://v.douyin.com/iABCDEF/"));
        assertEquals("dQw4w9WgXcQ", YoutubeAudioSource.videoId("https://youtu.be/dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ", YoutubeAudioSource.videoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ",
                YoutubeAudioSource.videoId("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RD"));
    }

    @Test
    void parsesKuaishouPhotoAndDouyinItemAndYoutubeAudio() {
        String apollo = """
                {"defaultClient":{
                  "VisionVideoDetailPhoto:1":{
                    "__typename":"VisionVideoDetailPhoto",
                    "caption":"一首歌",
                    "photoUrl":"https://cdn.example/a.mp4",
                    "duration":15000,
                    "coverUrl":"https://cdn.example/c.jpg"
                  },
                  "VisionVideoDetailAuthor:1":{
                    "__typename":"VisionVideoDetailAuthor",
                    "name":"作者"
                  }
                }}
                """;
        KuaishouAudioSource.Parsed photo = KuaishouAudioSource.parseTree(JsonParser.parseString(apollo));
        assertEquals("https://cdn.example/a.mp4", photo.mediaUrl());
        assertEquals("一首歌", photo.title);
        assertEquals("作者", photo.author);
        assertEquals(15000L, photo.durationMs);

        String router = """
                {"loaderData":{"video_(id)/page":{"videoInfoRes":{"item_list":[{
                  "desc":"标题",
                  "author":{"nickname":"作者"},
                  "video":{
                    "duration":8000,
                    "play_addr":{"uri":"v0200abc"},
                    "cover":{"url_list":["https://cdn.example/cover.jpg"]}
                  }
                }]}}}}
                """;
        DouyinAudioSource.Item item = DouyinAudioSource.findItem(JsonParser.parseString(router));
        assertEquals("v0200abc", item.playRef);
        assertEquals("标题", item.title);
        assertEquals("作者", item.author);
        assertEquals(8000L, item.durationMs);
        assertEquals(
                "https://www.iesdouyin.com/aweme/v1/play/?video_id=v0200abc&ratio=720p&line=0",
                DouyinAudioSource.mediaUrl(item));

        String player = """
                {"streamingData":{"adaptiveFormats":[
                  {"itag":139,"mimeType":"audio/mp4","bitrate":48000,"url":"https://cdn.example/low"},
                  {"itag":251,"mimeType":"audio/webm","bitrate":160000,"url":"https://cdn.example/opus"},
                  {"itag":140,"mimeType":"audio/mp4; codecs=\\"mp4a.40.2\\"","bitrate":128000,"url":"https://cdn.example/aac"}
                ]}}
                """;
        assertEquals("https://cdn.example/aac",
                YoutubeAudioSource.pickAudioUrl(JsonParser.parseString(player).getAsJsonObject()));
        String video = """
                {"streamingData":{"adaptiveFormats":[
                  {"mimeType":"video/webm","height":360,"bitrate":300000,"url":"https://cdn.example/webm"},
                  {"mimeType":"video/mp4","height":720,"bitrate":900000,"url":"https://cdn.example/720"},
                  {"mimeType":"video/mp4","height":360,"bitrate":400000,"url":"https://cdn.example/360"}
                ]}}
                """;
        assertEquals("https://cdn.example/360",
                YoutubeAudioSource.pickVideoUrl(JsonParser.parseString(video).getAsJsonObject()));
    }

    @Test
    void picksSmallAvcBilibiliPreview() {
        String video = """
                [
                  {"height":1080,"bandwidth":2500000,"codecs":"avc1","base_url":"https://cdn.example/1080"},
                  {"height":360,"bandwidth":400000,"codecs":"avc1","base_url":"https://cdn.example/360"},
                  {"height":360,"bandwidth":300000,"codecs":"hev1","base_url":"https://cdn.example/hevc"}
                ]
                """;
        assertEquals("https://cdn.example/360",
                BilibiliAudioSource.pickPreviewVideoUrl(JsonParser.parseString(video).getAsJsonArray()));
    }
}
