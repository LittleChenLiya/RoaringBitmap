/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */

package org.roaringbitmap.buffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

@Execution(ExecutionMode.CONCURRENT)
public class BufferThresholdTest {

  /** Naive reference implementation: expands every integer into a hash map. */
  private static MutableRoaringBitmap naiveThreshold(int k, ImmutableRoaringBitmap... bitmaps) {
    Map<Integer, Integer> counts = new HashMap<>();
    for (ImmutableRoaringBitmap bitmap : bitmaps) {
      bitmap.forEach((int value) -> counts.merge(value, 1, Integer::sum));
    }
    MutableRoaringBitmap answer = new MutableRoaringBitmap();
    counts.forEach(
        (value, count) -> {
          if (count >= k) {
            answer.add(value);
          }
        });
    return answer;
  }

  @Test
  public void acceptanceExample() {
    MutableRoaringBitmap a = MutableRoaringBitmap.bitmapOf(1, 2, 3);
    MutableRoaringBitmap b = MutableRoaringBitmap.bitmapOf(2, 3, 4);
    MutableRoaringBitmap c = MutableRoaringBitmap.bitmapOf(3, 4, 5);
    MutableRoaringBitmap result = BufferThreshold.threshold(2, a, b, c);
    assertEquals(MutableRoaringBitmap.bitmapOf(2, 3, 4), result);
    assertEquals(naiveThreshold(2, a, b, c), result);
    assertEquals(MutableRoaringBitmap.bitmapOf(3), BufferThreshold.threshold(3, a, b, c));
    assertEquals(
        MutableRoaringBitmap.bitmapOf(1, 2, 3, 4, 5), BufferThreshold.threshold(1, a, b, c));
  }

  @Test
  public void thresholdOfOneIsUnionAndThresholdOfNIsIntersection() {
    MutableRoaringBitmap a = MutableRoaringBitmap.bitmapOf(1, 2, 3, 1 << 16);
    MutableRoaringBitmap b = MutableRoaringBitmap.bitmapOf(3, 4, 5, 2 << 16);
    MutableRoaringBitmap c = MutableRoaringBitmap.bitmapOf(3, 5, 7);
    assertEquals(BufferFastAggregation.or(a, b, c), BufferThreshold.threshold(1, a, b, c));
    assertEquals(BufferFastAggregation.and(a, b, c), BufferThreshold.threshold(3, a, b, c));
  }

  @Test
  public void duplicateInputsCountSeparately() {
    MutableRoaringBitmap a = MutableRoaringBitmap.bitmapOf(1, 2, 3);
    MutableRoaringBitmap b = MutableRoaringBitmap.bitmapOf(3, 4, 5);
    assertEquals(a, BufferThreshold.threshold(2, a, a));
    assertEquals(MutableRoaringBitmap.bitmapOf(3), BufferThreshold.threshold(3, a, a, b));
    assertEquals(MutableRoaringBitmap.bitmapOf(1, 2, 3), BufferThreshold.threshold(2, a, a, b));
    assertThrows(IllegalArgumentException.class, () -> BufferThreshold.threshold(4, a, a, b));
    assertEquals(
        new MutableRoaringBitmap(),
        BufferThreshold.threshold(2, a, MutableRoaringBitmap.bitmapOf(4, 5, 6)));
  }

  @Test
  public void crossesContainerBoundariesAndHighValues() {
    MutableRoaringBitmap a = MutableRoaringBitmap.bitmapOf(65535, 65536, 65537, -1);
    MutableRoaringBitmap b = MutableRoaringBitmap.bitmapOf(65535, 65536, 200000, -1);
    MutableRoaringBitmap c = MutableRoaringBitmap.bitmapOf(65536, 65537, Integer.MIN_VALUE);
    MutableRoaringBitmap result = BufferThreshold.threshold(2, a, b, c);
    assertEquals(MutableRoaringBitmap.bitmapOf(65535, 65536, 65537, -1), result);
    assertEquals(naiveThreshold(2, a, b, c), result);
    assertEquals(MutableRoaringBitmap.bitmapOf(65536), BufferThreshold.threshold(3, a, b, c));
  }

  @Test
  public void runContainers() {
    MutableRoaringBitmap a = new MutableRoaringBitmap();
    a.add(0L, 100000L);
    a.runOptimize();
    MutableRoaringBitmap b = new MutableRoaringBitmap();
    b.add(50000L, 150000L);
    b.runOptimize();
    MutableRoaringBitmap c = new MutableRoaringBitmap();
    c.add(90000L, 110000L);
    c.runOptimize();
    MutableRoaringBitmap expected = new MutableRoaringBitmap();
    expected.add(50000L, 110000L);
    MutableRoaringBitmap result = BufferThreshold.threshold(2, a, b, c);
    assertEquals(expected, result);
    assertEquals(naiveThreshold(2, a, b, c), result);
  }

  @Test
  public void invalidArguments() {
    MutableRoaringBitmap a = MutableRoaringBitmap.bitmapOf(1, 2, 3);
    assertThrows(IllegalArgumentException.class, () -> BufferThreshold.threshold(1));
    assertThrows(IllegalArgumentException.class, () -> BufferThreshold.threshold(0, a));
    assertThrows(IllegalArgumentException.class, () -> BufferThreshold.threshold(2, a));
    assertThrows(
        NullPointerException.class, () -> BufferThreshold.threshold(1, a, null));
    assertThrows(
        NullPointerException.class,
        () -> BufferThreshold.threshold(1, (ImmutableRoaringBitmap[]) null));
  }

  @Test
  public void inputsAreNotModifiedAndResultDoesNotShareContainers() {
    MutableRoaringBitmap a = MutableRoaringBitmap.bitmapOf(1, 2, 3, 70000);
    MutableRoaringBitmap b = new MutableRoaringBitmap();
    b.add(2L, 80000L);
    b.runOptimize();
    MutableRoaringBitmap c = MutableRoaringBitmap.bitmapOf(2, 3, 4, 70000, 80000);
    MutableRoaringBitmap aCopy = a.clone();
    MutableRoaringBitmap bCopy = b.clone();
    MutableRoaringBitmap cCopy = c.clone();
    MutableRoaringBitmap result = BufferThreshold.threshold(2, a, b, c);
    assertEquals(aCopy, a);
    assertEquals(bCopy, b);
    assertEquals(cCopy, c);
    // mutating the result must not leak into the inputs
    result.add(5);
    result.remove(2);
    assertEquals(aCopy, a);
    assertEquals(bCopy, b);
    assertEquals(cCopy, c);
    // mutating the inputs must not leak into the result
    MutableRoaringBitmap resultCopy = result.clone();
    a.clear();
    b.clear();
    c.clear();
    assertEquals(resultCopy, result);
  }

  @Test
  public void randomizedAgainstNaiveCounting() {
    Random random = new Random(0xB0FF_E5L);
    for (int round = 0; round < 200; ++round) {
      int n = 1 + random.nextInt(6);
      MutableRoaringBitmap[] bitmaps = new MutableRoaringBitmap[n];
      for (int i = 0; i < n; ++i) {
        bitmaps[i] = randomBitmap(random);
      }
      int k = 1 + random.nextInt(n);
      MutableRoaringBitmap expected = naiveThreshold(k, bitmaps);
      MutableRoaringBitmap actual = BufferThreshold.threshold(k, bitmaps);
      assertEquals(expected, actual, "round=" + round + " k=" + k + " n=" + n);
    }
  }

  private static MutableRoaringBitmap randomBitmap(Random random) {
    MutableRoaringBitmap bitmap = new MutableRoaringBitmap();
    int containerCount = random.nextInt(4);
    for (int c = 0; c < containerCount; ++c) {
      int key = random.nextBoolean() ? random.nextInt(8) : 0xFFF0 + random.nextInt(16);
      long base = (key & 0xFFFFL) << 16;
      switch (random.nextInt(3)) {
        case 0:
          int sparse = 1 + random.nextInt(200);
          for (int j = 0; j < sparse; ++j) {
            bitmap.add((int) (base + random.nextInt(1 << 16)));
          }
          break;
        case 1:
          int dense = 5000 + random.nextInt(20000);
          for (int j = 0; j < dense; ++j) {
            bitmap.add((int) (base + random.nextInt(1 << 16)));
          }
          break;
        default:
          int runs = 1 + random.nextInt(5);
          for (int r = 0; r < runs; ++r) {
            int start = random.nextInt(1 << 16);
            int length = random.nextInt(20000);
            bitmap.add(base + start, base + Math.min(1 << 16, start + length));
          }
          bitmap.runOptimize();
          break;
      }
    }
    return bitmap;
  }

  @Test
  public void denseInputsPerformanceSmokeTest() {
    Random random = new Random(42L);
    int n = 8;
    int universe = 1_500_000;
    MutableRoaringBitmap[] bitmaps = new MutableRoaringBitmap[n];
    for (int i = 0; i < n; ++i) {
      MutableRoaringBitmap bitmap = new MutableRoaringBitmap();
      for (int j = 0; j < 1_000_000; ++j) {
        bitmap.add(random.nextInt(universe));
      }
      bitmaps[i] = bitmap;
    }
    long start = System.nanoTime();
    MutableRoaringBitmap result = BufferThreshold.threshold(4, bitmaps);
    long thresholdMillis = (System.nanoTime() - start) / 1_000_000;
    start = System.nanoTime();
    MutableRoaringBitmap expected = naiveThreshold(4, bitmaps);
    long naiveMillis = (System.nanoTime() - start) / 1_000_000;
    assertEquals(expected, result);
    System.out.printf(
        "dense buffer threshold: %d ms, naive hash-map counting: %d ms, cardinality %d%n",
        thresholdMillis, naiveMillis, result.getCardinality());
  }
}
