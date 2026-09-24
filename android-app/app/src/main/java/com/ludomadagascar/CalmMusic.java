package com.ludomadagascar;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CalmMusic {
  private static final int SAMPLE_RATE=22050;
  private final SharedPreferences prefs;
  private final double[] notes={ 261.63,329.63,392.0,329.63,293.66,349.23,392.0,329.63 };
  private final AtomicBoolean playing=new AtomicBoolean(false);
  private Thread thread;
  private AudioTrack track;
  public CalmMusic(Context c) { prefs=c.getSharedPreferences("music",Context.MODE_PRIVATE); }
  public boolean enabled() { return prefs.getBoolean("enabled",true); }
  public void toggle() { if(enabled()) { prefs.edit().putBoolean("enabled",false).apply(); stop(); } else { prefs.edit().putBoolean("enabled",true).apply(); start(); } }
  public synchronized void start() { if(!enabled()||playing.get()) return; playing.set(true); thread=new Thread(() -> loop(),"calm-music"); thread.setDaemon(true); thread.start(); }
  public synchronized void stop() { playing.set(false); if(track!=null) { try{track.stop();}catch(Exception ignored){} try{track.release();}catch(Exception ignored){} track=null; } }
  private void loop() {
    final int noteMs=520; final int count=SAMPLE_RATE*noteMs/1000;
    short[] pcm=new short[count];
    try {
      AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
      AudioFormat fmt=new AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
      track=new AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt).setBufferSizeInBytes(count*2*2).setTransferMode(AudioTrack.MODE_STREAM).build();
      track.setVolume(0.055f); track.play();
      int idx=0;
      while(playing.get() && enabled()) {
        double f=notes[idx++%notes.length];
        for(int i=0;i<count;i++) { double t=(double)i/SAMPLE_RATE; double env=Math.min(1.0,i/500.0)*Math.min(1.0,(count-i)/700.0); pcm[i]=(short)(Math.sin(2*Math.PI*f*t)*env*9000); }
        track.write(pcm,0,pcm.length);
      }
    } catch(Exception ignored) { } finally { stop(); }
  }
}
