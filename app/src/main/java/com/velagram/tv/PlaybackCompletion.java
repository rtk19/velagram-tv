package com.velagram.tv;

/** Matches iOS PlaybackCompletionPolicy; input is milliseconds. */
public final class PlaybackCompletion {
  public static boolean offerNext(long position, long duration) {
    return duration > 0 && position > 0 && duration - position <= 60000;
  }
  public static boolean finishedEpisode(boolean episode, long position, long duration, long creditsStart) {
    if (!episode || position <= 0 || duration <= 0) return false;
    if (creditsStart > 0 && creditsStart < duration) return position >= creditsStart;
    return Math.max(duration - position, 0) <= 60000;
  }
}
