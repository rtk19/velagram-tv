package com.velagram.tv;

import android.content.Intent;
import androidx.media3.session.*;

/** Foreground notification and TV/media-key access while the player screen is backgrounded. */
@androidx.media3.common.util.UnstableApi
public class PlaybackService extends MediaSessionService {
  static MediaSession activeSession;

  @Override
  public void onCreate() {
    super.onCreate();
    if (activeSession != null) addSession(activeSession);
  }

  @Override
  public MediaSession onGetSession(MediaSession.ControllerInfo controller) {
    return activeSession;
  }

  @Override
  public void onTaskRemoved(Intent rootIntent) {
    if (activeSession != null) activeSession.getPlayer().pause();
    stopSelf();
  }

  @Override
  public void onDestroy() {
    if (activeSession != null) removeSession(activeSession);
    super.onDestroy();
  }
}
