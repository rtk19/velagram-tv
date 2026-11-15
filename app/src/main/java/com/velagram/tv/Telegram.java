package com.velagram.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.drinkless.tdlib.*;
import org.json.*;

public final class Telegram {
  private static Telegram instance;

  public static synchronized Telegram get(Context c) {
    if (instance == null) instance = new Telegram(c.getApplicationContext());
    return instance;
  }

  final Client client;
  final Store store;
  final Context context;
  final Handler main = new Handler(Looper.getMainLooper());
  volatile TdApi.AuthorizationState state;
  public Consumer<String> listener;
  public String status = "Connecting to Telegram…";
  public String qr = "";
  public TdApi.ChatFolderInfo[] folders = new TdApi.ChatFolderInfo[0];
  final Map<Long, TdApi.Chat> chats = new ConcurrentHashMap<>();

  private Telegram(Context c) {
    context = c;
    store = new Store(c);
    client = Client.create(this::update, null, null);
  }

  void announce(String s) {
    status = s;
    if (listener != null)
      main.post(
          () -> {
            if (listener != null) listener.accept(s);
          });
  }

  void update(TdApi.Object o) {
    if (o instanceof TdApi.UpdateChatFolders) {
      folders = ((TdApi.UpdateChatFolders) o).chatFolders;
    } else if (o instanceof TdApi.UpdateAuthorizationState)
      auth(((TdApi.UpdateAuthorizationState) o).authorizationState);
    else if (o instanceof TdApi.AuthorizationState) auth((TdApi.AuthorizationState) o);
    else if (o instanceof TdApi.UpdateNewChat) {
      TdApi.Chat c = ((TdApi.UpdateNewChat) o).chat;
      chats.put(c.id, c);
    } else if (o instanceof TdApi.UpdateChatTitle) {
      TdApi.UpdateChatTitle t = (TdApi.UpdateChatTitle) o;
      TdApi.Chat c = chats.get(t.chatId);
      if (c != null) c.title = t.title;
    }
  }

  void auth(TdApi.AuthorizationState s) {
    state = s;
    String n = s.getClass().getSimpleName();
    if (s instanceof TdApi.AuthorizationStateWaitTdlibParameters) {
      try {
        TdApi.SetTdlibParameters p = new TdApi.SetTdlibParameters();
        p.databaseDirectory = new java.io.File(context.getFilesDir(), "telegram/db").toString();
        p.filesDirectory = new java.io.File(context.getFilesDir(), "telegram/files").toString();
        String key = store.get("secret.telegram.database", "");
        if (key.isEmpty()) {
          byte[] b = new byte[32];
          new java.security.SecureRandom().nextBytes(b);
          key = android.util.Base64.encodeToString(b, 2);
          store.put("secret.telegram.database", key);
        }
        p.databaseEncryptionKey = android.util.Base64.decode(key, 2);
        p.apiId = Integer.parseInt(BuildConfig.TELEGRAM_API_ID);
        p.apiHash = BuildConfig.TELEGRAM_API_HASH;
        p.useFileDatabase = true;
        p.useChatInfoDatabase = true;
        p.useMessageDatabase = true;
        p.systemLanguageCode = "en";
        p.deviceModel = "Velagram Android TV";
        p.systemVersion = android.os.Build.VERSION.RELEASE;
        p.applicationVersion = BuildConfig.VERSION_NAME;
        send(p, x -> {});
      } catch (Exception e) {
        announce("Telegram configuration unavailable");
      }
    } else if (s instanceof TdApi.AuthorizationStateClosed) {
      synchronized (Telegram.class) {
        if (instance == this) instance = null;
      }
      announce("Signed out");
    } else if (s instanceof TdApi.AuthorizationStateReady) {
      announce("Connected");
      TemporaryMedia.trim(this, false, message -> {});
      client.send(new TdApi.LoadChats(new TdApi.ChatListMain(), 100), x -> {});
    } else if (s instanceof TdApi.AuthorizationStateWaitOtherDeviceConfirmation) {
      qr = ((TdApi.AuthorizationStateWaitOtherDeviceConfirmation) s).link;
      announce("Confirm this TV in Telegram on your phone");
    } else {
      String label = n.replace("AuthorizationStateWait", "").replace("AuthorizationState", "");
      switch (label) {
        case "PhoneNumber":
          label = "Phone number, including country code";
          break;
        case "Code":
          label = "Enter the login code from Telegram";
          break;
        case "Password":
          label = "Enter your two-step verification password";
          break;
        case "EmailAddress":
          label = "Enter your email address";
          break;
        case "EmailCode":
          label = "Enter the code sent to your email";
          break;
      }
      announce(label);
    }
  }

  public boolean ready() {
    return state instanceof TdApi.AuthorizationStateReady;
  }

  public void send(TdApi.Function<?> f, Consumer<TdApi.Object> done) {
    client.send(
        f,
        o -> {
          if (o instanceof TdApi.Error) {
            String m = ((TdApi.Error) o).message;
            announce(m);
          }
          main.post(() -> done.accept(o));
        });
  }

  public void login(String value) {
    String n = state == null ? "" : state.getClass().getSimpleName();
    TdApi.Function<?> f = null;
    if (n.endsWith("PhoneNumber") || n.endsWith("OtherDeviceConfirmation")) {
      TdApi.SetAuthenticationPhoneNumber p = new TdApi.SetAuthenticationPhoneNumber();
      p.phoneNumber = value;
      f = p;
    } else if (n.endsWith("Code") && !n.endsWith("EmailCode")) {
      TdApi.CheckAuthenticationCode p = new TdApi.CheckAuthenticationCode();
      p.code = value;
      f = p;
    } else if (n.endsWith("Password")) {
      TdApi.CheckAuthenticationPassword p = new TdApi.CheckAuthenticationPassword();
      p.password = value;
      f = p;
    } else if (n.endsWith("EmailAddress")) {
      TdApi.SetAuthenticationEmailAddress p = new TdApi.SetAuthenticationEmailAddress();
      p.emailAddress = value;
      f = p;
    } else if (n.endsWith("EmailCode")) {
      TdApi.CheckAuthenticationEmailCode p = new TdApi.CheckAuthenticationEmailCode();
      p.code = new TdApi.EmailAddressAuthenticationCode(value);
      f = p;
    }
    if (f != null) send(f, o -> {});
  }

  public void qr() {
    send(new TdApi.RequestQrCodeAuthentication(new long[0]), o -> {});
  }

  public void chats(Consumer<List<TdApi.Chat>> done) {
    chats(new TdApi.ChatListMain(), done);
  }

  public void chats(TdApi.ChatList list, Consumer<List<TdApi.Chat>> done) {
    chats(list, 100, done);
  }

  public void chats(TdApi.ChatList list, int limit, Consumer<List<TdApi.Chat>> done) {
    client.send(new TdApi.LoadChats(list, 100), ignored -> chatList(list, limit, done));
  }

  void chatList(TdApi.ChatList list, int limit, Consumer<List<TdApi.Chat>> done) {
    client.send(
        new TdApi.GetChats(list, limit),
        o -> {
          if (o instanceof TdApi.Chats) {
            long[] ids = ((TdApi.Chats) o).chatIds;
            Map<Long, TdApi.Chat> result = new ConcurrentHashMap<>();
            java.util.concurrent.atomic.AtomicInteger left =
                new java.util.concurrent.atomic.AtomicInteger(ids.length);
            if (ids.length == 0) main.post(() -> done.accept(new ArrayList<>()));
            for (long id : ids)
              client.send(
                  new TdApi.GetChat(id),
                  r -> {
                    if (r instanceof TdApi.Chat) result.put(id, (TdApi.Chat) r);
                    if (left.decrementAndGet() == 0) main.post(() -> {
                      List<TdApi.Chat> ordered = new ArrayList<>();
                      for (long chatId : ids) if (result.containsKey(chatId)) ordered.add(result.get(chatId));
                      done.accept(ordered);
                    });
                  });
          } else main.post(() -> done.accept(new ArrayList<>()));
        });
  }

  public void messages(long chat, String query, long from, Consumer<List<JSONObject>> done) {
    messages(chat, query, from, 0, done);
  }

  public void messages(
      long chat, String query, long from, int topic, Consumer<List<JSONObject>> done) {
    TdApi.SearchChatMessages f = new TdApi.SearchChatMessages();
    f.chatId = chat;
    if (topic > 0) f.topicId = new TdApi.MessageTopicForum(topic);
    f.query = query;
    f.fromMessageId = from;
    f.limit = 100;
    f.filter = new TdApi.SearchMessagesFilterVideo();
    send(
        f,
        o -> {
          List<JSONObject> r = new ArrayList<>();
          if (o instanceof TdApi.FoundChatMessages)
            for (TdApi.Message m : ((TdApi.FoundChatMessages) o).messages) {
              JSONObject j = media(m);
              if (j != null) r.add(j);
            }
          TdApi.SearchChatMessages docs = new TdApi.SearchChatMessages();
          docs.chatId = chat;
          if (topic > 0) docs.topicId = new TdApi.MessageTopicForum(topic);
          docs.query = query;
          docs.fromMessageId = from;
          docs.limit = 100;
          docs.filter = new TdApi.SearchMessagesFilterDocument();
          send(
              docs,
              d -> {
                if (d instanceof TdApi.FoundChatMessages)
                  for (TdApi.Message m : ((TdApi.FoundChatMessages) d).messages) {
                    JSONObject j = media(m);
                    if (j != null) r.add(j);
                  }
                r.sort((a, b) -> Long.compare(b.optLong("message"), a.optLong("message")));
                done.accept(r);
              });
        });
  }

  public void catalog(
      JSONObject title,
      int season,
      int episode,
      java.util.function.BooleanSupplier active,
      Consumer<List<JSONObject>> batch,
      Consumer<Integer> done) {
    LinkedHashSet<String> aliases = new LinkedHashSet<>();
    boolean hebrew = store.get("searchLanguage", "English first").equals("Hebrew first");
    aliases.add(title.optString(hebrew ? "heTitle" : "enTitle", Models.title(title)));
    aliases.add(title.optString(hebrew ? "enTitle" : "heTitle", ""));
    aliases.add(Models.title(title));
    aliases.add(title.optString("original_title", title.optString("original_name", "")));
    aliases.remove("");
    List<String> queries = new ArrayList<>(aliases);
    global(queries, 0, 0, "", title, season, episode, active, batch, done, new HashSet<>());
  }

  private void global(
      List<String> queries,
      int queryIndex,
      int filterIndex,
      String offset,
      JSONObject title,
      int season,
      int episode,
      java.util.function.BooleanSupplier active,
      Consumer<List<JSONObject>> batch,
      Consumer<Integer> done,
      Set<String> seen) {
    if (!active.getAsBoolean()) return;
    if (queryIndex >= queries.size()) {
      done.accept(seen.size());
      return;
    }
    TdApi.SearchMessages request = new TdApi.SearchMessages();
    request.query = queries.get(queryIndex);
    request.offset = offset;
    request.limit = 100;
    request.filter =
        filterIndex == 0
            ? new TdApi.SearchMessagesFilterVideo()
            : new TdApi.SearchMessagesFilterDocument();
    client.send(
        request,
        result ->
            main.post(
                () -> {
                  if (!active.getAsBoolean()) return;
                  List<JSONObject> items = new ArrayList<>();
                  String next = "";
                  if (result instanceof TdApi.FoundMessages) {
                    TdApi.FoundMessages found = (TdApi.FoundMessages) result;
                    for (TdApi.Message message : found.messages) {
                      JSONObject file = media(message);
                      if (file != null
                          && ReleaseGrouping.matches(file, title, season, episode)
                          && seen.add(file.optString("file", file.optString("id"))))
                        items.add(file);
                    }
                    next = found.nextOffset;
                  }
                  if (!items.isEmpty()) batch.accept(items);
                  if (!next.isEmpty() && !next.equals(offset))
                    global(
                        queries,
                        queryIndex,
                        filterIndex,
                        next,
                        title,
                        season,
                        episode,
                        active,
                        batch,
                        done,
                        seen);
                  else if (filterIndex == 0)
                    global(
                        queries,
                        queryIndex,
                        1,
                        "",
                        title,
                        season,
                        episode,
                        active,
                        batch,
                        done,
                        seen);
                  else
                    global(
                        queries,
                        queryIndex + 1,
                        0,
                        "",
                        title,
                        season,
                        episode,
                        active,
                        batch,
                        done,
                        seen);
                }));
  }

  public void topics(long chat, Consumer<List<JSONObject>> done) {
    topics(chat, 0, 0, 0, new ArrayList<>(), new HashSet<>(), done);
  }

  void topics(long chat, int date, long message, int topic, List<JSONObject> collected, Set<Integer> seen, Consumer<List<JSONObject>> done) {
    send(
        new TdApi.GetForumTopics(chat, "", date, message, topic, 100),
        o -> {
          if (o instanceof TdApi.ForumTopics) {
            TdApi.ForumTopics page = (TdApi.ForumTopics) o;
            for (TdApi.ForumTopic t : page.topics)
              if (seen.add(t.info.forumTopicId)) collected.add(Models.json("id", t.info.forumTopicId, "name", t.info.name));
            if (page.topics.length > 0 && page.nextOffsetForumTopicId != 0 && page.nextOffsetForumTopicId != topic) {
              topics(chat, page.nextOffsetDate, page.nextOffsetMessageId, page.nextOffsetForumTopicId, collected, seen, done);
              return;
            }
          }
          done.accept(collected);
        });
  }

  static JSONObject media(TdApi.Message m) {
    TdApi.File f = null;
    String title = "", caption = "", kind = "video";
    long duration = 0;
    if (m.content instanceof TdApi.MessageVideo) {
      TdApi.MessageVideo v = (TdApi.MessageVideo) m.content;
      f = v.video.video;
      title = v.video.fileName;
      caption = v.caption.text;
      duration = v.video.duration;
    } else if (m.content instanceof TdApi.MessageDocument) {
      TdApi.MessageDocument d = (TdApi.MessageDocument) m.content;
      if (!(d.document.mimeType.startsWith("video/")
          || d.document.fileName.matches("(?i).*\\.(mkv|mp4|avi|webm|mov|ts)$"))) return null;
      f = d.document.document;
      title = d.document.fileName;
      caption = d.caption.text;
    } else if (m.content instanceof TdApi.MessageAudio) {
      TdApi.MessageAudio a = (TdApi.MessageAudio) m.content;
      f = a.audio.audio; title = a.audio.fileName; caption = a.caption.text; kind = "audio"; duration = a.audio.duration;
    } else if (m.content instanceof TdApi.MessageText) {
      TdApi.FormattedText text = ((TdApi.MessageText) m.content).text;
      List<String> urls = new ArrayList<>();
      if (text.entities != null) for (TdApi.TextEntity entity : text.entities)
        if (entity.type instanceof TdApi.TextEntityTypeTextUrl) urls.add(((TdApi.TextEntityTypeTextUrl) entity.type).url);
      java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("https?://[^\\s<>\\\"]+").matcher(text.text);
      while (matcher.find()) urls.add(matcher.group());
      for (String url : urls) try {
        java.net.URI uri = java.net.URI.create(url);
        if (uri.getPath() != null && uri.getPath().toLowerCase(Locale.ROOT).matches(".*\\.(m3u8|mp4|mkv|mp3|m4v|mov|m3u)$"))
          return Models.json("id", "telegram:" + m.chatId + ":" + m.id, "message", m.id, "chat", m.chatId,
              "name", uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1), "description", text.text, "date", m.date, "url", url, "mediaKind", "link");
      } catch (Exception ignored) { }
    }
    if (f == null) return null;
    return Models.json(
        "id",
        "telegram:" + m.chatId + ":" + m.id,
        "name",
        title.isEmpty() ? caption : title,
        "filename",
        title,
        "mediaKind", kind,
        "date", m.date,
        "mediaDuration", duration,
        "remoteUniqueId", f.remote == null ? "" : f.remote.uniqueId,
        "description",
        caption,
        "message",
        m.id,
        "chat",
        m.chatId,
        "file",
        f.id,
        "size",
        f.size,
        "url",
        "td://" + f.id);
  }

  public TdApi.File file(int id) throws Exception {
    CompletableFuture<TdApi.File> p = new CompletableFuture<>();
    client.send(
        new TdApi.GetFile(id),
        o -> {
          if (o instanceof TdApi.File) p.complete((TdApi.File) o);
          else p.completeExceptionally(new java.io.IOException("Telegram file unavailable"));
        });
    return p.get(20, TimeUnit.SECONDS);
  }

  public void request(int id, long offset) {
    client.send(new TdApi.DownloadFile(id, 32, offset, 0, false), o -> {});
  }

  public void download(JSONObject item, Consumer<String> done) {
    int id = item.optInt("file");
    store.put("temporary.keep." + id, "true");
    TemporaryMedia.acquire();
    client.send(
        new TdApi.DownloadFile(id, 16, 0, 0, true),
        o -> {
          TemporaryMedia.release();
          if (o instanceof TdApi.File && ((TdApi.File) o).local.isDownloadingCompleted) {
            try {
              JSONObject saved = new JSONObject(item.toString());
              saved.put(
                  "url",
                  android.net.Uri.fromFile(new java.io.File(((TdApi.File) o).local.path))
                      .toString());
              store.upsert("downloads", saved);
              main.post(() -> done.accept("Saved for offline playback"));
            } catch (Exception e) {
              main.post(() -> done.accept("Unable to save download"));
            }
          } else
            main.post(
                () ->
                    done.accept(
                        o instanceof TdApi.Error
                            ? ((TdApi.Error) o).message
                            : "Download did not finish"));
        });
  }
}
