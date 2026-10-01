package org.roaringbitmap;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.nio.ByteBuffer;
import java.util.TreeSet;

@Execution(ExecutionMode.CONCURRENT)
public class TestRoaringBitmapPreflight {

  private static final int ARRAY_0_CARDINALITY = 50;
  private static final int BITMAP_CARDINALITY = 5000;
  private static final int RUN_LENGTH = 10000;
  private static final int ARRAY_3_CARDINALITY = 3;

  // Container sizes for the reference bitmap below.
  private static final int ARRAY_0_SIZE = 2 * ARRAY_0_CARDINALITY;
  private static final int BITMAP_SIZE = 8192;
  private static final int RUN_SIZE = 2 + 4;
  private static final int ARRAY_3_SIZE = 2 * ARRAY_3_CARDINALITY;

  /** Builds a bitmap with four containers: array, bitmap, run, array. */
  private static RoaringBitmap referenceBitmap() {
    RoaringBitmap bitmap = new RoaringBitmap();
    for (int i = 0; i < ARRAY_0_CARDINALITY; ++i) {
      bitmap.add(i * 7);
    }
    for (int i = 0; i < BITMAP_CARDINALITY; ++i) {
      bitmap.add(65536 + i * 3);
    }
    bitmap.add(2L * 65536, 2L * 65536 + RUN_LENGTH);
    bitmap.add(3 * 65536);
    bitmap.add(3 * 65536 + 5);
    bitmap.add(3 * 65536 + 9);
    bitmap.runOptimize();
    return bitmap;
  }

  private static ByteBuffer serialize(RoaringBitmap bitmap) {
    ByteBuffer buffer = ByteBuffer.allocate(bitmap.serializedSizeInBytes());
    bitmap.serialize(buffer);
    buffer.flip();
    return buffer;
  }

  private static int headerSize(int containerCount, boolean hasRunContainers) {
    int size = 4;
    if (!hasRunContainers) {
      size += 4;
    }
    if (hasRunContainers) {
      size += (containerCount + 7) / 8;
    }
    size += 4 * containerCount;
    if (!hasRunContainers || containerCount >= 4) {
      size += 4 * containerCount;
    }
    return size;
  }

  private static int offsetTablePosition(int containerCount) {
    return 4 + (containerCount + 7) / 8 + 4 * containerCount;
  }

  @Test
  public void validBitmapWithAllContainerTypesPassesAndDecodes() throws java.io.IOException {
    RoaringBitmap bitmap = referenceBitmap();
    ByteBuffer buffer = serialize(bitmap);
    int positionBefore = buffer.position();

    RoaringBitmapPreflight.Result result = RoaringBitmapPreflight.check(buffer);
    assertTrue(result.isValid(), result.toString());
    assertEquals(-1, result.errorOffset());
    assertEquals(-1, result.containerIndex());
    assertEquals(bitmap.serializedSizeInBytes(), result.serializedSizeInBytes());
    // the buffer must be left untouched
    assertEquals(positionBefore, buffer.position());

    RoaringBitmap decoded = new RoaringBitmap();
    decoded.deserialize(buffer);
    assertTrue(decoded.validate());
    assertEquals(bitmap, decoded);
    TreeSet<Integer> expected = new TreeSet<>();
    bitmap.forEach((int value) -> expected.add(value));
    TreeSet<Integer> actual = new TreeSet<>();
    decoded.forEach((int value) -> actual.add(value));
    assertEquals(expected, actual);
  }

  @Test
  public void staticApiOnRoaringBitmapWorks() {
    ByteBuffer buffer = serialize(referenceBitmap());
    assertTrue(RoaringBitmap.checkSerialized(buffer).isValid());
    assertTrue(RoaringBitmap.checkSerialized(buffer, 4).isValid());
  }

  @Test
  public void overlappingOffsetIsRejected() {
    RoaringBitmap bitmap = referenceBitmap();
    ByteBuffer buffer = serialize(bitmap);
    int offsetTable = offsetTablePosition(4);
    ByteBuffer view = buffer.duplicate().order(LITTLE_ENDIAN);
    int firstOffset = view.getInt(offsetTable);
    // point the third container at the first container's offset (overlap)
    view.putInt(offsetTable + 2 * 4, firstOffset);

    RoaringBitmapPreflight.Result result = RoaringBitmapPreflight.check(buffer);
    assertFalse(result.isValid());
    assertEquals(offsetTable + 2 * 4, result.errorOffset());
    assertEquals(2, result.containerIndex());
  }

  @Test
  public void mutatedRunLengthIsRejected() {
    RoaringBitmap bitmap = referenceBitmap();
    ByteBuffer buffer = serialize(bitmap);
    int runContainerPos = headerSize(4, true) + ARRAY_0_SIZE + BITMAP_SIZE;
    ByteBuffer view = buffer.duplicate().order(LITTLE_ENDIAN);
    assertEquals(1, view.getChar(runContainerPos)); // a single run
    // shrink the run: the declared cardinality no longer matches the content
    view.putChar(runContainerPos + 2 + 2, (char) (RUN_LENGTH - 2));

    RoaringBitmapPreflight.Result result = RoaringBitmapPreflight.check(buffer);
    assertFalse(result.isValid());
    assertEquals(runContainerPos, result.errorOffset());
    assertEquals(2, result.containerIndex());
  }

  @Test
  public void runOverflowingContainerRangeIsRejected() {
    RoaringBitmap bitmap = referenceBitmap();
    ByteBuffer buffer = serialize(bitmap);
    int runContainerPos = headerSize(4, true) + ARRAY_0_SIZE + BITMAP_SIZE;
    ByteBuffer view = buffer.duplicate().order(LITTLE_ENDIAN);
    // run starting at 1 with a length that overflows the 16-bit container range
    view.putChar(runContainerPos + 2, (char) 1);
    view.putChar(runContainerPos + 2 + 2, (char) 0xFFFF);

    RoaringBitmapPreflight.Result result = RoaringBitmapPreflight.check(buffer);
    assertFalse(result.isValid());
    assertEquals(2, result.containerIndex());
  }

  @Test
  public void sliceStartIsUsedAsOffsetBase() {
    RoaringBitmap bitmap = referenceBitmap();
    ByteBuffer raw = serialize(bitmap);
    byte[] bytes = new byte[raw.remaining()];
    raw.get(bytes);

    int prefix = 37;
    ByteBuffer shifted = ByteBuffer.allocate(prefix + bytes.length + 13);
    shifted.position(prefix);
    shifted.put(bytes);
    shifted.position(prefix);
    shifted.limit(prefix + bytes.length);

    RoaringBitmapPreflight.Result result = RoaringBitmapPreflight.check(shifted);
    assertTrue(result.isValid(), result.toString());
    assertEquals(bytes.length, result.serializedSizeInBytes());
    // position and limit are untouched
    assertEquals(prefix, shifted.position());
    assertEquals(prefix + bytes.length, shifted.limit());

    // offsets stored in the payload are relative to the slice start: "fixing" them to be
    // absolute must be rejected
    ByteBuffer absolute = ByteBuffer.allocate(prefix + bytes.length);
    absolute.position(prefix);
    absolute.put(bytes);
    absolute.position(prefix);
    ByteBuffer view = absolute.duplicate().order(LITTLE_ENDIAN);
    int offsetTable = prefix + offsetTablePosition(4);
    for (int k = 0; k < 4; ++k) {
      view.putInt(offsetTable + 4 * k, view.getInt(offsetTable + 4 * k) + prefix);
    }
    RoaringBitmapPreflight.Result shiftedResult = RoaringBitmapPreflight.check(absolute);
    assertFalse(shiftedResult.isValid());
    assertEquals(offsetTablePosition(4), shiftedResult.errorOffset());
    assertEquals(0, shiftedResult.containerIndex());
  }

  @Test
  public void truncatedInputIsRejected() {
    RoaringBitmap bitmap = referenceBitmap();
    ByteBuffer buffer = serialize(bitmap);
    int total = buffer.remaining();

    ByteBuffer view = buffer.duplicate();
    view.limit(2);
    assertFalse(RoaringBitmapPreflight.check(view).isValid());

    view = buffer.duplicate();
    view.limit(10);
    assertFalse(RoaringBitmapPreflight.check(view).isValid());

    view = buffer.duplicate();
    view.limit(total - 1);
    RoaringBitmapPreflight.Result result = RoaringBitmapPreflight.check(view);
    assertFalse(result.isValid());
    assertEquals(3, result.containerIndex());
    assertTrue(result.message().contains("truncated"));
  }

  @Test
  public void smallBitmapWithoutOffsetTablePasses() throws java.io.IOException {
    RoaringBitmap bitmap = new RoaringBitmap();
    bitmap.add(10L, 20L); // becomes a run container
    bitmap.add(65536 + 3); // array container
    bitmap.runOptimize();
    ByteBuffer buffer = serialize(bitmap);
    // with fewer than 4 containers and a run container, the offset table is omitted
    assertEquals(headerSize(2, true) + 6 + 2, buffer.remaining());

    RoaringBitmapPreflight.Result result = RoaringBitmapPreflight.check(buffer);
    assertTrue(result.isValid(), result.toString());

    RoaringBitmap decoded = new RoaringBitmap();
    decoded.deserialize(buffer);
    assertEquals(bitmap, decoded);
  }

  @Test
  public void readOnlyAndDirectBuffersAreSupported() {
    RoaringBitmap bitmap = referenceBitmap();
    ByteBuffer buffer = serialize(bitmap);

    ByteBuffer readOnly = buffer.asReadOnlyBuffer();
    assertTrue(RoaringBitmapPreflight.check(readOnly).isValid());

    ByteBuffer direct = ByteBuffer.allocateDirect(buffer.remaining());
    direct.put(buffer.duplicate());
    direct.flip();
    assertTrue(RoaringBitmapPreflight.check(direct).isValid());
    assertTrue(RoaringBitmapPreflight.check(direct.asReadOnlyBuffer()).isValid());
  }

  @Test
  public void scanLimitIsEnforced() {
    ByteBuffer buffer = serialize(referenceBitmap());
    RoaringBitmapPreflight.Result limited = RoaringBitmapPreflight.check(buffer, 3);
    assertFalse(limited.isValid());
    assertEquals(-1, limited.containerIndex());
    assertTrue(limited.message().contains("scan limit"));
    assertTrue(RoaringBitmapPreflight.check(buffer, 4).isValid());
    assertTrue(RoaringBitmapPreflight.check(buffer, 1000).isValid());
  }

  @Test
  public void badCookieIsRejected() {
    ByteBuffer buffer = serialize(referenceBitmap());
    ByteBuffer view = buffer.duplicate().order(LITTLE_ENDIAN);
    view.putInt(0, 9999);
    RoaringBitmapPreflight.Result result = RoaringBitmapPreflight.check(buffer);
    assertFalse(result.isValid());
    assertEquals(0, result.errorOffset());
    assertEquals(-1, result.containerIndex());
  }

  @Test
  public void unsortedArrayContainerIsRejected() {
    RoaringBitmap bitmap = referenceBitmap();
    ByteBuffer buffer = serialize(bitmap);
    int arrayPos = headerSize(4, true);
    ByteBuffer view = buffer.duplicate().order(LITTLE_ENDIAN);
    // swap the first two values of the leading array container
    char first = view.getChar(arrayPos);
    view.putChar(arrayPos, view.getChar(arrayPos + 2));
    view.putChar(arrayPos + 2, first);

    RoaringBitmapPreflight.Result result = RoaringBitmapPreflight.check(buffer);
    assertFalse(result.isValid());
    assertEquals(0, result.containerIndex());
  }

  @Test
  public void bitmapContainerCardinalityMismatchIsRejected() {
    RoaringBitmap bitmap = referenceBitmap();
    ByteBuffer buffer = serialize(bitmap);
    int bitmapPos = headerSize(4, true) + ARRAY_0_SIZE;
    ByteBuffer view = buffer.duplicate().order(LITTLE_ENDIAN);
    // flip one bit in the bitmap container without updating the declared cardinality
    view.putLong(bitmapPos, view.getLong(bitmapPos) ^ 1L);

    RoaringBitmapPreflight.Result result = RoaringBitmapPreflight.check(buffer);
    assertFalse(result.isValid());
    assertEquals(bitmapPos, result.errorOffset());
    assertEquals(1, result.containerIndex());
  }
}
