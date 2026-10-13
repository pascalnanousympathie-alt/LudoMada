package com.ludomadagascar;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.AudioFormat;
import android.media.AudioTrack;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lobby background music controller.
 *
 * A licensed/local copy of the requested track should be bundled as
 * assets/vale_vale.mp3. It is played entirely from local app storage, so
 * playback never depends on Internet connectivity.
 *
 * During development/builds where the asset is not present yet, the class
 * falls back to the existing lightweight procedural melody rather than
 * failing the app launch.
 */
public final class CalmMusic {
  private static final int SAMPLE_RATE=22050;
  private final Context context;
  private final android.content.SharedPreferences prefs;
  private final double[] notes={ 261.63,329.63,392.0,329.63,293.66,349.23,392.0,329.63 };
  private final AtomicBoolean playing=new AtomicBoolean(false);
  private Thread thread;
  private MediaPlayer mediaPlayer;
  private AudioTrack fallbackTrack;

  public CalmMusic(Context c) {
    context=c.getApplicationContext();
    prefs=c.getSharedPreferences("music",Context.MODE_PRIVATE);
  }

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
    if(!enabled() || playing.get()) return;
    playing.set(true);
    thread=new Thread(() -> loop(),"lobby-music");
    thread.setDaemon(true);
    thread.start();
  }

  public synchronized void stop() {
    playing.set(false);
    releasePlayers();
  }

  private void loop() {
    try {
      if(startBundledTrack()) {
        while(playing.get() && enabled()) {
          try { Thread.sleep(250L); } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
        }
      } else {
        playFallbackMelody();
      }
    } catch(Exception ignored) {
      if(playing.get() && enabled()) playFallbackMelody();
    } finally {
      releasePlayers();
      playing.set(false);
    }
  }

  private boolean startBundledTrack() {
    AssetFileDescriptor afd=null;
    MediaPlayer mp=null;
    try {
      afd=context.getAssets().openFd("vale_vale.mp3");
      mp=new MediaPlayer();
      mp.setAudioAttributes(new AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_GAME)
          .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
          .build());
      mp.setDataSource(afd.getFileDescriptor(),afd.getStartOffset(),afd.getLength());
      afd.close();
      afd=null;
      mp.setLooping(true);
      mp.setVolume(.34f,.34f);
      mp.prepare();
      if(!playing.get() || !enabled()) {
        mp.release();
        return true;
      }
      synchronized(this) { mediaPlayer=mp; }
      mp.start();
      return true;
    } catch(Exception e) {
      if(afd!=null) try { afd.close(); } catch(Exception ignored) {}
      if(mp!=null) try { mp.release(); } catch(Exception ignored) {}
      return false;
    }
  }

  private void playFallbackMelody() {
    final int noteMs=520;
    final int count=SAMPLE_RATE*noteMs/1000;
    final short[] pcm=new short[count];
    try {
      AudioAttributes attrs=new AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_GAME)
          .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
          .build();
      AudioFormat fmt=new AudioFormat.Builder()
          .setSampleRate(SAMPLE_RATE)
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .build();
      AudioTrack track=new AudioTrack.Builder()
          .setAudioAttributes(attrs)
          .setAudioFormat(fmt)
          .setBufferSizeInBytes(count*2*2)
          .setTransferMode(AudioTrack.MODE_STREAM)
          .build();
      synchronized(this) { fallbackTrack=track; }
      track.setVolume(.055f);
      track.play();
      int idx=0;
      while(playing.get() && enabled()) {
        double f=notes[idx++%notes.length];
        for(int i=0;i<count;i++) {
          double tt=(double)i/SAMPLE_RATE;
          double env=Math.min(1.0,i/500.0)*Math.min(1.0,(count-i)/700.0);
          pcm[i]=(short)(Math.sin(2*Math.PI*f*tt)*env*9000);
        }
        track.write(pcm,0,pcm.length);
      }
    } catch(Exception ignored) {
      // Music is non-critical; never break app startup or navigation.
    }
  }

  private synchronized void releasePlayers() {
    if(mediaPlayer!=null) {
      try { if(mediaPlayer.isPlaying()) mediaPlayer.stop(); } catch(Exception ignored) {}
      try { mediaPlayer.release(); } catch(Exception ignored) {}
      mediaPlayer=null;
    }
    if(fallbackTrack!=null) {
      try { fallbackTrack.stop(); } catch(Exception ignored) {}
      try { fallbackTrack.release(); } catch(Exception ignored) {}
      fallbackTrack=null;
    }
  }
}
