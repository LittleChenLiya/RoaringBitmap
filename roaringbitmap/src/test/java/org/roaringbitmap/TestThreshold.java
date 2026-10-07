/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */

package org.roaringbitmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

public class TestThreshold {

  /** Naive reference: expands every integer into a hash map and counts occurrences. */
  private static RoaringBitmap naiveThreshold(int k, RoaringBitmap... bitmaps) {
    Map<Integer, Integer> counts = new HashMap<>();
    for (RoaringBitmap bitmap : bitmaps) {
      for (int value : bitmap) {
        counts.merge(value, 1, Integer::sum);
      }
    }
    RoaringBitmap answer = new RoaringBitmap();
    for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
      if (entry.getValue() >= k) {
        answer.add(entry.getKey());
      }
    }
    return answer;
  }

  private static int occurrences(int value, RoaringBitmap... bitmaps) {
    int count = 0;
    for (RoaringBitmap bitmap : bitmaps) {
      if (bitmap.contains(value)) {
        count++;
      }
    }
    return count;
  }

  @Test
  public void acceptanceExample() {
    RoaringBitmap b1 = RoaringBitmap.bitmapOf(1, 2, 3);
    RoaringBitmap b2 = RoaringBitmap.bitmapOf(2, 3, 4);
    RoaringBitmap b3 = RoaringBitmap.bitmapOf(3, 4, 5);
    RoaringBitmap result = Threshold.threshold(2, b1, b2, b3);
    assertEquals(RoaringBitmap.bitmapOf(2, 3, 4), result);
    // the occurrence counts behind the result are directly observable
    assertEquals(1, occurrences(1, b1, b2, b3)); // below k=2, excluded
    assertEquals(2, occurrences(2, b1, b2, b3)); // kept
    assertEquals(3, occurrences(3, b1, b2, b3)); // kept
    assertEquals(2, occurrences(4, b1, b2, b3)); // kept
    assertEquals(1, occurrences(5, b1, b2, b3)); // below k=2, excluded
    // k=3 keeps only the value seen 3 times
    assertEquals(RoaringBitmap.bitmapOf(3), Threshold.threshold(3, b1, b2, b3));
    // k=1 keeps everything
    assertEquals(RoaringBitmap.bitmapOf(1, 2, 3, 4, 5), Threshold.threshold(1, b1, b2, b3));
  }

  @Test
  public void emptyInputYieldsEmptyBitmap() {
    RoaringBitmap result = Threshold.threshold(1);
    assertTrue(result.isEmpty());
  }

  @Test
  public void invalidThresholdIsRejected() {
    RoaringBitmap b = RoaringBitmap.bitmapOf(1, 2, 3);
    assertThrows(IllegalArgumentException.class, () -> Threshold.threshold(0, b));
    assertThrows(IllegalArgumentException.class, () -> Threshold.threshold(-1, b));
    assertThrows(IllegalArgumentException.class, () -> Threshold.threshold(2, b));
    assertThrows(IllegalArgumentException.class, () -> Threshold.threshold(3, b, b));
    assertThrows(NullPointerException.class, () -> Threshold.threshold(1, (RoaringBitmap[]) null));
    assertThrows(NullPointerException.class, () -> Threshold.threshold(1, b, null));
  }

  @Test
  public void thresholdOfOneIsOr() {
    Random random = new Random(123456789L);
    for (int trial = 0; trial < 20; ++trial) {
      RoaringBitmap[] bitmaps = randomBitmaps(random, 2 + random.nextInt(6));
      assertEquals(FastAggregation.or(bitmaps), Threshold.threshold(1, bitmaps));
    }
  }

  @Test
  public void thresholdOfNIsAnd() {
    Random random = new Random(987654321L);
    for (int trial = 0; trial < 20; ++trial) {
      RoaringBitmap[] bitmaps = randomBitmaps(random, 2 + random.nextInt(6));
      assertEquals(FastAggregation.and(bitmaps), Threshold.threshold(bitmaps.length, bitmaps));
    }
  }

  @Test
  public void duplicateInputsEachCount() {
    RoaringBitmap b1 = RoaringBitmap.bitmapOf(1, 2, 3);
    RoaringBitmap b2 = RoaringBitmap.bitmapOf(2, 3, 4);
    // the same bitmap passed twice counts as two independent inputs
    assertEquals(b1, Threshold.threshold(2, b1, b1));
    // counts: 1->1, 2->3, 3->3, 4->2
    assertEquals(RoaringBitmap.bitmapOf(2, 3, 4), Threshold.threshold(2, b1, b2, b2));
    // counts: 1->2, 2->5, 3->5, 4->3
    assertEquals(RoaringBitmap.bitmapOf(2, 3, 4), Threshold.threshold(3, b1, b1, b2, b2, b2));
    // counts: 1->2, 2->3, 3->3, 4->1
    assertEquals(RoaringBitmap.bitmapOf(2, 3), Threshold.threshold(3, b1, b1, b2));
    // disjoint inputs can never reach k=2
    assertEquals(new RoaringBitmap(), Threshold.threshold(2, b1, RoaringBitmap.bitmapOf(4, 5, 6)));
  }

  @Test
  public void valuesCrossingContainerBoundary() {
    RoaringBitmap b1 = RoaringBitmap.bitmapOf(65534, 65535, 65536, 65537, 131072);
    RoaringBitmap b2 = RoaringBitmap.bitmapOf(65535, 65536, 65537, 65538, 131072);
    RoaringBitmap b3 = RoaringBitmap.bitmapOf(65536, 65537, 65538, 65539, 131072);
    assertEquals(
        RoaringBitmap.bitmapOf(65535, 65536, 65537, 65538, 131072),
        Threshold.threshold(2, b1, b2, b3));
    assertEquals(RoaringBitmap.bitmapOf(65536, 65537, 131072), Threshold.threshold(3, b1, b2, b3));
  }

  @Test
  public void runContainersAreSupported() {
    RoaringBitmap b1 = RoaringBitmap.bitmapOfRange(0, 1 << 18);
    RoaringBitmap b2 = RoaringBitmap.bitmapOfRange(100, (1 << 18) + 100);
    RoaringBitmap b3 = RoaringBitmap.bitmapOfRange(200, 5000);
    b1.runOptimize();
    b2.runOptimize();
    b3.runOptimize();
    assertTrue(b1.hasRunCompression() && b2.hasRunCompression() && b3.hasRunCompression());
    // b1=[0,2^18), b2=[100,2^18+100), b3=[200,5000): pairs intersect on [100,2^18)
    assertEquals(RoaringBitmap.bitmapOfRange(100, 1 << 18), Threshold.threshold(2, b1, b2, b3));
    assertEquals(RoaringBitmap.bitmapOfRange(200, 5000), Threshold.threshold(3, b1, b2, b3));
  }

  @Test
  public void unsignedHighValuesAreSupported() {
    int hi1 = 0xFFFF0000; // 4294901760, negative as a signed int
    int hi2 = 0xFFFFFFFF; // 4294967295
    int hi3 = 0x80000000; // 2147483648
    RoaringBitmap b1 = RoaringBitmap.bitmapOf(hi1, hi2, hi3, 7);
    RoaringBitmap b2 = RoaringBitmap.bitmapOf(hi1, hi2, 8);
    RoaringBitmap b3 = RoaringBitmap.bitmapOf(hi1, 9);
    assertEquals(RoaringBitmap.bitmapOf(hi1, hi2), Threshold.threshold(2, b1, b2, b3));
    assertEquals(RoaringBitmap.bitmapOf(hi1), Threshold.threshold(3, b1, b2, b3));
    // last() is the unsigned maximum
    assertEquals(hi2, Threshold.threshold(2, b1, b2, b3).last());
  }

  @Test
  public void randomDataMatchesNaiveCounting() {
    Random random = new Random(0xC0FFEE);
    for (int trial = 0; trial < 200; ++trial) {
      int n = 1 + random.nextInt(8);
      RoaringBitmap[] bitmaps = randomBitmaps(random, n);
      int k = 1 + random.nextInt(n);
      RoaringBitmap expected = naiveThreshold(k, bitmaps);
      RoaringBitmap actual = Threshold.threshold(k, bitmaps);
      assertEquals(expected, actual, "trial=" + trial + " n=" + n + " k=" + k);
    }
  }

  @Test
  public void inputsAreNotModifiedAndResultIsIndependent() {
    Random random = new Random(0xBADC0DE);
    RoaringBitmap[] bitmaps = randomBitmaps(random, 6);
    RoaringBitmap[] snapshots = new RoaringBitmap[bitmaps.length];
    for (int i = 0; i < bitmaps.length; ++i) {
      snapshots[i] = bitmaps[i].clone();
    }
    RoaringBitmap result = Threshold.threshold(3, bitmaps);
    RoaringBitmap expected = naiveThreshold(3, bitmaps);
    assertEquals(expected, result);
    // aggressively mutate the result; inputs must stay exactly as they were
    result.add(0L, 1L << 20);
    result.remove(10L, 1L << 19);
    result.flip(123L, 123456L);
    result.or(bitmaps[0]);
    result.andNot(bitmaps[1]);
    result.runOptimize();
    for (int i = 0; i < bitmaps.length; ++i) {
      assertEquals(snapshots[i], bitmaps[i], "input " + i + " was modified");
    }
  }

  @Test
  public void singleInputReturnsCopyNotSharedContainer() {
    RoaringBitmap input = RoaringBitmap.bitmapOf(1, 2, 3, 65536, 65537);
    RoaringBitmap result = Threshold.threshold(1, input);
    assertEquals(input, result);
    // mutating the result must not leak into the input (no shared mutable container)
    result.add(4);
    result.remove(1);
    result.flip(65536L, 65537L);
    assertEquals(RoaringBitmap.bitmapOf(1, 2, 3, 65536, 65537), input);
  }

  @Test
  public void largeInputsAreHandledWithoutExpandingIntegers() {
    // Six run-compressed bitmaps of 3 million consecutive values each; the expected
    // threshold result is known analytically, so no per-integer expansion is needed
    // anywhere in this test.
    int n = 6;
    int k = 4;
    RoaringBitmap[] bitmaps = new RoaringBitmap[n];
    for (int i = 0; i < n; ++i) {
      // bitmap i holds [1000*i, 3_000_000 + 1000*i)
      bitmaps[i] = RoaringBitmap.bitmapOfRange(1000L * i, 3_000_000L + 1000L * i);
      bitmaps[i].runOptimize();
    }
    // value v is covered by bitmap i iff 1000*i <= v < 3_000_000 + 1000*i;
    // count(v) = #{i : v - 3_000_000 < 1000*i <= v} is >= 4 exactly on [3000, 3_002_000)
    RoaringBitmap expected = RoaringBitmap.bitmapOfRange(3000L, 3_002_000L);
    RoaringBitmap result = Threshold.threshold(k, bitmaps);
    assertEquals(expected, result);
    // k = n is the intersection
    assertEquals(RoaringBitmap.bitmapOfRange(5000L, 3_000_000L), Threshold.threshold(n, bitmaps));
    // k = 1 is the union
    assertEquals(RoaringBitmap.bitmapOfRange(0L, 3_005_000L), Threshold.threshold(1, bitmaps));
  }

  private static RoaringBitmap[] randomBitmaps(Random random, int n) {
    RoaringBitmap[] bitmaps = new RoaringBitmap[n];
    for (int i = 0; i < n; ++i) {
      bitmaps[i] = randomBitmap(random, random.nextInt(6));
    }
    return bitmaps;
  }

  private static RoaringBitmap randomBitmap(Random random, int kind) {
    RoaringBitmap bitmap = new RoaringBitmap();
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
