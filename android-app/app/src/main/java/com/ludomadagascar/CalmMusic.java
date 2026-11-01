package com.ludomadagascar;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Small procedural home-screen music player with generation-safe lifecycle handling. */
public final class CalmMusic {
  private static final int SAMPLE_RATE=22050;
  private final SharedPreferences prefs;
  private final double[] notes={ 261.63,329.63,392.0,329.63,293.66,349.23,392.0,329.63 };
  private final MusicPlaybackGate sessions=new MusicPlaybackGate();
  private volatile AudioTrack track;
  private Thread thread;

  public CalmMusic(Context c) { prefs=c.getSharedPreferences("music",Context.MODE_PRIVATE); }
  public boolean enabled() { return prefs.getBoolean("enabled",true); }

  public void toggle() {
    if(enabled()) {
      prefs.edit().putBoolean("enabled",false).apply();
      stop();
    } else {
      prefs.edit().putBoolean("enabled",true).apply();
      start();
    }
  }

  public synchronized void start() {
    if(!enabled()) return;
    final long session=sessions.begin();
    if(session<0L) return;
    Thread worker=new Thread(() -> loop(session),"calm-music");
    worker.setDaemon(true);
    thread=worker;
    worker.start();
  }

  /**
   * Invalidate the active generation and stop its local AudioTrack. The worker
   * owns release(), so a stale worker can never release a newer session's track.
   */
  public synchronized void stop() {
    sessions.end();
    AudioTrack active=track;
    if(active!=null) {
      try { active.stop(); } catch(Exception ignored) { }
    }
  }

  private void loop(long session) {
    AudioTrack local=null;
    try {
      final int noteMs=520;
      final int count=SAMPLE_RATE*noteMs/1000;
      final short[] pcm=new short[count];
      AudioAttributes attrs=new AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_GAME)
          .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
          .build();
      AudioFormat fmt=new AudioFormat.Builder()
          .setSampleRate(SAMPLE_RATE)
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .build();
      local=new AudioTrack.Builder()
          .setAudioAttributes(attrs)
          .setAudioFormat(fmt)
          .setBufferSizeInBytes(count*2*2)
          .setTransferMode(AudioTrack.MODE_STREAM)
          .build();

      synchronized(this) {
        if(!sessions.isCurrent(session) || !enabled()) return;
        track=local;
        local.setVolume(0.055f);
        local.play();
      }

      int idx=0;
      while(sessions.isCurrent(session) && enabled()) {
        double frequency=notes[idx++%notes.length];
        for(int i=0;i<count;i++) {
          double t=(double)i/SAMPLE_RATE;
          double envelope=Math.min(1.0,i/500.0)*Math.min(1.0,(count-i)/700.0);
          pcm[i]=(short)(Math.sin(2*Math.PI*frequency*t)*envelope*9000);
        }
        int written=local.write(pcm,0,pcm.length);
        if(written<0) break;
      }
    } catch(Exception ignored) {
      // Music is best-effort and must never interrupt gameplay or navigation.
    } finally {
      if(local!=null) {
        try { local.stop(); } catch(Exception ignored) { }
        try { local.release(); } catch(Exception ignored) { }
      }
      synchronized(this) {
        if(track==local) track=null;
        if(thread==Thread.currentThread()) thread=null;
      }
    }
  }
}
