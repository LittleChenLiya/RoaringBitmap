/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */

package org.roaringbitmap.buffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

public class TestBufferThreshold {

  /** Naive reference: expands every integer into a hash map and counts occurrences. */
  private static MutableRoaringBitmap naiveThreshold(int k, ImmutableRoaringBitmap... bitmaps) {
    Map<Integer, Integer> counts = new HashMap<>();
    for (ImmutableRoaringBitmap bitmap : bitmaps) {
      for (int value : bitmap) {
        counts.merge(value, 1, Integer::sum);
      }
    }
    MutableRoaringBitmap answer = new MutableRoaringBitmap();
    for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
      if (entry.getValue() >= k) {
        answer.add(entry.getKey());
      }
    }
    return answer;
  }

  private static MutableRoaringBitmap bitmapOf(int... values) {
    MutableRoaringBitmap bitmap = new MutableRoaringBitmap();
    for (int value : values) {
      bitmap.add(value);
    }
    return bitmap;
  }

  @Test
  public void acceptanceExample() {
    MutableRoaringBitmap b1 = bitmapOf(1, 2, 3);
    MutableRoaringBitmap b2 = bitmapOf(2, 3, 4);
    MutableRoaringBitmap b3 = bitmapOf(3, 4, 5);
    assertEquals(bitmapOf(2, 3, 4), BufferThreshold.threshold(2, b1, b2, b3));
    assertEquals(bitmapOf(3), BufferThreshold.threshold(3, b1, b2, b3));
    assertEquals(bitmapOf(1, 2, 3, 4, 5), BufferThreshold.threshold(1, b1, b2, b3));
  }

  @Test
  public void emptyInputYieldsEmptyBitmap() {
    assertTrue(BufferThreshold.threshold(1).isEmpty());
  }

  @Test
  public void invalidThresholdIsRejected() {
    MutableRoaringBitmap b = bitmapOf(1, 2, 3);
    assertThrows(IllegalArgumentException.class, () -> BufferThreshold.threshold(0, b));
    assertThrows(IllegalArgumentException.class, () -> BufferThreshold.threshold(-1, b));
    assertThrows(IllegalArgumentException.class, () -> BufferThreshold.threshold(2, b));
    assertThrows(
        NullPointerException.class,
        () -> BufferThreshold.threshold(1, (ImmutableRoaringBitmap[]) null));
    assertThrows(NullPointerException.class, () -> BufferThreshold.threshold(1, b, null));
  }

  @Test
  public void thresholdOfOneIsOrAndThresholdOfNIsAnd() {
    Random random = new Random(0xFEEDFACEL);
    for (int trial = 0; trial < 20; ++trial) {
      ImmutableRoaringBitmap[] bitmaps = randomBitmaps(random, 2 + random.nextInt(6));
      assertEquals(BufferFastAggregation.or(bitmaps), BufferThreshold.threshold(1, bitmaps));
      assertEquals(
          BufferFastAggregation.and(bitmaps), BufferThreshold.threshold(bitmaps.length, bitmaps));
    }
  }

  @Test
  public void duplicateInputsEachCount() {
    MutableRoaringBitmap b1 = bitmapOf(1, 2, 3);
    MutableRoaringBitmap b2 = bitmapOf(2, 3, 4);
    assertEquals(b1, BufferThreshold.threshold(2, b1, b1));
    assertEquals(bitmapOf(2, 3, 4), BufferThreshold.threshold(2, b1, b2, b2));
    assertEquals(bitmapOf(2, 3), BufferThreshold.threshold(3, b1, b1, b2));
    assertEquals(new MutableRoaringBitmap(), BufferThreshold.threshold(2, b1, bitmapOf(4, 5, 6)));
  }

  @Test
  public void boundaryRunAndHighValues() {
    MutableRoaringBitmap b1 = bitmapOf(65535, 65536, 0xFFFFFFFF, 0x80000000);
    MutableRoaringBitmap b2 = bitmapOf(65536, 65537, 0xFFFFFFFF);
    MutableRoaringBitmap b3 = new MutableRoaringBitmap();
    b3.add(65530L, 65540L);
    b3.add(0xFFFFFFF0L, 0x100000000L);
    b3.runOptimize();
    assertTrue(b3.hasRunCompression());
    assertEquals(bitmapOf(65536, 0xFFFFFFFF), BufferThreshold.threshold(3, b1, b2, b3));
    assertEquals(
        bitmapOf(65535, 65536, 65537, 0xFFFFFFFF), BufferThreshold.threshold(2, b1, b2, b3));
  }

  @Test
  public void randomDataMatchesNaiveCounting() {
    Random random = new Random(0xDEADBEEFL);
    for (int trial = 0; trial < 200; ++trial) {
      int n = 1 + random.nextInt(8);
      ImmutableRoaringBitmap[] bitmaps = randomBitmaps(random, n);
      int k = 1 + random.nextInt(n);
      assertEquals(
          naiveThreshold(k, bitmaps),
          BufferThreshold.threshold(k, bitmaps),
          "trial=" + trial + " n=" + n + " k=" + k);
    }
  }

  @Test
  public void inputsAreNotModifiedAndResultIsIndependent() {
    Random random = new Random(0x5EED5L);
    ImmutableRoaringBitmap[] bitmaps = randomBitmaps(random, 6);
    MutableRoaringBitmap[] snapshots = new MutableRoaringBitmap[bitmaps.length];
    for (int i = 0; i < bitmaps.length; ++i) {
      snapshots[i] = bitmaps[i].toMutableRoaringBitmap();
    }
    MutableRoaringBitmap result = BufferThreshold.threshold(3, bitmaps);
    assertEquals(naiveThreshold(3, bitmaps), result);
    // aggressively mutate the result; inputs must stay exactly as they were
    result.add(0L, 1L << 20);
    result.remove(10L, 1L << 19);
    result.flip(123L, 123456L);
    result.or(bitmaps[0].toMutableRoaringBitmap());
    result.runOptimize();
    for (int i = 0; i < bitmaps.length; ++i) {
      assertEquals(snapshots[i], bitmaps[i], "input " + i + " was modified");
    }
  }

  @Test
  public void largeInputsAreHandledWithoutExpandingIntegers() {
    int n = 6;
    int k = 4;
    ImmutableRoaringBitmap[] bitmaps = new ImmutableRoaringBitmap[n];
    for (int i = 0; i < n; ++i) {
      MutableRoaringBitmap bitmap = new MutableRoaringBitmap();
      bitmap.add(1000L * i, 3_000_000L + 1000L * i);
      bitmap.runOptimize();
      bitmaps[i] = bitmap;
    }
    MutableRoaringBitmap expected = new MutableRoaringBitmap();
    expected.add(3000L, 3_002_000L);
    assertEquals(expected, BufferThreshold.threshold(k, bitmaps));
  }

  private static ImmutableRoaringBitmap[] randomBitmaps(Random random, int n) {
    ImmutableRoaringBitmap[] bitmaps = new ImmutableRoaringBitmap[n];
    for (int i = 0; i < n; ++i) {
      bitmaps[i] = randomBitmap(random, random.nextInt(6));
    }
    return bitmaps;
  }

  private static MutableRoaringBitmap randomBitmap(Random random, int kind) {
    MutableRoaringBitmap bitmap = new MutableRoaringBitmap();
    switch (kind) {
      case 0: // empty or almost empty
        if (random.nextBoolean()) {
          bitmap.add(random.nextInt());
        }
        break;
      case 1: // sparse, full unsigned 32-bit range
        for (int i = 0; i < 1 + random.nextInt(2000); ++i) {
          bitmap.add(random.nextInt());
        }
        break;
      case 2: // runs crossing 65536 boundaries
        for (int c = 0; c < 1 + random.nextInt(4); ++c) {
          long base = (random.nextInt(64) * 65536L) - 2000 + random.nextInt(4000);
          if (base < 0) {
            base = 0;
          }
          bitmap.add(base, base + random.nextInt(9000));
        }
        bitmap.runOptimize();
        break;
      case 3: // dense band (bitmap containers)
        {
          long start = Integer.toUnsignedLong(random.nextInt()) % (1L << 26);
          bitmap.add(start, start + 50000 + random.nextInt(100000));
          break;
        }
      case 4: // clustered small ranges
        for (int c = 0; c < 1 + random.nextInt(8); ++c) {
          long start = Integer.toUnsignedLong(random.nextInt());
          start %= 1L << 24;
          bitmap.add(start, start + random.nextInt(300));
        }
        if (random.nextBoolean()) {
          bitmap.runOptimize();
        }
        break;
      default: // high unsigned values
        for (int i = 0; i < 1 + random.nextInt(500); ++i) {
          bitmap.add(0xFFFFF000 + random.nextInt(4096));
        }
        break;
    }
    return bitmap;
  }
}
