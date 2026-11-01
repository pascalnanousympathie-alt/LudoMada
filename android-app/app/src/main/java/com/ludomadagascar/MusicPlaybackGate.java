package com.ludomadagascar;

/** Serializes music playback generations across fast screen/lifecycle transitions. */
final class MusicPlaybackGate {
  private long generation=0L;
  private boolean playing=false;

  synchronized long begin() {
    if(playing) return -1L;
    playing=true;
    return ++generation;
  }

  synchronized long end() {
    playing=false;
    return ++generation;
  }

  /** End only the caller's generation; a stale worker cannot stop its successor. */
  synchronized boolean endIfCurrent(long session) {
    if (!playing || generation!=session) return false;
    playing=false;
    ++generation;
    return true;
  }

  synchronized boolean isCurrent(long session) {
    return playing && generation==session;
  }
}
