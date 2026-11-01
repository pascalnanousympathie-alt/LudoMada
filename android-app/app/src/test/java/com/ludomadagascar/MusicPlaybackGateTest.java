package com.ludomadagascar;

import org.junit.Test;
import static org.junit.Assert.*;

public class MusicPlaybackGateTest {
  @Test public void onlyOneMusicSessionCanStartAtATime() {
    MusicPlaybackGate gate=new MusicPlaybackGate();
    long first=gate.begin();
    assertTrue(first>0L);
    assertTrue(gate.isCurrent(first));
    assertEquals(-1L,gate.begin());
  }

  @Test public void stopInvalidatesThePreviouslyActiveWriter() {
    MusicPlaybackGate gate=new MusicPlaybackGate();
    long first=gate.begin();
    long stopped=gate.end();
    assertTrue(stopped>first);
    assertFalse(gate.isCurrent(first));
    assertEquals(-1L,gate.begin());
  }

  @Test public void restartingCreatesANewGenerationAndKeepsOldWorkersStale() {
    MusicPlaybackGate gate=new MusicPlaybackGate();
    long first=gate.begin();
    gate.end();
    long second=gate.begin();
    assertTrue(second>first);
    assertFalse(gate.isCurrent(first));
    assertTrue(gate.isCurrent(second));
  }

  @Test public void repeatedStopsDoNotReactivateAnyOldSession() {
    MusicPlaybackGate gate=new MusicPlaybackGate();
    long first=gate.begin();
    gate.end();
    gate.end();
    assertFalse(gate.isCurrent(first));
    long second=gate.begin();
    assertTrue(second>first);
    assertTrue(gate.isCurrent(second));
  }

  @Test public void staleWorkerCannotEndItsSuccessor() {
    MusicPlaybackGate gate=new MusicPlaybackGate();
    long first=gate.begin();
    gate.end();
    long second=gate.begin();
    assertFalse(gate.endIfCurrent(first));
    assertTrue(gate.isCurrent(second));
    assertTrue(gate.endIfCurrent(second));
    assertFalse(gate.isCurrent(second));
  }

  @Test public void completedWorkerFreesTheGateForRestart() {
    MusicPlaybackGate gate=new MusicPlaybackGate();
    long first=gate.begin();
    assertTrue(gate.endIfCurrent(first));
    long second=gate.begin();
    assertTrue(second>first);
    assertTrue(gate.isCurrent(second));
  }
}
