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
package uk.co.real_logic.aeron.driver;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.stubbing.Answer;
import uk.co.real_logic.aeron.logbuffer.HeaderWriter;
import uk.co.real_logic.aeron.driver.buffer.RawLog;
import uk.co.real_logic.aeron.driver.cmd.NewPublicationCmd;
import uk.co.real_logic.aeron.driver.cmd.SenderCmd;
import uk.co.real_logic.aeron.driver.event.EventLogger;
import uk.co.real_logic.aeron.driver.media.*;
import uk.co.real_logic.aeron.logbuffer.LogBufferDescriptor;
import uk.co.real_logic.aeron.logbuffer.TermAppender;
import uk.co.real_logic.aeron.protocol.DataHeaderFlyweight;
import uk.co.real_logic.aeron.protocol.HeaderFlyweight;
import uk.co.real_logic.aeron.protocol.SetupFlyweight;
import uk.co.real_logic.agrona.concurrent.AtomicCounter;
import uk.co.real_logic.agrona.concurrent.OneToOneConcurrentArrayQueue;
import uk.co.real_logic.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.agrona.concurrent.status.AtomicLongPosition;
import uk.co.real_logic.agrona.concurrent.status.Position;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.aeron.logbuffer.FrameDescriptor.FRAME_ALIGNMENT;
import static uk.co.real_logic.aeron.logbuffer.LogBufferDescriptor.TERM_META_DATA_LENGTH;
import static uk.co.real_logic.agrona.BitUtil.align;

public class SenderTest {
    private static final int TERM_BUFFER_LENGTH = LogBufferDescriptor.TERM_MIN_LENGTH;
    private static final int MAX_FRAME_LENGTH = 1024;
    private static final int SESSION_ID = 1;
    private static final int STREAM_ID = 2;
    private static final int INITIAL_TERM_ID = 3;
    private static final byte[] PAYLOAD = "Payload is here!".getBytes();

    private static final UnsafeBuffer HEADER =
            DataHeaderFlyweight.createDefaultHeader(SESSION_ID, STREAM_ID, INITIAL_TERM_ID);
    private static final int ALIGNED_FRAME_LENGTH = align(HEADER.capacity() + PAYLOAD.length, FRAME_ALIGNMENT);

    private final EventLogger mockLogger = mock(EventLogger.class);
    private final ControlTransportPoller mockTransportPoller = mock(ControlTransportPoller.class);

    private final RawLog rawLog = LogBufferHelper.newTestLogBuffers(TERM_BUFFER_LENGTH, TERM_META_DATA_LENGTH);

    private TermAppender[] termAppenders;
    private NetworkPublication publication;
    private Sender sender;

    private final FlowControl flowControl = spy(new UnicastFlowControl());
    private final RetransmitHandler mockRetransmitHandler = mock(RetransmitHandler.class);

    private long currentTimestamp = 0;

    private final Queue<ByteBuffer> receivedFrames = new ArrayDeque<>();

    private final UdpChannel udpChannel = UdpChannel.parse("udp://localhost:40123");
    private final InetSocketAddress rcvAddress = udpChannel.remoteData();
    private final DataHeaderFlyweight dataHeader = new DataHeaderFlyweight();
    private final SetupFlyweight setupHeader = new SetupFlyweight();
    private final SystemCounters mockSystemCounters = mock(SystemCounters.class);
    private final OneToOneConcurrentArrayQueue<SenderCmd> senderCommandQueue = new OneToOneConcurrentArrayQueue<>(1024);

    private final HeaderWriter headerWriter = new HeaderWriter(HEADER);

    private Answer<Integer> saveByteBufferAnswer =
            (invocation) ->
            {
                final Object args[] = invocation.getArguments();
                final ByteBuffer buffer = (ByteBuffer) args[0];

                final int length = buffer.limit() - buffer.position();
                receivedFrames.add(ByteBuffer.allocateDirect(length).put(buffer));

                // we don't pass on the args, so don't reset buffer.position() back
                return length;
            };

    @Before
    public void setUp() throws Exception {
        final SendChannelEndpoint mockSendChannelEndpoint = mock(SendChannelEndpoint.class);
        when(mockSendChannelEndpoint.udpChannel()).thenReturn(udpChannel);
        when(mockSendChannelEndpoint.send(anyObject())).thenAnswer(saveByteBufferAnswer);
        when(mockSystemCounters.heartbeatsSent()).thenReturn(mock(AtomicCounter.class));
        when(mockSystemCounters.bytesSent()).thenReturn(mock(AtomicCounter.class));
        when(mockSystemCounters.senderFlowControlLimits()).thenReturn(mock(AtomicCounter.class));

        sender = new Sender(
                new MediaDriver.Context()
                        .senderTransportPoller(mockTransportPoller)
                        .systemCounters(mockSystemCounters)
                        .senderCommandQueue(senderCommandQueue)
                        .eventLogger(mockLogger)
                        .nanoClock(() -> currentTimestamp));

        final UnsafeBuffer termMetaData = rawLog.partitions()[0].metaDataBuffer();
        LogBufferDescriptor.initialiseTailWithTermId(termMetaData, INITIAL_TERM_ID);

        termAppenders = rawLog
                .stream()
                .map((log) -> new TermAppender(log.termBuffer(), log.metaDataBuffer()))
                .toArray(TermAppender[]::new);

        publication = new NetworkPublication(
                mockSendChannelEndpoint,
                () -> currentTimestamp,
                rawLog,
                new AtomicLongPosition(),
                mock(Position.class),
                SESSION_ID,
                STREAM_ID,
                INITIAL_TERM_ID,
                MAX_FRAME_LENGTH,
                mockSystemCounters,
                flowControl,
                mockRetransmitHandler);

        senderCommandQueue.offer(new NewPublicationCmd(publication));
    }

    @After
    public void tearDown() throws Exception {
        sender.onClose();
    }

    @Test
    public void shouldSendSetupFrameOnChannelWhenTimeoutWithoutStatusMessage() throws Exception {
        sender.doWork();
        assertThat(receivedFrames.size(), is(1));
        currentTimestamp += Configuration.PUBLICATION_SETUP_TIMEOUT_NS - 1;
        sender.doWork();
        assertThat(receivedFrames.size(), is(1));
        currentTimestamp += 10;
        sender.doWork();
        assertThat(receivedFrames.size(), is(2));

        setupHeader.wrap(new UnsafeBuffer(receivedFrames.remove()));
        assertThat(setupHeader.frameLength(), is(SetupFlyweight.HEADER_LENGTH));
        assertThat(setupHeader.initialTermId(), is(INITIAL_TERM_ID));
        assertThat(setupHeader.activeTermId(), is(INITIAL_TERM_ID));
        assertThat(setupHeader.streamId(), is(STREAM_ID));
        assertThat(setupHeader.sessionId(), is(SESSION_ID));
        assertThat(setupHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_SETUP));
        assertThat(setupHeader.flags(), is((short) 0));
        assertThat(setupHeader.version(), is((short) HeaderFlyweight.CURRENT_VERSION));
    }

    @Test
    public void shouldSendMultipleSetupFramesOnChannelWhenTimeoutWithoutStatusMessage() throws Exception {
        sender.doWork();
        assertThat(receivedFrames.size(), is(1));

        currentTimestamp += Configuration.PUBLICATION_SETUP_TIMEOUT_NS - 1;
        sender.doWork();
        currentTimestamp += 10;
        sender.doWork();

        assertThat(receivedFrames.size(), is(2));
    }

    @Test
    public void shouldNotSendSetupFrameAfterReceivingStatusMessage() throws Exception {
        publication.senderPositionLimit(flowControl.onStatusMessage(INITIAL_TERM_ID, 0, 0, rcvAddress));
        sender.doWork();
        assertThat(receivedFrames.size(), is(1));
        receivedFrames.remove();

        currentTimestamp += Configuration.PUBLICATION_SETUP_TIMEOUT_NS + 10;
        sender.doWork();

        assertThat(receivedFrames.size(), is(1));
        dataHeader.wrap(receivedFrames.remove());
        assertThat(dataHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_DATA)); // heartbeat
        assertThat(dataHeader.frameLength(), is(0));
        assertThat(dataHeader.termOffset(), is(offsetOfMessage(1)));
    }

    @Test
    public void shouldSendSetupFrameAfterReceivingStatusMessageWithSetupBit() throws Exception {
        publication.senderPositionLimit(
                flowControl.onStatusMessage(INITIAL_TERM_ID, 0, ALIGNED_FRAME_LENGTH, rcvAddress));

        final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(PAYLOAD.length));
        buffer.putBytes(0, PAYLOAD);

        termAppenders[0].appendUnfragmentedMessage(headerWriter, buffer, 0, PAYLOAD.length);
        sender.doWork();

        assertThat(receivedFrames.size(), is(2)); // setup then data
        receivedFrames.remove();
        receivedFrames.remove();

        publication.triggerSendSetupFrame();

        sender.doWork();
        assertThat(receivedFrames.size(), is(0)); // setup has been sent already, have to wait

        currentTimestamp += Configuration.PUBLICATION_SETUP_TIMEOUT_NS + 10;

        sender.doWork();

        assertThat(receivedFrames.size(), is(1));

        setupHeader.wrap(new UnsafeBuffer(receivedFrames.remove()));
        assertThat(setupHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_SETUP));
    }

    @Test
    public void shouldBeAbleToSendOnChannel() throws Exception {
        publication.senderPositionLimit(
                flowControl.onStatusMessage(INITIAL_TERM_ID, 0, ALIGNED_FRAME_LENGTH, rcvAddress));

        final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(PAYLOAD.length));
        buffer.putBytes(0, PAYLOAD);

        termAppenders[0].appendUnfragmentedMessage(headerWriter, buffer, 0, PAYLOAD.length);
        sender.doWork();

        assertThat(receivedFrames.size(), is(2));
        setupHeader.wrap(new UnsafeBuffer(receivedFrames.remove()));
        assertThat(setupHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_SETUP));

        dataHeader.wrap(new UnsafeBuffer(receivedFrames.remove()));
        assertThat(dataHeader.frameLength(), is(ALIGNED_FRAME_LENGTH));
        assertThat(dataHeader.termId(), is(INITIAL_TERM_ID));
        assertThat(dataHeader.streamId(), is(STREAM_ID));
        assertThat(dataHeader.sessionId(), is(SESSION_ID));
        assertThat(dataHeader.termOffset(), is(offsetOfMessage(1)));
        assertThat(dataHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_DATA));
        assertThat(dataHeader.flags(), is(DataHeaderFlyweight.BEGIN_AND_END_FLAGS));
        assertThat(dataHeader.version(), is((short) HeaderFlyweight.CURRENT_VERSION));
    }

    @Test
    public void shouldBeAbleToSendOnChannelTwice() throws Exception {
        publication.senderPositionLimit(
                flowControl.onStatusMessage(INITIAL_TERM_ID, 0, (2 * ALIGNED_FRAME_LENGTH), rcvAddress));

        final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(PAYLOAD.length));
        buffer.putBytes(0, PAYLOAD);

        termAppenders[0].appendUnfragmentedMessage(headerWriter, buffer, 0, PAYLOAD.length);
        sender.doWork();
        termAppenders[0].appendUnfragmentedMessage(headerWriter, buffer, 0, PAYLOAD.length);
        sender.doWork();

        assertThat(receivedFrames.size(), is(3));

        setupHeader.wrap(new UnsafeBuffer(receivedFrames.remove()));
        assertThat(setupHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_SETUP));

        dataHeader.wrap(new UnsafeBuffer(receivedFrames.remove()));
        assertThat(dataHeader.frameLength(), is(ALIGNED_FRAME_LENGTH));
        assertThat(dataHeader.termId(), is(INITIAL_TERM_ID));
        assertThat(dataHeader.streamId(), is(STREAM_ID));
        assertThat(dataHeader.sessionId(), is(SESSION_ID));
        assertThat(dataHeader.termOffset(), is(offsetOfMessage(1)));
        assertThat(dataHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_DATA));
        assertThat(dataHeader.flags(), is(DataHeaderFlyweight.BEGIN_AND_END_FLAGS));
        assertThat(dataHeader.version(), is((short) HeaderFlyweight.CURRENT_VERSION));

        dataHeader.wrap(new UnsafeBuffer(receivedFrames.remove()));
        assertThat(dataHeader.frameLength(), is(ALIGNED_FRAME_LENGTH));
        assertThat(dataHeader.termId(), is(INITIAL_TERM_ID));
        assertThat(dataHeader.streamId(), is(STREAM_ID));
        assertThat(dataHeader.sessionId(), is(SESSION_ID));
        assertThat(dataHeader.termOffset(), is(offsetOfMessage(2)));
        assertThat(dataHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_DATA));
        assertThat(dataHeader.flags(), is(DataHeaderFlyweight.BEGIN_AND_END_FLAGS));
        assertThat(dataHeader.version(), is((short) HeaderFlyweight.CURRENT_VERSION));
    }

    @Test
    public void shouldNotSendUntilStatusMessageReceived() throws Exception {
        final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(PAYLOAD.length));
        buffer.putBytes(0, PAYLOAD);
        termAppenders[0].appendUnfragmentedMessage(headerWriter, buffer, 0, PAYLOAD.length);

        sender.doWork();
        assertThat(receivedFrames.size(), is(1));
        setupHeader.wrap(receivedFrames.remove());
        assertThat(setupHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_SETUP));

        publication.senderPositionLimit(
                flowControl.onStatusMessage(INITIAL_TERM_ID, 0, ALIGNED_FRAME_LENGTH, rcvAddress));
        sender.doWork();

        assertThat(receivedFrames.size(), is(1));

        dataHeader.wrap(new UnsafeBuffer(receivedFrames.remove()));

        assertThat(dataHeader.frameLength(), is(ALIGNED_FRAME_LENGTH));
        assertThat(dataHeader.termId(), is(INITIAL_TERM_ID));
        assertThat(dataHeader.streamId(), is(STREAM_ID));
        assertThat(dataHeader.sessionId(), is(SESSION_ID));
        assertThat(dataHeader.termOffset(), is(offsetOfMessage(1)));
        assertThat(dataHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_DATA));
        assertThat(dataHeader.flags(), is(DataHeaderFlyweight.BEGIN_AND_END_FLAGS));
        assertThat(dataHeader.version(), is((short) HeaderFlyweight.CURRENT_VERSION));
    }

    @Test
    public void shouldNotBeAbleToSendAfterUsingUpYourWindow() throws Exception {
        final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(PAYLOAD.length));
        buffer.putBytes(0, PAYLOAD);
        termAppenders[0].appendUnfragmentedMessage(headerWriter, buffer, 0, PAYLOAD.length);
        publication.senderPositionLimit(
                flowControl.onStatusMessage(INITIAL_TERM_ID, 0, ALIGNED_FRAME_LENGTH, rcvAddress));

        sender.doWork();

        assertThat(receivedFrames.size(), is(2));
        receivedFrames.remove();                   // skip setup

        dataHeader.wrap(new UnsafeBuffer(receivedFrames.remove()));
        assertThat(dataHeader.frameLength(), is(ALIGNED_FRAME_LENGTH));
        assertThat(dataHeader.termId(), is(INITIAL_TERM_ID));
        assertThat(dataHeader.streamId(), is(STREAM_ID));
        assertThat(dataHeader.sessionId(), is(SESSION_ID));
        assertThat(dataHeader.termOffset(), is(offsetOfMessage(1)));
        assertThat(dataHeader.headerType(), is(HeaderFlyweight.HDR_TYPE_DATA));
        assertThat(dataHeader.flags(), is(DataHeaderFlyweight.BEGIN_AND_END_FLAGS));
        assertThat(dataHeader.version(), is((short) HeaderFlyweight.CURRENT_VERSION));

        termAppenders[0].appendUnfragmentedMessage(headerWriter, buffer, 0, PAYLOAD.length);
        sender.doWork();

        assertThat(receivedFrames.size(), is(0));
    }

    @Test
    public void shouldSendLastDataFrameAsHeartbeatWhenIdle() throws Exception {
        publication.senderPositionLimit(
                flowControl.onStatusMessage(INITIAL_TERM_ID, 0, ALIGNED_FRAME_LENGTH, rcvAddress));

        final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(PAYLOAD.length));
        buffer.putBytes(0, PAYLOAD);

        termAppenders[0].appendUnfragmentedMessage(headerWriter, buffer, 0, PAYLOAD.length);
        sender.doWork();

        assertThat(receivedFrames.size(), is(2));  // should send ticks
        receivedFrames.remove();                   // skip setup & data frame
        receivedFrames.remove();

        currentTimestamp += Configuration.PUBLICATION_HEARTBEAT_TIMEOUT_NS - 1;
        sender.doWork();

        assertThat(receivedFrames.size(), is(0));  // should not send yet
        currentTimestamp += 10;
        sender.doWork();

        assertThat(receivedFrames.size(), greaterThanOrEqualTo(1));  // should send ticks

        dataHeader.wrap(receivedFrames.remove());
        assertThat(dataHeader.frameLength(), is(0));
        assertThat(dataHeader.termOffset(), is(offsetOfMessage(2)));
    }

    @Test
    public void shouldSendMultipleDataFramesAsHeartbeatsWhenIdle() {
        publication.senderPositionLimit(
                flowControl.onStatusMessage(INITIAL_TERM_ID, 0, ALIGNED_FRAME_LENGTH, rcvAddress));

        final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(PAYLOAD.length));
        buffer.putBytes(0, PAYLOAD);

        termAppenders[0].appendUnfragmentedMessage(headerWriter, buffer, 0, PAYLOAD.length);
        sender.doWork();

        assertThat(receivedFrames.size(), is(2));  // should send ticks
        receivedFrames.remove();
        receivedFrames.remove();                   // skip setup & data frame

        currentTimestamp += Configuration.PUBLICATION_HEARTBEAT_TIMEOUT_NS - 1;
        sender.doWork();
        assertThat(receivedFrames.size(), is(0));  // should not send yet
        currentTimestamp += 10;
        sender.doWork();
        assertThat(receivedFrames.size(), greaterThanOrEqualTo(1));  // should send ticks

        dataHeader.wrap(receivedFrames.remove());
        assertThat(dataHeader.frameLength(), is(0));
        assertThat(dataHeader.termOffset(), is(offsetOfMessage(2)));

        currentTimestamp += Configuration.PUBLICATION_HEARTBEAT_TIMEOUT_NS - 1;
        sender.doWork();
        assertThat(receivedFrames.size(), is(0));  // should not send yet
        currentTimestamp += 10;
        sender.doWork();
        assertThat(receivedFrames.size(), greaterThanOrEqualTo(1));  // should send ticks

        dataHeader.wrap(receivedFrames.remove());
        assertThat(dataHeader.frameLength(), is(0));
        assertThat(dataHeader.termOffset(), is(offsetOfMessage(2)));
    }

    private int offsetOfMessage(final int offset) {
        return (offset - 1) * align(HEADER.capacity() + PAYLOAD.length, FRAME_ALIGNMENT);
    }
}
