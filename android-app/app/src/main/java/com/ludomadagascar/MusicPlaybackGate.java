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

  synchronized boolean isCurrent(long session) {
    return playing && generation==session;
  }
}
