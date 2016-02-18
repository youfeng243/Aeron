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

import uk.co.real_logic.agrona.concurrent.status.Position;

/**
 * Consumption position a subscriber has got to within a {@link SubscriptionLink}.
 */
public final class SubscriberPosition {
    private final SubscriptionLink subscription;
    private final Position position;

    public SubscriberPosition(final SubscriptionLink subscription, final Position position) {
        this.subscription = subscription;
        this.position = position;
    }

    public Position position() {
        return position;
    }

    public int positionCounterId() {
        return position().id();
    }

    public SubscriptionLink subscription() {
        return subscription;
    }
}
