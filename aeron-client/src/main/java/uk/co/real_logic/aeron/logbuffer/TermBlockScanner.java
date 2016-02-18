/*
 * Copyright 2015 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.aeron.logbuffer;

import uk.co.real_logic.agrona.concurrent.UnsafeBuffer;

import static uk.co.real_logic.aeron.logbuffer.FrameDescriptor.FRAME_ALIGNMENT;
import static uk.co.real_logic.aeron.logbuffer.FrameDescriptor.frameLengthVolatile;
import static uk.co.real_logic.agrona.BitUtil.align;

/**
 * Scan a term buffer for a block of messages including padding. The block must include complete messages.
 */
public class TermBlockScanner {
    /**
     * Scan a term buffer for a block of messages from and offset up to a limit.
     *
     * @param termBuffer to scan for messages.
     * @param offset     at which the scan should begin.
     * @param limit      at which the scan should stop.
     * @return the offset at which the scan terminated.
     */
    public static int scan(final UnsafeBuffer termBuffer, int offset, final int limit) {
        do {
            final int frameLength = frameLengthVolatile(termBuffer, offset);
            if (frameLength <= 0) {
                break;
            }

            final int alignedFrameLength = align(frameLength, FRAME_ALIGNMENT);
            offset += alignedFrameLength;
            if (offset >= limit) {
                if (offset > limit) {
                    offset -= alignedFrameLength;
                }

                break;
            }
        }
        while (true);

        return offset;
    }
}
