package com.velagram.tv;

import static org.junit.Assert.*;
import java.util.*;
import org.drinkless.tdlib.TdApi;
import org.json.*;
import org.junit.Test;

public class TelegramBrowserContractTest {
  TdApi.Message message(long id) {
    TdApi.Message m = new TdApi.Message(); m.id = id; m.chatId = 42; m.date = 1700000000; return m;
  }
  TdApi.File file() {
    TdApi.File f = new TdApi.File(); f.id = 123; f.size = 1000000;
    f.remote = new TdApi.RemoteFile(); f.remote.uniqueId = "same-remote-file"; return f;
  }
  TdApi.FormattedText text(String value) { return new TdApi.FormattedText(value, new TdApi.TextEntity[0]); }
  @Test public void videoKeepsFileIdentityCaptionAndDate() {
    TdApi.Message m = message(1); TdApi.Video video = new TdApi.Video();
    video.video = file(); video.fileName = "Show.S00E03.mkv"; video.duration = 1800;
    TdApi.MessageVideo content = new TdApi.MessageVideo(); content.video = video; content.caption = text("Caption"); m.content = content;
    JSONObject result = Telegram.media(m);
    assertEquals("same-remote-file", result.optString("remoteUniqueId"));
    assertEquals(1700000000, result.optLong("date")); assertEquals(1800, result.optLong("mediaDuration"));
    assertEquals("td://123", result.optString("url")); assertEquals("Show.S00E03.mkv", result.optString("filename"));
  }
  @Test public void audioAndDirectLinksArePlayableButWebPagesAreExcluded() {
    TdApi.Message m = message(2); TdApi.Audio audio = new TdApi.Audio(); audio.audio = file(); audio.fileName = "Podcast.mp3"; audio.duration = 123;
    m.content = new TdApi.MessageAudio(audio, text("Podcast"));
    assertEquals("audio", Telegram.media(m).optString("mediaKind"));
    TdApi.MessageText content = new TdApi.MessageText(); content.text = text("Watch https://example.com/video.m3u8?token=abc"); m.content = content;
    assertEquals("https://example.com/video.m3u8?token=abc", Telegram.media(m).optString("url"));
    content.text = text("https://example.com/ordinary-page"); assertNull(Telegram.media(m));
  }
  @Test public void hiddenTextEntityUrlsAreRetained() {
    TdApi.Message m = message(3); TdApi.MessageText content = new TdApi.MessageText();
    content.text = new TdApi.FormattedText("Play", new TdApi.TextEntity[] {new TdApi.TextEntity(0, 4, new TdApi.TextEntityTypeTextUrl("https://example.com/video.mp4"))});
    m.content = content; assertEquals("https://example.com/video.mp4", Telegram.media(m).optString("url"));
  }
  @Test public void eachFilterKeepsItsOwnCursorAndTopic() {
    Map<String, Long> cursors = new LinkedHashMap<>(); cursors.put("video", 900L); cursors.put("document", 200L); cursors.put("audio", -1L); cursors.put("url", 50L);
    List<Long> requested = new ArrayList<>(); TelegramMediaPage[] result = new TelegramMediaPage[1];
    TelegramMediaPage.load((request, done) -> {
      TdApi.SearchChatMessages q = (TdApi.SearchChatMessages) request;
      requested.add(q.fromMessageId); assertEquals(42, q.chatId); assertEquals("show", q.query);
      assertEquals(7, ((TdApi.MessageTopicForum) q.topicId).forumTopicId);
      TdApi.Message m = message(q.fromMessageId);
      TdApi.MessageText text = new TdApi.MessageText(); text.text = text("https://example.com/video.mp4"); m.content = text;
      done.accept(new TdApi.FoundChatMessages(10, new TdApi.Message[] {m}, q.fromMessageId - 1));
    }, 42, "show", 7, cursors, page -> result[0] = page);
    assertEquals(Arrays.asList(900L, 200L, 50L), requested);
    assertEquals(Long.valueOf(899), result[0].next.get("video")); assertEquals(Long.valueOf(199), result[0].next.get("document"));
    assertEquals(Long.valueOf(-1), result[0].next.get("audio")); assertTrue(result[0].hasMore());
  }
  @Test public void emptyUnplayablePageStillKeepsPagingAndFailuresCanRetry() {
    TelegramMediaPage[] result = new TelegramMediaPage[1];
    TelegramMediaPage.load((request, done) -> {
      TdApi.SearchChatMessages q = (TdApi.SearchChatMessages) request;
      if (q.filter instanceof TdApi.SearchMessagesFilterAudio) { done.accept(new TdApi.Error(500, "retry")); return; }
      TdApi.Message m = message(1); TdApi.MessageText text = new TdApi.MessageText(); text.text = text("ordinary message"); m.content = text;
      done.accept(new TdApi.FoundChatMessages(10, new TdApi.Message[] {m}, 123));
    }, 42, "", 0, Collections.emptyMap(), page -> result[0] = page);
    assertTrue(result[0].items.isEmpty()); assertTrue(result[0].hasMore());
    assertEquals(Long.valueOf(0), result[0].next.get("audio"));
  }
}
