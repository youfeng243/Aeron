/*
 * Copyright 2014 - 2015 Real Logic Ltd.
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
package uk.co.real_logic.aeron.protocol;

import uk.co.real_logic.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;

import static java.nio.ByteOrder.LITTLE_ENDIAN;

/**
 * Flyweight for a Nak Packet
 *
 * <a href="https://github.com/real-logic/Aeron/wiki/Protocol-Specification#data-recovery-via-retransmit-request">
 * Data Recovery</a>
 */
public class NakFlyweight extends HeaderFlyweight {
    public static final int HEADER_LENGTH = 28;

    private static final int SESSION_ID_FIELD_OFFSET = 8;
    private static final int STREAM_ID_FIELD_OFFSET = 12;
    private static final int TERM_ID_FIELD_OFFSET = 16;
    private static final int TERM_OFFSET_FIELD_OFFSET = 20;
    private static final int LENGTH_FIELD_OFFSET = 24;

    public NakFlyweight() {
    }

    public NakFlyweight(final ByteBuffer buffer) {
        super(buffer);
    }

    public NakFlyweight(final UnsafeBuffer buffer) {
        super(buffer);
    }

    /**
     * return session id field
     *
     * @return session id field
     */
    public int sessionId() {
        return getInt(SESSION_ID_FIELD_OFFSET, LITTLE_ENDIAN);
    }

    /**
     * set session id field
     *
     * @param sessionId field value
     * @return flyweight
     */
    public NakFlyweight sessionId(final int sessionId) {
        putInt(SESSION_ID_FIELD_OFFSET, sessionId, LITTLE_ENDIAN);

        return this;
    }

    /**
     * return stream id field
     *
     * @return stream id field
     */
    public int streamId() {
        return getInt(STREAM_ID_FIELD_OFFSET, LITTLE_ENDIAN);
    }

    /**
     * set stream id field
     *
     * @param streamId field value
     * @return flyweight
     */
    public NakFlyweight streamId(final int streamId) {
        putInt(STREAM_ID_FIELD_OFFSET, streamId, LITTLE_ENDIAN);

        return this;
    }

    /**
     * return term id field
     *
     * @return term id field
     */
    public int termId() {
        return getInt(TERM_ID_FIELD_OFFSET, LITTLE_ENDIAN);
    }

    /**
     * set term id field
     *
     * @param termId field value
     * @return flyweight
     */
    public NakFlyweight termId(final int termId) {
        putInt(TERM_ID_FIELD_OFFSET, termId, LITTLE_ENDIAN);

        return this;
    }

    /**
     * return term offset field
     *
     * @return term offset field
     */
    public int termOffset() {
        return getInt(TERM_OFFSET_FIELD_OFFSET, LITTLE_ENDIAN);
    }

    /**
     * set term offset field
     *
     * @param termOffset field value
     * @return flyweight
     */
    public NakFlyweight termOffset(final int termOffset) {
        putInt(TERM_OFFSET_FIELD_OFFSET, termOffset, LITTLE_ENDIAN);

        return this;
    }

    /**
     * The length field
     *
     * @return length field
     */
    public int length() {
        return getInt(LENGTH_FIELD_OFFSET, LITTLE_ENDIAN);
    }

    /**
     * set length field
     *
     * @param length field value
     * @return flyweight
     */
    public NakFlyweight length(final int length) {
        putInt(LENGTH_FIELD_OFFSET, length, LITTLE_ENDIAN);

        return this;
    }
}
