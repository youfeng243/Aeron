/*
 * Copyright 2016 Real Logic Ltd.
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
package uk.co.real_logic.aeron.driver;

import uk.co.real_logic.aeron.driver.media.ReceiveChannelEndpoint;
import uk.co.real_logic.aeron.driver.media.UdpChannel;
import uk.co.real_logic.agrona.concurrent.UnsafeBuffer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

/**
 * Debug implementation which can record transmission frames to the {@link MediaDriver.Context#eventLogger()} and introduce
 * loss via {@link MediaDriver.Context#controlLossGenerator()} and {@link MediaDriver.Context#dataLossGenerator()} .
 */
public class DebugReceiveChannelEndpoint extends ReceiveChannelEndpoint {
    private final LossGenerator dataLossGenerator;
    private final LossGenerator controlLossGenerator;

    public DebugReceiveChannelEndpoint(
            final UdpChannel udpChannel, final DataPacketDispatcher dispatcher, final MediaDriver.Context context) {
        super(udpChannel, dispatcher, context);

        dataLossGenerator = context.dataLossGenerator();
        controlLossGenerator = context.controlLossGenerator();
    }

    public int sendTo(final ByteBuffer buffer, final InetSocketAddress remoteAddress) {
        logger.logFrameOut(buffer, remoteAddress);

        // TODO: call controlLossGenerator and drop (call log to inform) - need a shouldDropAllFrame() method

        return super.sendTo(buffer, remoteAddress);
    }

    protected int dispatch(final UnsafeBuffer buffer, final int length, final InetSocketAddress srcAddress) {
        int result = 0;

        if (dataLossGenerator.shouldDropFrame(srcAddress, buffer, length)) {
            logger.logFrameInDropped(receiveByteBuffer, 0, length, srcAddress);
        } else {
            logger.logFrameIn(receiveByteBuffer, 0, length, srcAddress);

            result = super.dispatch(buffer, length, srcAddress);
        }

        return result;
    }
}
