/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.spi.block;

import io.airlift.slice.Slice;
import io.airlift.slice.SliceInput;
import io.airlift.slice.SliceOutput;
import io.airlift.slice.Slices;

import static io.airlift.slice.SizeOf.SIZE_OF_INT;
import static io.trino.spi.block.EncoderUtil.decodeNullBits;
import static io.trino.spi.block.EncoderUtil.encodeNullsAsBits;

public class VariableWidthBlockEncoding
        implements BlockEncoding
{
    public static final String NAME = "VARIABLE_WIDTH";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void writeBlock(BlockEncodingSerde blockEncodingSerde, SliceOutput sliceOutput, Block block)
    {
        // The down casts here are safe because it is the block itself the provides this encoding implementation.
        AbstractVariableWidthBlock variableWidthBlock = (AbstractVariableWidthBlock) block;

        int positionCount = variableWidthBlock.getPositionCount();
        sliceOutput.appendInt(positionCount);

        // lengths
        int[] lengths = new int[positionCount];
        int totalLength = 0;
        int nonNullsCount = 0;

        for (int position = 0; position < positionCount; position++) {
            int length = variableWidthBlock.getSliceLength(position);
            totalLength += length;
            lengths[nonNullsCount] = length;
            nonNullsCount += variableWidthBlock.isNull(position) ? 0 : 1;
        }

        sliceOutput
                .appendInt(nonNullsCount)
                .writeBytes(Slices.wrappedIntArray(lengths, 0, nonNullsCount));

        encodeNullsAsBits(sliceOutput, variableWidthBlock);

        sliceOutput
                .appendInt(totalLength)
                .writeBytes(variableWidthBlock.getRawSlice(0), variableWidthBlock.getPositionOffset(0), totalLength);
    }

    @Override
    public Block readBlock(BlockEncodingSerde blockEncodingSerde, SliceInput sliceInput)
    {
        int positionCount = sliceInput.readInt();
        int nonNullsCount = sliceInput.readInt();

        int[] offsets = new int[positionCount + 1];
        int[] lengths = new int[nonNullsCount];
        sliceInput.readBytes(Slices.wrappedIntArray(lengths), 0, nonNullsCount * SIZE_OF_INT);

        boolean[] valueIsNull = decodeNullBits(sliceInput, positionCount).orElse(null);

        int blockSize = sliceInput.readInt();
        Slice slice = Slices.allocate(blockSize);
        sliceInput.readBytes(slice);

        int nonNullPosition = 0;
        int offset = 0;

        for (int i = 0; i < positionCount; i++) {
            if (valueIsNull == null || !valueIsNull[i]) {
                offset += lengths[nonNullPosition];
                nonNullPosition++;
            }
            offsets[i + 1] = offset;
        }
        return new VariableWidthBlock(0, positionCount, slice, offsets, valueIsNull);
    }
}
