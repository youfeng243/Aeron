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

import uk.co.real_logic.aeron.CncFileDescriptor;
import uk.co.real_logic.aeron.CommonContext;
import uk.co.real_logic.aeron.driver.buffer.RawLogFactory;
import uk.co.real_logic.aeron.driver.cmd.DriverConductorCmd;
import uk.co.real_logic.aeron.driver.cmd.ReceiverCmd;
import uk.co.real_logic.aeron.driver.cmd.SenderCmd;
import uk.co.real_logic.aeron.driver.event.EventConfiguration;
import uk.co.real_logic.aeron.driver.event.EventLogger;
import uk.co.real_logic.aeron.driver.exceptions.ActiveDriverException;
import uk.co.real_logic.aeron.driver.exceptions.ConfigurationException;
import uk.co.real_logic.aeron.driver.media.*;
import uk.co.real_logic.agrona.ErrorHandler;
import uk.co.real_logic.agrona.IoUtil;
import uk.co.real_logic.agrona.LangUtil;
import uk.co.real_logic.agrona.concurrent.*;
import uk.co.real_logic.agrona.concurrent.broadcast.BroadcastTransmitter;
import uk.co.real_logic.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import uk.co.real_logic.agrona.concurrent.ringbuffer.RingBuffer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.StandardSocketOptions;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.lang.Boolean.getBoolean;
import static uk.co.real_logic.aeron.driver.Configuration.*;
import static uk.co.real_logic.agrona.IoUtil.mapNewFile;

/**
 * Main class for JVM-based media driver
 *
 * Usage:
 * <code>
 * $ java -jar aeron-driver.jar
 * $ java -Doption=value -jar aeron-driver.jar
 * </code>
 *
 * {@link Configuration}
 */
public final class MediaDriver implements AutoCloseable {
    /**
     * Attempt to delete directories on start if they exist
     */
    public static final String DIRS_DELETE_ON_START_PROP_NAME = "aeron.dir.delete.on.start";

    private final List<AgentRunner> runners;
    private final Context ctx;

    /**
     * Load system properties from a given filename or url.
     *
     * File is first searched in resources, then file system, then URL. All are loaded if multiples found.
     *
     * @param filenameOrUrl that holds properties
     */
    public static void loadPropertiesFile(final String filenameOrUrl) {
        final Properties properties = new Properties(System.getProperties());

        try (final InputStream inputStream = MediaDriver.class.getClassLoader().getResourceAsStream(filenameOrUrl)) {
            properties.load(inputStream);
        } catch (final Exception ignore) {
        }

        try (final FileInputStream inputStream = new FileInputStream(filenameOrUrl)) {
            properties.load(inputStream);
        } catch (final Exception ignore) {
        }

        try (final InputStream inputStream = new URL(filenameOrUrl).openStream()) {
            properties.load(inputStream);
        } catch (final Exception ignore) {
        }

        System.setProperties(properties);
    }

    /**
     * Start Media Driver as a stand-alone process.
     *
     * @param args command line arguments
     * @throws Exception if an error occurs
     */
    public static void main(final String[] args) throws Exception {
        if (1 == args.length) {
            loadPropertiesFile(args[0]);
        }

        try (final MediaDriver ignored = MediaDriver.launch()) {
            new SigIntBarrier().await();

            System.out.println("Shutdown Driver...");
        }
    }

    /**
     * Construct a media driver with the given context.
     *
     * @param context for the media driver parameters
     */
    private MediaDriver(final Context context) {
        this.ctx = context;

        ensureDirectoryIsRecreated(context);

        validateSufficientSocketBufferLengths(context);

        context
                .toConductorFromReceiverCommandQueue(new OneToOneConcurrentArrayQueue<>(CMD_QUEUE_CAPACITY))
                .toConductorFromSenderCommandQueue(new OneToOneConcurrentArrayQueue<>(CMD_QUEUE_CAPACITY))
                .receiverCommandQueue(new OneToOneConcurrentArrayQueue<>(CMD_QUEUE_CAPACITY))
                .senderCommandQueue(new OneToOneConcurrentArrayQueue<>(CMD_QUEUE_CAPACITY))
                .conclude();

        final Receiver receiver = new Receiver(context);
        final Sender sender = new Sender(context);
        final DriverConductor conductor = new DriverConductor(context);

        context.receiverProxy().receiver(receiver);
        context.senderProxy().sender(sender);
        context.fromReceiverDriverConductorProxy().driverConductor(conductor);
        context.fromSenderDriverConductorProxy().driverConductor(conductor);
        context.toDriverCommands().consumerHeartbeatTime(context.epochClock().time());

        final AtomicCounter errorCounter = context.systemCounters().errors();
        final ErrorHandler errorHandler = context.errorHandler();

        switch (context.threadingMode) {
            case SHARED:
                runners = Collections.singletonList(
                        new AgentRunner(
                                context.sharedIdleStrategy,
                                errorHandler,
                                errorCounter,
                                new CompositeAgent(sender, receiver, conductor))
                );
                break;

            case SHARED_NETWORK:
                runners = Arrays.asList(
                        new AgentRunner(
                                context.sharedNetworkIdleStrategy,
                                errorHandler,
                                errorCounter,
                                new CompositeAgent(sender, receiver)),
                        new AgentRunner(context.conductorIdleStrategy, errorHandler, errorCounter, conductor)
                );
                break;

            default:
            case DEDICATED:
                runners = Arrays.asList(
                        new AgentRunner(context.senderIdleStrategy, errorHandler, errorCounter, sender),
                        new AgentRunner(context.receiverIdleStrategy, errorHandler, errorCounter, receiver),
                        new AgentRunner(context.conductorIdleStrategy, errorHandler, errorCounter, conductor)
                );
        }
    }

    /**
     * Launch an isolated MediaDriver embedded in the current process with a generated aeronDirectoryName that can be retrieved
     * by calling aeronDirectoryName.
     *
     * @return the newly started MediaDriver.
     */
    public static MediaDriver launchEmbedded() {
        return launchEmbedded(new Context());
    }

    /**
     * Launch an isolated MediaDriver embedded in the current process with a provided configuration context and a generated
     * aeronDirectoryName (overwrites configured aeronDirectoryName) that can be retrieved by calling aeronDirectoryName.
     *
     * If the aeronDirectoryName is configured then it will be used.
     *
     * @param context containing the configuration options.
     * @return the newly started MediaDriver.
     */
    public static MediaDriver launchEmbedded(final Context context) {
        if (CommonContext.AERON_DIR_PROP_DEFAULT.equals(context.aeronDirectoryName())) {
            context.aeronDirectoryName(CommonContext.generateRandomDirName());
        }

        return launch(context);
    }

    /**
     * Launch a MediaDriver embedded in the current process with default configuration.
     *
     * @return the newly started MediaDriver.
     */
    public static MediaDriver launch() {
        return launch(new Context());
    }

    /**
     * Launch a MediaDriver embedded in the current process and provided a configuration context.
     *
     * @param context containing the configuration options.
     * @return the newly created MediaDriver.
     */
    public static MediaDriver launch(final Context context) {
        return new MediaDriver(context).start();
    }

    /**
     * Shutdown the media driver by stopping all threads and freeing resources.
     */
    public void close() {
        try {
            runners.forEach(AgentRunner::close);

            freeSocketsForReuseOnWindows();
            ctx.close();
        } catch (final Exception ex) {
            LangUtil.rethrowUnchecked(ex);
        }
    }

    /**
     * Used to access the configured aeronDirectoryName for this MediaDriver, typically used after the
     * {@link #launchEmbedded()} method is used.
     *
     * @return the context aeronDirectoryName
     */
    public String aeronDirectoryName() {
        return ctx.aeronDirectoryName();
    }

    private void freeSocketsForReuseOnWindows() {
        ctx.receiverTransportPoller().selectNowWithoutProcessing();
        ctx.senderTransportPoller().selectNowWithoutProcessing();
    }

    private MediaDriver start() {
        runners.forEach(
                (runner) ->
                {
                    final Thread thread = new Thread(runner);
                    thread.setName(runner.agent().roleName());
                    thread.start();
                });

        return this;
    }

    private static void validateSufficientSocketBufferLengths(final Context ctx) {
        try (final DatagramChannel probe = DatagramChannel.open()) {
            final int defaultSoSndBuf = probe.getOption(StandardSocketOptions.SO_SNDBUF);

            probe.setOption(StandardSocketOptions.SO_SNDBUF, Integer.MAX_VALUE);
            final int maxSoSndBuf = probe.getOption(StandardSocketOptions.SO_SNDBUF);

            if (maxSoSndBuf < Configuration.SOCKET_SNDBUF_LENGTH) {
                System.err.format(
                        "WARNING: Could not get desired SO_SNDBUF, adjust OS buffer to match %s: attempted=%d, actual=%d\n",
                        Configuration.SOCKET_SNDBUF_LENGTH_PROP_NAME,
                        Configuration.SOCKET_SNDBUF_LENGTH,
                        maxSoSndBuf);
            }

            probe.setOption(StandardSocketOptions.SO_RCVBUF, Integer.MAX_VALUE);
            final int maxSoRcvBuf = probe.getOption(StandardSocketOptions.SO_RCVBUF);

            if (maxSoRcvBuf < Configuration.SOCKET_RCVBUF_LENGTH) {
                System.err.format(
                        "WARNING: Could not get desired SO_RCVBUF, adjust OS buffer to match %s: attempted=%d, actual=%d\n",
                        Configuration.SOCKET_RCVBUF_LENGTH_PROP_NAME,
                        Configuration.SOCKET_RCVBUF_LENGTH,
                        maxSoRcvBuf);
            }

            final int soSndBuf =
                    0 == Configuration.SOCKET_SNDBUF_LENGTH ? defaultSoSndBuf : Configuration.SOCKET_SNDBUF_LENGTH;

            if (ctx.mtuLength() > soSndBuf) {
                throw new ConfigurationException(String.format(
                        "MTU greater than socket SO_SNDBUF, adjust %s to match MTU: mtuLength=%d, SO_SNDBUF=%d",
                        Configuration.SOCKET_SNDBUF_LENGTH_PROP_NAME,
                        ctx.mtuLength(),
                        soSndBuf));
            }
        } catch (final IOException ex) {
            throw new RuntimeException(String.format("probe socket: %s", ex.toString()), ex);
        }
    }

    private void ensureDirectoryIsRecreated(final Context ctx) {
        final File aeronDir = new File(ctx.aeronDirectoryName());

        if (aeronDir.exists()) {
            final Consumer<String> logProgress;
            if (ctx.warnIfDirectoriesExist()) {
                System.err.println("WARNING: " + aeronDir + " already exists.");
                logProgress = System.err::println;
            } else {
                logProgress = (message) -> {
                };
            }

            if (ctx.dirsDeleteOnStart()) {
                ctx.deleteAeronDirectory();
            } else {
                final boolean driverActive = ctx.isDriverActive(ctx.driverTimeoutMs(), logProgress);

                if (driverActive) {
                    throw new ActiveDriverException("active driver detected");
                }

                ctx.deleteAeronDirectory();
            }
        }

        final BiConsumer<String, String> callback =
                (path, name) ->
                {
                    if (ctx.warnIfDirectoriesExist()) {
                        System.err.println("WARNING: " + name + " directory already exists: " + path);
                    }
                };

        IoUtil.ensureDirectoryIsRecreated(aeronDir, "aeron", callback);
    }

    public static class Context extends CommonContext {
        private RawLogFactory rawLogFactory;
        private DataTransportPoller receiverTransportPoller;
        private ControlTransportPoller senderTransportPoller;
        private Supplier<FlowControl> unicastSenderFlowControlSupplier;
        private Supplier<FlowControl> multicastSenderFlowControlSupplier;
        private EpochClock epochClock;
        private NanoClock nanoClock;
        private OneToOneConcurrentArrayQueue<DriverConductorCmd> toConductorFromReceiverCommandQueue;
        private OneToOneConcurrentArrayQueue<DriverConductorCmd> toConductorFromSenderCommandQueue;
        private OneToOneConcurrentArrayQueue<ReceiverCmd> receiverCommandQueue;
        private OneToOneConcurrentArrayQueue<SenderCmd> senderCommandQueue;
        private ReceiverProxy receiverProxy;
        private SenderProxy senderProxy;
        private DriverConductorProxy fromReceiverDriverConductorProxy;
        private DriverConductorProxy fromSenderDriverConductorProxy;
        private IdleStrategy conductorIdleStrategy;
        private IdleStrategy senderIdleStrategy;
        private IdleStrategy receiverIdleStrategy;
        private IdleStrategy sharedNetworkIdleStrategy;
        private IdleStrategy sharedIdleStrategy;
        private ClientProxy clientProxy;
        private RingBuffer toDriverCommands;
        private RingBuffer toEventReader;

        private MappedByteBuffer cncByteBuffer;
        private UnsafeBuffer cncMetaDataBuffer;

        private CountersManager countersManager;
        private SystemCounters systemCounters;

        private long imageLivenessTimeoutNs = IMAGE_LIVENESS_TIMEOUT_NS;
        private long clientLivenessTimeoutNs = CLIENT_LIVENESS_TIMEOUT_NS;
        private long publicationUnblockTimeoutNs = PUBLICATION_UNBLOCK_TIMEOUT_NS;

        private int publicationTermBufferLength;
        private int ipcPublicationTermBufferLength;
        private int maxImageTermBufferLength;
        private int initialWindowLength;
        private int eventBufferLength;
        private long statusMessageTimeout;
        private long dataLossSeed;
        private long controlLossSeed;
        private double dataLossRate;
        private double controlLossRate;
        private int mtuLength;

        private boolean warnIfDirectoriesExist;
        private EventLogger eventLogger;
        private Consumer<String> eventConsumer;
        private ThreadingMode threadingMode;
        private boolean dirsDeleteOnStart;

        private LossGenerator dataLossGenerator;
        private LossGenerator controlLossGenerator;

        private SendChannelEndpointSupplier sendChannelEndpointSupplier;
        private ReceiveChannelEndpointSupplier receiveChannelEndpointSupplier;

        public Context() {
            termBufferLength(Configuration.termBufferLength());
            termBufferMaxLength(Configuration.termBufferLengthMax());
            initialWindowLength(Configuration.initialWindowLength());
            statusMessageTimeout(Configuration.statusMessageTimeout());
            dataLossRate(Configuration.dataLossRate());
            dataLossSeed(Configuration.dataLossSeed());
            controlLossRate(Configuration.controlLossRate());
            controlLossSeed(Configuration.controlLossSeed());
            mtuLength(Configuration.MTU_LENGTH);

            eventBufferLength = EventConfiguration.bufferLength();

            warnIfDirectoriesExist = true;

            dirsDeleteOnStart(getBoolean(DIRS_DELETE_ON_START_PROP_NAME));
        }

        public Context conclude() {
            super.conclude();

            try {
                concludeNullProperties();

                receiverTransportPoller(new DataTransportPoller());
                senderTransportPoller(new ControlTransportPoller());

                Configuration.validateTermBufferLength(termBufferLength());
                Configuration.validateInitialWindowLength(initialWindowLength(), mtuLength());

                cncByteBuffer = mapNewFile(
                        cncFile(),
                        CncFileDescriptor.computeCncFileLength(
                                CONDUCTOR_BUFFER_LENGTH + TO_CLIENTS_BUFFER_LENGTH +
                                        COUNTER_LABELS_BUFFER_LENGTH + COUNTER_VALUES_BUFFER_LENGTH));

                cncMetaDataBuffer = CncFileDescriptor.createMetaDataBuffer(cncByteBuffer);
                CncFileDescriptor.fillMetaData(
                        cncMetaDataBuffer,
                        CONDUCTOR_BUFFER_LENGTH,
                        TO_CLIENTS_BUFFER_LENGTH,
                        COUNTER_LABELS_BUFFER_LENGTH,
                        COUNTER_VALUES_BUFFER_LENGTH,
                        clientLivenessTimeoutNs);

                final BroadcastTransmitter transmitter =
                        new BroadcastTransmitter(CncFileDescriptor.createToClientsBuffer(cncByteBuffer, cncMetaDataBuffer));

                clientProxy(new ClientProxy(transmitter, eventLogger));

                toDriverCommands(
                        new ManyToOneRingBuffer(CncFileDescriptor.createToDriverBuffer(cncByteBuffer, cncMetaDataBuffer)));

                concludeCounters();

                receiverProxy(new ReceiverProxy(
                        threadingMode, receiverCommandQueue(), systemCounters.receiverProxyFails()));
                senderProxy(new SenderProxy(threadingMode, senderCommandQueue(), systemCounters.senderProxyFails()));
                fromReceiverDriverConductorProxy(new DriverConductorProxy(
                        threadingMode, toConductorFromReceiverCommandQueue, systemCounters.conductorProxyFails()));
                fromSenderDriverConductorProxy(new DriverConductorProxy(
                        threadingMode, toConductorFromSenderCommandQueue, systemCounters.conductorProxyFails()));

                rawLogBuffersFactory(new RawLogFactory(aeronDirectoryName(),
                        publicationTermBufferLength, maxImageTermBufferLength, ipcPublicationTermBufferLength, eventLogger));

                concludeIdleStrategies();
                concludeLossGenerators();
            } catch (final Exception ex) {
                LangUtil.rethrowUnchecked(ex);
            }

            return this;
        }

        private void concludeNullProperties() {
            if (null == epochClock) {
                epochClock = new SystemEpochClock();
            }

            if (null == nanoClock) {
                nanoClock = new SystemNanoClock();
            }

            if (threadingMode == null) {
                threadingMode = Configuration.threadingMode();
            }

            final ByteBuffer eventByteBuffer = ByteBuffer.allocateDirect(eventBufferLength);

            if (null == eventLogger) {
                eventLogger = new EventLogger(eventByteBuffer);
            }

            if (null == eventConsumer) {
                eventConsumer = System.out::println;
            }

            toEventReader(new ManyToOneRingBuffer(new UnsafeBuffer(eventByteBuffer)));

            if (null == unicastSenderFlowControlSupplier) {
                unicastSenderFlowControlSupplier = Configuration::unicastFlowControlStrategy;
            }

            if (null == multicastSenderFlowControlSupplier) {
                multicastSenderFlowControlSupplier = Configuration::multicastFlowControlStrategy;
            }

            if (0 == ipcPublicationTermBufferLength) {
                ipcPublicationTermBufferLength = Configuration.ipcTermBufferLength(termBufferLength());
            }

            if (null == sendChannelEndpointSupplier) {
                sendChannelEndpointSupplier = Configuration.sendChannelEndpointSupplier();
            }

            if (null == receiveChannelEndpointSupplier) {
                receiveChannelEndpointSupplier = Configuration.receiveChannelEndpointSupplier();
            }
        }

        public Context epochClock(final EpochClock clock) {
            this.epochClock = clock;
            return this;
        }

        public Context nanoClock(final NanoClock clock) {
            this.nanoClock = clock;
            return this;
        }

        public Context toConductorFromReceiverCommandQueue(
                final OneToOneConcurrentArrayQueue<DriverConductorCmd> conductorCommandQueue) {
            this.toConductorFromReceiverCommandQueue = conductorCommandQueue;
            return this;
        }

        public Context toConductorFromSenderCommandQueue(
                final OneToOneConcurrentArrayQueue<DriverConductorCmd> conductorCommandQueue) {
            this.toConductorFromSenderCommandQueue = conductorCommandQueue;
            return this;
        }

        public Context rawLogBuffersFactory(final RawLogFactory rawLogFactory) {
            this.rawLogFactory = rawLogFactory;
            return this;
        }

        public Context receiverTransportPoller(final DataTransportPoller transportPoller) {
            this.receiverTransportPoller = transportPoller;
            return this;
        }

        public Context senderTransportPoller(final ControlTransportPoller transportPoller) {
            this.senderTransportPoller = transportPoller;
            return this;
        }

        public Context unicastSenderFlowControlSupplier(final Supplier<FlowControl> senderFlowControl) {
            this.unicastSenderFlowControlSupplier = senderFlowControl;
            return this;
        }

        public Context multicastSenderFlowControlSupplier(final Supplier<FlowControl> senderFlowControl) {
            this.multicastSenderFlowControlSupplier = senderFlowControl;
            return this;
        }

        public Context receiverCommandQueue(final OneToOneConcurrentArrayQueue<ReceiverCmd> receiverCommandQueue) {
            this.receiverCommandQueue = receiverCommandQueue;
            return this;
        }

        public Context senderCommandQueue(final OneToOneConcurrentArrayQueue<SenderCmd> senderCommandQueue) {
            this.senderCommandQueue = senderCommandQueue;
            return this;
        }

        public Context receiverProxy(final ReceiverProxy receiverProxy) {
            this.receiverProxy = receiverProxy;
            return this;
        }

        public Context senderProxy(final SenderProxy senderProxy) {
            this.senderProxy = senderProxy;
            return this;
        }

        public Context fromReceiverDriverConductorProxy(final DriverConductorProxy driverConductorProxy) {
            this.fromReceiverDriverConductorProxy = driverConductorProxy;
            return this;
        }

        public Context fromSenderDriverConductorProxy(final DriverConductorProxy driverConductorProxy) {
            this.fromSenderDriverConductorProxy = driverConductorProxy;
            return this;
        }

        public Context conductorIdleStrategy(final IdleStrategy strategy) {
            this.conductorIdleStrategy = strategy;
            return this;
        }

        public Context senderIdleStrategy(final IdleStrategy strategy) {
            this.senderIdleStrategy = strategy;
            return this;
        }

        public Context receiverIdleStrategy(final IdleStrategy strategy) {
            this.receiverIdleStrategy = strategy;
            return this;
        }

        public Context sharedNetworkIdleStrategy(final IdleStrategy strategy) {
            this.sharedNetworkIdleStrategy = strategy;
            return this;
        }

        public Context sharedIdleStrategy(final IdleStrategy strategy) {
            this.sharedIdleStrategy = strategy;
            return this;
        }

        public Context clientProxy(final ClientProxy clientProxy) {
            this.clientProxy = clientProxy;
            return this;
        }

        public Context toDriverCommands(final RingBuffer toDriverCommands) {
            this.toDriverCommands = toDriverCommands;
            return this;
        }

        public Context countersManager(final CountersManager countersManager) {
            this.countersManager = countersManager;
            return this;
        }

        public Context termBufferLength(final int termBufferLength) {
            this.publicationTermBufferLength = termBufferLength;
            return this;
        }

        public Context termBufferMaxLength(final int termBufferMaxLength) {
            this.maxImageTermBufferLength = termBufferMaxLength;
            return this;
        }

        public Context ipcTermBufferLength(final int ipcTermBufferLength) {
            this.ipcPublicationTermBufferLength = ipcTermBufferLength;
            return this;
        }

        public Context initialWindowLength(final int initialWindowLength) {
            this.initialWindowLength = initialWindowLength;
            return this;
        }

        public Context statusMessageTimeout(final long statusMessageTimeout) {
            this.statusMessageTimeout = statusMessageTimeout;
            return this;
        }

        public Context warnIfDirectoriesExist(final boolean value) {
            this.warnIfDirectoriesExist = value;
            return this;
        }

        public Context eventConsumer(final Consumer<String> value) {
            this.eventConsumer = value;
            return this;
        }

        public Context eventLogger(final EventLogger value) {
            this.eventLogger = value;
            return this;
        }

        public Context toEventReader(final RingBuffer toEventReader) {
            this.toEventReader = toEventReader;
            return this;
        }

        public Context imageLivenessTimeoutNs(final long timeout) {
            this.imageLivenessTimeoutNs = timeout;
            return this;
        }

        public Context clientLivenessTimeoutNs(final long timeout) {
            this.clientLivenessTimeoutNs = timeout;
            return this;
        }

        public Context publicationUnblockTimeoutNs(final long timeout) {
            this.publicationUnblockTimeoutNs = timeout;
            return this;
        }

        public Context eventBufferLength(final int length) {
            this.eventBufferLength = length;
            return this;
        }

        public Context dataLossRate(final double lossRate) {
            this.dataLossRate = lossRate;
            return this;
        }

        public Context dataLossSeed(final long lossSeed) {
            this.dataLossSeed = lossSeed;
            return this;
        }

        public Context controlLossRate(final double lossRate) {
            this.controlLossRate = lossRate;
            return this;
        }

        public Context controlLossSeed(final long lossSeed) {
            this.controlLossSeed = lossSeed;
            return this;
        }

        public Context systemCounters(final SystemCounters systemCounters) {
            this.systemCounters = systemCounters;
            return this;
        }

        public Context threadingMode(final ThreadingMode threadingMode) {
            this.threadingMode = threadingMode;
            return this;
        }

        public Context dataLossGenerator(final LossGenerator generator) {
            this.dataLossGenerator = generator;
            return this;
        }

        public Context controlLossGenerator(final LossGenerator generator) {
            this.controlLossGenerator = generator;
            return this;
        }

        /**
         * Set whether or not this application will attempt to delete the Aeron directories when starting.
         *
         * @param dirsDeleteOnStart Attempt deletion.
         * @return this Object for method chaining.
         */
        public Context dirsDeleteOnStart(final boolean dirsDeleteOnStart) {
            this.dirsDeleteOnStart = dirsDeleteOnStart;
            return this;
        }

        /**
         * @see CommonContext#aeronDirectoryName(String)
         */
        public Context aeronDirectoryName(String dirName) {
            super.aeronDirectoryName(dirName);
            return this;
        }

        public Context sendChannelEndpointSupplier(final SendChannelEndpointSupplier supplier) {
            this.sendChannelEndpointSupplier = supplier;
            return this;
        }

        public Context receiveChannelEndpointSupplier(final ReceiveChannelEndpointSupplier supplier) {
            this.receiveChannelEndpointSupplier = supplier;
            return this;
        }

        public EpochClock epochClock() {
            return epochClock;
        }

        public NanoClock nanoClock() {
            return nanoClock;
        }

        public OneToOneConcurrentArrayQueue<DriverConductorCmd> toConductorFromReceiverCommandQueue() {
            return toConductorFromReceiverCommandQueue;
        }

        public OneToOneConcurrentArrayQueue<DriverConductorCmd> toConductorFromSenderCommandQueue() {
            return toConductorFromSenderCommandQueue;
        }

        public RawLogFactory rawLogBuffersFactory() {
            return rawLogFactory;
        }

        public DataTransportPoller receiverTransportPoller() {
            return receiverTransportPoller;
        }

        public ControlTransportPoller senderTransportPoller() {
            return senderTransportPoller;
        }

        public Supplier<FlowControl> unicastSenderFlowControlSupplier() {
            return unicastSenderFlowControlSupplier;
        }

        public Supplier<FlowControl> multicastSenderFlowControlSupplier() {
            return multicastSenderFlowControlSupplier;
        }

        public OneToOneConcurrentArrayQueue<ReceiverCmd> receiverCommandQueue() {
            return receiverCommandQueue;
        }

        public OneToOneConcurrentArrayQueue<SenderCmd> senderCommandQueue() {
            return senderCommandQueue;
        }

        public ReceiverProxy receiverProxy() {
            return receiverProxy;
        }

        public SenderProxy senderProxy() {
            return senderProxy;
        }

        public DriverConductorProxy fromReceiverDriverConductorProxy() {
            return fromReceiverDriverConductorProxy;
        }

        public DriverConductorProxy fromSenderDriverConductorProxy() {
            return fromSenderDriverConductorProxy;
        }

        public IdleStrategy conductorIdleStrategy() {
            return conductorIdleStrategy;
        }

        public IdleStrategy senderIdleStrategy() {
            return senderIdleStrategy;
        }

        public IdleStrategy receiverIdleStrategy() {
            return receiverIdleStrategy;
        }

        public IdleStrategy sharedNetworkIdleStrategy() {
            return sharedNetworkIdleStrategy;
        }

        public IdleStrategy sharedIdleStrategy() {
            return sharedIdleStrategy;
        }

        public ClientProxy clientProxy() {
            return clientProxy;
        }

        public RingBuffer toDriverCommands() {
            return toDriverCommands;
        }

        public CountersManager countersManager() {
            return countersManager;
        }

        public long imageLivenessTimeoutNs() {
            return imageLivenessTimeoutNs;
        }

        public long clientLivenessTimeoutNs() {
            return clientLivenessTimeoutNs;
        }

        public long publicationUnblockTimeoutNs() {
            return publicationUnblockTimeoutNs;
        }

        public int termBufferLength() {
            return publicationTermBufferLength;
        }

        public int termBufferMaxLength() {
            return maxImageTermBufferLength;
        }

        public int ipcTermBufferLength() {
            return ipcPublicationTermBufferLength;
        }

        public int initialWindowLength() {
            return initialWindowLength;
        }

        public long statusMessageTimeout() {
            return statusMessageTimeout;
        }

        public boolean warnIfDirectoriesExist() {
            return warnIfDirectoriesExist;
        }

        public EventLogger eventLogger() {
            return eventLogger;
        }

        public ErrorHandler errorHandler() {
            return eventLogger::logException;
        }

        public double dataLossRate() {
            return dataLossRate;
        }

        public long dataLossSeed() {
            return dataLossSeed;
        }

        public double controlLossRate() {
            return controlLossRate;
        }

        public long controlLossSeed() {
            return controlLossSeed;
        }

        public int mtuLength() {
            return mtuLength;
        }

        public LossGenerator dataLossGenerator() {
            return dataLossGenerator;
        }

        public LossGenerator controlLossGenerator() {
            return controlLossGenerator;
        }

        public CommonContext mtuLength(final int mtuLength) {
            this.mtuLength = mtuLength;
            return this;
        }

        public SystemCounters systemCounters() {
            return systemCounters;
        }

        /**
         * Get whether or not this application will attempt to delete the Aeron directories when starting.
         *
         * @return true when directories will be deleted, otherwise false.
         */
        public boolean dirsDeleteOnStart() {
            return dirsDeleteOnStart;
        }

        public Consumer<String> eventConsumer() {
            return eventConsumer;
        }

        public int eventBufferLength() {
            return eventBufferLength;
        }

        public RingBuffer toEventReader() {
            return toEventReader;
        }

        public SendChannelEndpointSupplier sendChannelEndpointSupplier() {
            return sendChannelEndpointSupplier;
        }

        public ReceiveChannelEndpointSupplier receiveChannelEndpointSupplier() {
            return receiveChannelEndpointSupplier;
        }

        public void close() {
            // do not close the systemsCounters so that all counters are kept as is.
            IoUtil.unmap(cncByteBuffer);

            super.close();
        }

        private void concludeCounters() {
            if (countersManager() == null) {
                if (counterLabelsBuffer() == null) {
                    counterLabelsBuffer(CncFileDescriptor.createCounterLabelsBuffer(cncByteBuffer, cncMetaDataBuffer));
                }

                if (counterValuesBuffer() == null) {
                    counterValuesBuffer(CncFileDescriptor.createCounterValuesBuffer(cncByteBuffer, cncMetaDataBuffer));
                }

                countersManager(new CountersManager(counterLabelsBuffer(), counterValuesBuffer()));
            }

            if (null == systemCounters) {
                systemCounters = new SystemCounters(countersManager);
            }
        }

        private void concludeIdleStrategies() {
            if (null == conductorIdleStrategy) {
                conductorIdleStrategy(Configuration.conductorIdleStrategy());
            }

            if (null == senderIdleStrategy) {
                senderIdleStrategy(Configuration.senderIdleStrategy());
            }

            if (null == receiverIdleStrategy) {
                receiverIdleStrategy(Configuration.receiverIdleStrategy());
            }

            if (null == sharedNetworkIdleStrategy) {
                sharedNetworkIdleStrategy(Configuration.sharedNetworkIdleStrategy());
            }

            if (null == sharedIdleStrategy) {
                sharedIdleStrategy(Configuration.sharedIdleStrategy());
            }
        }

        private void concludeLossGenerators() {
            if (null == dataLossGenerator) {
                dataLossGenerator(Configuration.createLossGenerator(dataLossRate, dataLossSeed));
            }

            if (null == controlLossGenerator) {
                controlLossGenerator(Configuration.createLossGenerator(controlLossRate, controlLossSeed));
            }
        }
    }
}
