package com.velagram.tv;

import java.util.*;
import java.util.function.*;
import org.drinkless.tdlib.TdApi;
import org.json.*;

/** Independent TDLib cursors prevent a busy video filter from skipping older documents/audio. */
public final class TelegramMediaPage {
  public interface Transport { void send(TdApi.Function<?> request, Consumer<TdApi.Object> done); }
  public final List<JSONObject> items = new ArrayList<>();
  public final Map<String, Long> next = new LinkedHashMap<>();
  static final String[] FILTERS = {"video", "document", "audio", "url"};

  public static void load(Transport transport, long chat, String query, int topic, Map<String, Long> cursors, Consumer<TelegramMediaPage> done) {
    TelegramMediaPage page = new TelegramMediaPage();
    loadFilter(transport, chat, query, topic, cursors, page, 0, done);
  }

  static void loadFilter(Transport transport, long chat, String query, int topic, Map<String, Long> cursors,
      TelegramMediaPage page, int index, Consumer<TelegramMediaPage> done) {
    if (index == FILTERS.length) {
      Set<String> seen = new HashSet<>(); page.items.removeIf(o -> !seen.add(o.optString("id")));
      page.items.sort((a, b) -> Long.compare(b.optLong("message"), a.optLong("message")));
      done.accept(page); return;
    }
    String filter = FILTERS[index];
    if (cursors.containsKey(filter) && cursors.get(filter) < 0) {
      page.next.put(filter, -1L); loadFilter(transport, chat, query, topic, cursors, page, index + 1, done); return;
    }
    TdApi.SearchChatMessages request = new TdApi.SearchChatMessages();
    request.chatId = chat; request.query = query; request.limit = 100;
    request.fromMessageId = cursors.getOrDefault(filter, 0L);
    if (topic > 0) request.topicId = new TdApi.MessageTopicForum(topic);
    request.filter = index == 0 ? new TdApi.SearchMessagesFilterVideo() : index == 1 ? new TdApi.SearchMessagesFilterDocument()
        : index == 2 ? new TdApi.SearchMessagesFilterAudio() : new TdApi.SearchMessagesFilterUrl();
    transport.send(request, response -> {
      if (response instanceof TdApi.FoundChatMessages) {
        TdApi.FoundChatMessages messages = (TdApi.FoundChatMessages) response;
        for (TdApi.Message m : messages.messages) { JSONObject item = Telegram.media(m); if (item != null) page.items.add(item); }
        long next = messages.nextFromMessageId;
        page.next.put(filter, next == 0 || next == request.fromMessageId || messages.messages.length == 0 ? -1L : next);
      } else page.next.put(filter, request.fromMessageId); // A failed filter remains retryable.
      loadFilter(transport, chat, query, topic, cursors, page, index + 1, done);
    });
  }
  public boolean hasMore() { return next.values().stream().anyMatch(v -> v >= 0); }
}
