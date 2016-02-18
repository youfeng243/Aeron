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

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Assume;
import org.junit.Test;
import uk.co.real_logic.aeron.driver.exceptions.InvalidChannelException;
import uk.co.real_logic.aeron.driver.media.UdpChannel;
import uk.co.real_logic.agrona.BitUtil;
import uk.co.real_logic.agrona.LangUtil;

import java.net.*;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class UdpChannelTest {
    @Test
    public void shouldHandleExplicitLocalAddressAndPortFormat() throws Exception {
        final UdpChannel udpChannel = UdpChannel.parse("udp://localhost:40123@localhost:40124");

        assertThat(udpChannel.localData(), is(new InetSocketAddress("localhost", 40123)));
        assertThat(udpChannel.localControl(), is(new InetSocketAddress("localhost", 40123)));
        assertThat(udpChannel.remoteData(), is(new InetSocketAddress("localhost", 40124)));
        assertThat(udpChannel.remoteControl(), is(new InetSocketAddress("localhost", 40124)));
    }

    @Test
    public void shouldHandleExplicitLocalAddressAndPortFormatWithAeronUri() throws Exception {
        final UdpChannel udpChannel = UdpChannel.parse("aeron:udp?local=localhost:40123|remote=localhost:40124");

        assertThat(udpChannel.localData(), is(new InetSocketAddress("localhost", 40123)));
        assertThat(udpChannel.localControl(), is(new InetSocketAddress("localhost", 40123)));
        assertThat(udpChannel.remoteData(), is(new InetSocketAddress("localhost", 40124)));
        assertThat(udpChannel.remoteControl(), is(new InetSocketAddress("localhost", 40124)));
    }

    @Test
    public void shouldHandleImpliedLocalAddressAndPortFormat() throws Exception {
        final UdpChannel udpChannel = UdpChannel.parse("udp://localhost:40124");

        assertThat(udpChannel.localData(), is(new InetSocketAddress("0.0.0.0", 0)));
        assertThat(udpChannel.localControl(), is(new InetSocketAddress("0.0.0.0", 0)));
        assertThat(udpChannel.remoteData(), is(new InetSocketAddress("localhost", 40124)));
        assertThat(udpChannel.remoteControl(), is(new InetSocketAddress("localhost", 40124)));
    }

    @Test
    public void shouldHandleImpliedLocalAddressAndPortFormatWithAeronUri() throws Exception {
        final UdpChannel udpChannel = UdpChannel.parse("aeron:udp?remote=localhost:40124");

        assertThat(udpChannel.localData(), is(new InetSocketAddress("0.0.0.0", 0)));
        assertThat(udpChannel.localControl(), is(new InetSocketAddress("0.0.0.0", 0)));
        assertThat(udpChannel.remoteData(), is(new InetSocketAddress("localhost", 40124)));
        assertThat(udpChannel.remoteControl(), is(new InetSocketAddress("localhost", 40124)));
    }

    @Test(expected = InvalidChannelException.class)
    public void shouldThrowExceptionForIncorrectScheme() throws Exception {
        UdpChannel.parse("unknownudp://localhost:40124");
    }

    @Test(expected = InvalidChannelException.class)
    public void shouldThrowExceptionForMissingAddress() throws Exception {
        UdpChannel.parse("udp://");
    }

    @Test(expected = InvalidChannelException.class)
    public void shouldThrowExceptionForMissingAddressWithAeronUri() throws Exception {
        UdpChannel.parse("aeron:udp");
    }

    @Test(expected = InvalidChannelException.class)
    public void shouldThrowExceptionOnEvenMulticastAddress() throws Exception {
        UdpChannel.parse("udp://224.10.9.8");
    }

    @Test
    public void shouldParseValidMulticastAddress() throws Exception {
        final UdpChannel udpChannel = UdpChannel.parse("udp://localhost@224.10.9.9:40124");

        assertThat(udpChannel.localControl(), is(new InetSocketAddress("localhost", 0)));
        assertThat(udpChannel.remoteControl(), isMulticastAddress("224.10.9.10", 40124));
        assertThat(udpChannel.localData(), is(new InetSocketAddress("localhost", 0)));
        assertThat(udpChannel.remoteData(), isMulticastAddress("224.10.9.9", 40124));
        assertThat(udpChannel.localInterface(), is(NetworkInterface.getByInetAddress(InetAddress.getByName("localhost"))));
    }

    @Test
    public void shouldParseValidMulticastAddressWithAeronUri() throws Exception {
        final UdpChannel udpChannel = UdpChannel.parse("aeron:udp?interface=localhost|group=224.10.9.9:40124");

        assertThat(udpChannel.localControl(), is(new InetSocketAddress("localhost", 0)));
        assertThat(udpChannel.remoteControl(), isMulticastAddress("224.10.9.10", 40124));
        assertThat(udpChannel.localData(), is(new InetSocketAddress("localhost", 0)));
        assertThat(udpChannel.remoteData(), isMulticastAddress("224.10.9.9", 40124));
        assertThat(udpChannel.localInterface(), is(NetworkInterface.getByInetAddress(InetAddress.getByName("localhost"))));
    }

    private Matcher<InetSocketAddress> isMulticastAddress(final String addressName, final int port) throws UnknownHostException {
        final InetAddress inetAddress = InetAddress.getByName(addressName);
        return is(new InetSocketAddress(inetAddress, port));
    }

    @Test
    public void shouldHandleImpliedLocalPortFormat() throws Exception {
        final UdpChannel udpChannel = UdpChannel.parse("udp://localhost@localhost:40124");

        assertThat(udpChannel.localData(), is(new InetSocketAddress("localhost", 0)));
        assertThat(udpChannel.localControl(), is(new InetSocketAddress("localhost", 0)));
        assertThat(udpChannel.remoteData(), is(new InetSocketAddress("localhost", 40124)));
        assertThat(udpChannel.remoteControl(), is(new InetSocketAddress("localhost", 40124)));
    }

    @Test
    public void shouldHandleImpliedLocalPortFormatWithAeronUri() throws Exception {
        final UdpChannel udpChannel = UdpChannel.parse("aeron:udp?local=localhost|remote=localhost:40124");

        assertThat(udpChannel.localData(), is(new InetSocketAddress("localhost", 0)));
        assertThat(udpChannel.localControl(), is(new InetSocketAddress("localhost", 0)));
        assertThat(udpChannel.remoteData(), is(new InetSocketAddress("localhost", 40124)));
        assertThat(udpChannel.remoteControl(), is(new InetSocketAddress("localhost", 40124)));
    }

    @Test
    public void shouldHandleLocalhostLookup() throws Exception {
        final UdpChannel udpChannel = UdpChannel.parse("udp://localhost:40124");

        assertThat(udpChannel.remoteData(), is(new InetSocketAddress("127.0.0.1", 40124)));
        assertThat(udpChannel.remoteControl(), is(new InetSocketAddress("127.0.0.1", 40124)));
    }

    @Test
    public void shouldHandleLocalhostLookupWithAeronUri() throws Exception {
        final UdpChannel udpChannel = UdpChannel.parse("aeron:udp?remote=localhost:40124");

        assertThat(udpChannel.remoteData(), is(new InetSocketAddress("127.0.0.1", 40124)));
        assertThat(udpChannel.remoteControl(), is(new InetSocketAddress("127.0.0.1", 40124)));
    }

    @Test
    public void shouldHandleBeingUsedAsMapKey() throws Exception {
        final UdpChannel udpChannel1 = UdpChannel.parse("udp://localhost:40124");
        final UdpChannel udpChannel2 = UdpChannel.parse("udp://localhost:40124");

        final Map<UdpChannel, Integer> map = new HashMap<>();

        map.put(udpChannel1, 1);
        assertThat(map.get(udpChannel2), is(1));
    }

    @Test(expected = InvalidChannelException.class)
    public void shouldThrowExceptionWhenNoPortSpecified() throws Exception {
        UdpChannel.parse("udp://localhost");
    }

    @Test
    public void shouldHandleCanonicalFormForUnicastCorrectly() throws Exception {
        final UdpChannel udpChannel = UdpChannel.parse("udp://192.168.0.1:40456");
        final UdpChannel udpChannelLocal = UdpChannel.parse("udp://127.0.0.1@192.168.0.1:40456");
        final UdpChannel udpChannelLocalPort = UdpChannel.parse("udp://127.0.0.1:40455@192.168.0.1:40456");
        final UdpChannel udpChannelLocalhost = UdpChannel.parse("udp://localhost@localhost:40456");

        assertThat(udpChannel.canonicalForm(), is("UDP-00000000-0-c0a80001-40456"));
        assertThat(udpChannelLocal.canonicalForm(), is("UDP-7f000001-0-c0a80001-40456"));
        assertThat(udpChannelLocalPort.canonicalForm(), is("UDP-7f000001-40455-c0a80001-40456"));
        assertThat(udpChannelLocalhost.canonicalForm(), is("UDP-7f000001-0-7f000001-40456"));
    }

    @Test
    public void shouldHandleIpV6CanonicalFormForUnicastCorrectly() throws Exception {
        final UdpChannel udpChannelLocal = UdpChannel.parse("aeron:udp?local=[::1]|remote=192.168.0.1:40456");
        final UdpChannel udpChannelLocalPort =
                UdpChannel.parse("aeron:udp?local=127.0.0.1:40455|remote=[fe80::5246:5dff:fe73:df06]:40456");

        assertThat(udpChannelLocal.canonicalForm(), is("UDP-00000000000000000000000000000001-0-c0a80001-40456"));
        assertThat(udpChannelLocalPort.canonicalForm(), is("UDP-7f000001-40455-fe8000000000000052465dfffe73df06-40456"));
    }

    @Test
    public void shouldHandleCanonicalFormForUnicastCorrectlyWithAeronUri() throws Exception {
        final UdpChannel udpChannel = UdpChannel.parse("aeron:udp?remote=192.168.0.1:40456");
        final UdpChannel udpChannelLocal = UdpChannel.parse("aeron:udp?local=127.0.0.1|remote=192.168.0.1:40456");
        final UdpChannel udpChannelLocalPort = UdpChannel.parse("aeron:udp?local=127.0.0.1:40455|remote=192.168.0.1:40456");
        final UdpChannel udpChannelLocalhost = UdpChannel.parse("aeron:udp?local=localhost|remote=localhost:40456");

        assertThat(udpChannel.canonicalForm(), is("UDP-00000000-0-c0a80001-40456"));
        assertThat(udpChannelLocal.canonicalForm(), is("UDP-7f000001-0-c0a80001-40456"));
        assertThat(udpChannelLocalPort.canonicalForm(), is("UDP-7f000001-40455-c0a80001-40456"));
        assertThat(udpChannelLocalhost.canonicalForm(), is("UDP-7f000001-0-7f000001-40456"));
    }

    @Test
    public void shouldGetProtocolFamilyForIpV4() throws Exception {
        final UdpChannel udpChannel = UdpChannel.parse("aeron:udp?local=127.0.0.1|remote=127.0.0.1:12345");
        assertThat(udpChannel.protocolFamily(), is((ProtocolFamily) StandardProtocolFamily.INET));
    }

    @Test
    public void shouldGetProtocolFamilyForIpV6() throws Exception {
        final UdpChannel udpChannel = UdpChannel.parse("aeron:udp?local=[::1]|remote=[::1]:12345");
        assertThat(udpChannel.protocolFamily(), is((ProtocolFamily) StandardProtocolFamily.INET6));
    }

    @Test
    public void shouldGetProtocolFamilyForIpV4WithoutLocalSpecified() throws Exception {
        final UdpChannel udpChannel = UdpChannel.parse("aeron:udp?remote=127.0.0.1:12345");
        assertThat(udpChannel.protocolFamily(), is((ProtocolFamily) StandardProtocolFamily.INET));
    }

    @Test
    public void shouldGetProtocolFamilyForIpV6WithoutLocalSpecified() throws Exception {
        final UdpChannel udpChannel = UdpChannel.parse("aeron:udp?remote=[::1]:12345");
        assertThat(udpChannel.protocolFamily(), is((ProtocolFamily) StandardProtocolFamily.INET6));
    }

    @Test
    public void shouldHandleCanonicalFormWithNsLookup() throws Exception {
        final String localhostIpAsHex = resolveToHexAddress("localhost");

        final UdpChannel udpChannelExampleCom0 = UdpChannel.parse("aeron:udp?remote=localhost:40456");
        assertThat(udpChannelExampleCom0.canonicalForm(), is("UDP-00000000-0-" + localhostIpAsHex + "-40456"));

        final UdpChannel udpChannelExampleCom1 = UdpChannel.parse("udp://localhost:40456");
        assertThat(udpChannelExampleCom1.canonicalForm(), is("UDP-00000000-0-" + localhostIpAsHex + "-40456"));
    }

    @Test
    public void shouldHandleCanonicalFormForMulticastWithLocalPort() throws Exception {
        final UdpChannel udpChannelLocalPort = UdpChannel.parse("udp://127.0.0.1:40455@224.0.1.1:40456");
        assertThat(udpChannelLocalPort.canonicalForm(), is("UDP-7f000001-40455-e0000101-40456"));

        final UdpChannel udpChannelSubnetLocalPort = UdpChannel.parse("udp://127.0.0.0:40455@224.0.1.1:40456?subnetPrefix=24");
        assertThat(udpChannelSubnetLocalPort.canonicalForm(), is("UDP-7f000001-40455-e0000101-40456"));
    }

    @Test
    public void shouldHandleCanonicalFormForMulticastCorrectly() throws Exception {
        final UdpChannel udpChannel = UdpChannel.parse("udp://localhost@224.0.1.1:40456");
        final UdpChannel udpChannelLocal = UdpChannel.parse("udp://127.0.0.1@224.0.1.1:40456");
        final UdpChannel udpChannelAllSystems = UdpChannel.parse("udp://localhost@224.0.0.1:40456");
        final UdpChannel udpChannelDefault = UdpChannel.parse("udp://224.0.1.1:40456");

        final UdpChannel udpChannelSubnet = UdpChannel.parse("udp://localhost@224.0.1.1:40456?subnetPrefix=24");
        final UdpChannel udpChannelSubnetLocal = UdpChannel.parse("udp://127.0.0.0@224.0.1.1:40456?subnetPrefix=24");

        assertThat(udpChannel.canonicalForm(), is("UDP-7f000001-0-e0000101-40456"));
        assertThat(udpChannelLocal.canonicalForm(), is("UDP-7f000001-0-e0000101-40456"));
        assertThat(udpChannelAllSystems.canonicalForm(), is("UDP-7f000001-0-e0000001-40456"));
        assertThat(udpChannelSubnet.canonicalForm(), is("UDP-7f000001-0-e0000101-40456"));
        assertThat(udpChannelSubnetLocal.canonicalForm(), is("UDP-7f000001-0-e0000101-40456"));

        assertThat(udpChannelDefault.localInterface(), supportsMulticastOrIsLoopback());
    }

    @Test
    public void shouldHandleCanonicalFormForMulticastCorrectlyWithAeronUri() throws Exception {
        final UdpChannel udpChannel = UdpChannel.parse("aeron:udp?interface=localhost|group=224.0.1.1:40456");
        final UdpChannel udpChannelLocal = UdpChannel.parse("aeron:udp?interface=127.0.0.1|group=224.0.1.1:40456");
        final UdpChannel udpChannelAllSystems =
                UdpChannel.parse("aeron:udp?interface=localhost|group=224.0.0.1:40456");
        final UdpChannel udpChannelDefault = UdpChannel.parse("aeron:udp?group=224.0.1.1:40456");
        final UdpChannel udpChannelSubnet = UdpChannel.parse("aeron:udp?interface=localhost/24|group=224.0.1.1:40456");
        final UdpChannel udpChannelSubnetLocal = UdpChannel.parse("aeron:udp?interface=127.0.0.0/24|group=224.0.1.1:40456");

        assertThat(udpChannel.canonicalForm(), is("UDP-7f000001-0-e0000101-40456"));
        assertThat(udpChannelLocal.canonicalForm(), is("UDP-7f000001-0-e0000101-40456"));
        assertThat(udpChannelAllSystems.canonicalForm(), is("UDP-7f000001-0-e0000001-40456"));
        assertThat(udpChannelSubnet.canonicalForm(), is("UDP-7f000001-0-e0000101-40456"));
        assertThat(udpChannelSubnetLocal.canonicalForm(), is("UDP-7f000001-0-e0000101-40456"));
        assertThat(udpChannelDefault.localInterface(), supportsMulticastOrIsLoopback());
    }

    @Test
    public void shouldHandleIpV6CanonicalFormForMulticastCorrectly() throws Exception {
        Assume.assumeTrue(System.getProperty("java.net.preferIPv4Stack") == null);

        final UdpChannel udpChannel = UdpChannel.parse("aeron:udp?interface=localhost|group=[FF01::FD]:40456");
        final UdpChannel udpChannelLocal = UdpChannel.parse("aeron:udp?interface=[::1]:54321/64|group=224.0.1.1:40456");

        assertThat(udpChannel.canonicalForm(), is("UDP-7f000001-0-ff0100000000000000000000000000fd-40456"));
        assertThat(udpChannelLocal.canonicalForm(), is("UDP-00000000000000000000000000000001-54321-e0000101-40456"));
    }

    @Test(expected = InvalidChannelException.class)
    public void shouldFailIfBothUnicastAndMulticastSpecified() throws Exception {
        UdpChannel.parse("aeron:udp?group=224.0.1.1:40456|remote=192.168.0.1:40456");
    }

    private static Matcher<NetworkInterface> supportsMulticastOrIsLoopback() {
        return new TypeSafeMatcher<NetworkInterface>() {
            public void describeTo(Description description) {
                description.appendText("Interface supports multicast or is loopack");
            }

            protected boolean matchesSafely(final NetworkInterface item) {
                boolean matchesSafely = false;
                try {
                    matchesSafely = item.supportsMulticast() || item.isLoopback();
                } catch (final SocketException e) {
                    LangUtil.rethrowUnchecked(e);
                }

                return matchesSafely;
            }
        };
    }

    private String resolveToHexAddress(final String host) throws UnknownHostException {
        return BitUtil.toHex(InetAddress.getByName(host).getAddress());
    }
}
