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
package uk.co.real_logic.aeron.command;

import uk.co.real_logic.aeron.ErrorCode;
import uk.co.real_logic.agrona.BitUtil;
import uk.co.real_logic.agrona.MutableDirectBuffer;

import java.nio.ByteOrder;

/**
 * Control message flyweight for any errors sent from driver to clients
 *
 *
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |              Offending Command Correlation ID                 |
 * |                                                               |
 * +---------------------------------------------------------------+
 * |                         Error Code                            |
 * +---------------------------------------------------------------+
 * |                   Error Message Length                        |
 * +---------------------------------------------------------------+
 * |                       Error Message                          ...
 * ...                                                             |
 * +---------------------------------------------------------------+
 */
public class ErrorResponseFlyweight {
    private static final int OFFENDING_COMMAND_CORRELATION_ID_OFFSET = 0;
    private static final int ERROR_CODE_OFFSET = OFFENDING_COMMAND_CORRELATION_ID_OFFSET + BitUtil.SIZE_OF_LONG;
    private static final int ERROR_MESSAGE_OFFSET = ERROR_CODE_OFFSET + BitUtil.SIZE_OF_INT;

    private MutableDirectBuffer buffer;
    private int offset;

    /**
     * Wrap the buffer at a given offset for updates.
     *
     * @param buffer to wrap
     * @param offset at which the message begins.
     * @return for fluent API
     */
    public final ErrorResponseFlyweight wrap(final MutableDirectBuffer buffer, final int offset) {
        this.buffer = buffer;
        this.offset = offset;

        return this;
    }

    /**
     * Return correlation ID of the offending command.
     *
     * @return correlation ID of the offending command
     */
    public long offendingCommandCorrelationId() {
        return buffer.getLong(offset + OFFENDING_COMMAND_CORRELATION_ID_OFFSET);
    }

    /**
     * Set correlation ID of the offending command.
     *
     * @param correlationId of the offending command
     * @return flyweight
     */
    public ErrorResponseFlyweight offendingCommandCorrelationId(final long correlationId) {
        buffer.putLong(offset + OFFENDING_COMMAND_CORRELATION_ID_OFFSET, correlationId);
        return this;
    }

    /**
     * Error code for the command.
     *
     * @return error code for the command
     */
    public ErrorCode errorCode() {
        return ErrorCode.get(buffer.getInt(offset + ERROR_CODE_OFFSET));
    }

    /**
     * Set the error code for the command.
     *
     * @param code for the error
     * @return flyweight
     */
    public ErrorResponseFlyweight errorCode(final ErrorCode code) {
        buffer.putInt(offset + ERROR_CODE_OFFSET, code.value());
        return this;
    }

    /**
     * Error message associated with the error.
     *
     * @return error message
     */
    public String errorMessage() {
        return buffer.getStringUtf8(offset + ERROR_MESSAGE_OFFSET, ByteOrder.nativeOrder());
    }

    /**
     * Set the error message
     *
     * @param message to associate with the error
     * @return flyweight
     */
    public ErrorResponseFlyweight errorMessage(final String message) {
        buffer.putStringUtf8(offset + ERROR_MESSAGE_OFFSET, message, ByteOrder.nativeOrder());
        return this;
    }

    /**
     * Length of the error response in bytes.
     *
     * @return length of the error response
     */
    public int length() {
        return buffer.getInt(offset + ERROR_MESSAGE_OFFSET) + ERROR_MESSAGE_OFFSET + BitUtil.SIZE_OF_INT;
    }
}
