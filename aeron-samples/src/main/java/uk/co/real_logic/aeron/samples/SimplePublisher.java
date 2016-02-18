/*
 * Copyright 2015 Kaazing Corporation
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
package uk.co.real_logic.aeron.samples;

import uk.co.real_logic.aeron.Aeron;
import uk.co.real_logic.aeron.Publication;
import uk.co.real_logic.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;

/**
 * A very simple Aeron publisher application
 * Publishes a fixed size message on a fixed channel and stream. Upon completion
 * of message send, it lingers for 5 seconds before exiting.
 */
public class SimplePublisher {
    public static void main(final String[] args) throws Exception {
        // Allocate enough buffer size to hold maximum message length
        // The UnsafeBuffer class is part of the Agrona library and is used for efficient buffer management
        final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(512));

        // The channel (an endpoint identifier) to send the message to
        final String channel = "udp://localhost:40123";

        // A unique identifier for a stream within a channel. Stream ID 0 is reserved
        // for internal use and should not be used by applications.
        final int streamId = 10;

        System.out.println("Publishing to " + channel + " on stream Id " + streamId);

        // Create a context, needed for client connection to media driver
        // A separate media driver process needs to be running prior to starting this application
        final Aeron.Context ctx = new Aeron.Context();

        // Create an Aeron instance with client-provided context configuration and connect to the
        // media driver, and create a Publication.  The Aeron and Publication classes implement
        // AutoCloseable, and will automatically clean up resources when this try block is finished.
        try (final Aeron aeron = Aeron.connect(ctx);
             final Publication publication = aeron.addPublication(channel, streamId)) {
            final String message = "Hello World! ";
            final byte[] messageBytes = message.getBytes();
            buffer.putBytes(0, messageBytes);

            // Try to publish the buffer. 'offer' is a non-blocking call.
            // If it returns less than 0, the message was not sent, and the offer should be retried.
            final long result = publication.offer(buffer, 0, messageBytes.length);

            if (result < 0L) {
                if (result == Publication.BACK_PRESSURED) {
                    System.out.println(" Offer failed due to back pressure");
                } else if (result == Publication.NOT_CONNECTED) {
                    System.out.println(" Offer failed because publisher is not yet connected to subscriber");
                } else if (result == Publication.ADMIN_ACTION) {
                    System.out.println("Offer failed because of an administration action in the system");
                } else if (result == Publication.CLOSED) {
                    System.out.println("Offer failed publication is closed");
                } else {
                    System.out.println(" Offer failed due to unknown reason");
                }
            } else {
                System.out.println(" yay !!");
            }

            System.out.println("Done sending.");
        }
    }
}
