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

import org.junit.Test;
import uk.co.real_logic.agrona.LangUtil;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.*;
import java.util.*;

import static java.lang.Short.parseShort;
import static java.net.InetAddress.getByName;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.junit.Assert.*;
import static uk.co.real_logic.aeron.driver.NetworkUtil.filterBySubnet;

public class NetworkUtilTest {
    @Test
    public void shouldNotMatchIfLengthsAreDifferent() {
        assertFalse(NetworkUtil.isMatchWithPrefix(new byte[0], new byte[3], 0));
        assertFalse(NetworkUtil.isMatchWithPrefix(new byte[1], new byte[2], 0));
        assertFalse(NetworkUtil.isMatchWithPrefix(new byte[5], new byte[5000], 0));
    }

    @Test
    public void shouldMatchIfAllBytesMatch() throws Exception {
        final byte[] a = {'a', 'b', 'c', 'd'};
        final byte[] b = {'a', 'b', 'c', 'd'};
        assertTrue(NetworkUtil.isMatchWithPrefix(a, b, 32));
    }

    @Test
    public void shouldMatchIfAllBytesWithPrefixMatch() throws Exception {
        final byte[] a = {'a', 'b', 'c', 'd'};
        final byte[] b = {'a', 'b', 'c', 'e'};
        assertTrue(NetworkUtil.isMatchWithPrefix(a, b, 24));
    }

    @Test
    public void shouldNotMatchIfNotAllBytesWithPrefixMatch() throws Exception {
        final byte[] a = {'a', 'b', 'c', 'd'};
        final byte[] b = {'a', 'b', 'd', 'd'};
        assertFalse(NetworkUtil.isMatchWithPrefix(a, b, 24));
    }

    @Test
    public void shouldMatchIfAllBytesWithPrefixUnalignedMatch() throws Exception {
        assertTrue(NetworkUtil.isMatchWithPrefix(
                asBytes(0b10101010_11111111_00000000_00000000),
                asBytes(0b10101010_11111110_00000000_00000000),
                15));
    }

    @Test
    public void shouldNotMatchIfNotAllBytesWithUnalignedPrefixMatch() throws Exception {
        assertFalse(NetworkUtil.isMatchWithPrefix(
                asBytes(0b10101010_11111111_00000000_00000000),
                asBytes(0b10101010_11111111_10000000_00000000),
                17));
    }

    @Test
    public void shouldFilterBySubnetAndFindOneResult() throws Exception {
        final NetworkInterfaceStub stub = new NetworkInterfaceStub();

        final NetworkInterface ifc1 = stub.add("192.168.0.1/24");
        stub.add("10.0.0.2/8");

        final Collection<NetworkInterface> filteredBySubnet = filterBySubnet(stub, getByName("192.168.0.0"), 24);

        assertThat(filteredBySubnet.size(), is(1));
        assertThat(first(filteredBySubnet), is(ifc1));
    }

    @Test
    public void shouldFilterBySubnetAndFindNoResults() throws Exception {
        final NetworkInterfaceStub stub = new NetworkInterfaceStub();

        stub.add("192.168.0.1/24");
        stub.add("10.0.0.2/8");

        final Collection<NetworkInterface> filteredBySubnet = filterBySubnet(stub, getByName("192.169.0.0"), 24);

        assertThat(filteredBySubnet.size(), is(0));
    }

    @Test
    public void shouldFilterBySubnetAndFindMultipleResultsOrderedByMatchLength() throws Exception {
        final NetworkInterfaceStub stub = new NetworkInterfaceStub();

        stub.add("10.0.0.2/8");
        final NetworkInterface ifc1 = stub.add("192.0.0.0/8");
        final NetworkInterface ifc2 = stub.add("192.168.1.1/24");
        final NetworkInterface ifc3 = stub.add("192.168.0.0/16");

        final Collection<NetworkInterface> filteredBySubnet = filterBySubnet(stub, getByName("192.0.0.0"), 8);

        assertThat(filteredBySubnet.size(), is(3));
        final Iterator<NetworkInterface> it = filteredBySubnet.iterator();
        assertThat(it.next(), sameInstance(ifc2));
        assertThat(it.next(), sameInstance(ifc3));
        assertThat(it.next(), sameInstance(ifc1));
    }

    @Test
    public void shouldFilterBySubnetAndFindOneIpV6Result() throws Exception {
        final NetworkInterfaceStub stub = new NetworkInterfaceStub();

        final NetworkInterface ifc1 = stub.add("fe80:0:0:0001:0002:0:0:1/80");
        stub.add("fe80:0:0:0002:0003:0:0:1/80");

        final Collection<NetworkInterface> filteredBySubnet =
                filterBySubnet(stub, getByName("fe80:0:0:0001:0002:0:0:0"), 80);

        assertThat(filteredBySubnet.size(), is(1));
        assertThat(first(filteredBySubnet), is(ifc1));
    }

    @Test
    public void shouldFilterBySubnetAndFindNoIpV6Results() throws Exception {
        final NetworkInterfaceStub stub = new NetworkInterfaceStub();

        stub.add("fe80:0:0:0001:0:0:0:1/64");
        stub.add("fe80:0:0:0002:0:0:0:1/64");

        final Collection<NetworkInterface> filteredBySubnet =
                filterBySubnet(stub, getByName("fe80:0:0:0004:0:0:0:0"), 64);

        assertThat(filteredBySubnet.size(), is(0));
    }

    @Test
    public void shouldFilterBySubnetAndFindMultipleIpV6ResultsOrderedByMatchLength() throws Exception {
        final NetworkInterfaceStub stub = new NetworkInterfaceStub();

        stub.add("ee80:0:0:0001:0:0:0:1/64");
        final NetworkInterface ifc1 = stub.add("fe80:0:0:0:0:0:0:1/16");
        final NetworkInterface ifc2 = stub.add("fe80:0001:0:0:0:0:0:1/32");
        final NetworkInterface ifc3 = stub.add("fe80:0001:abcd:0:0:0:0:1/48");

        final Collection<NetworkInterface> filteredBySubnet = filterBySubnet(stub, getByName("fe80:0:0:0:0:0:0:0"), 16);

        assertThat(filteredBySubnet.size(), is(3));
        final Iterator<NetworkInterface> it = filteredBySubnet.iterator();
        assertThat(it.next(), sameInstance(ifc3));
        assertThat(it.next(), sameInstance(ifc2));
        assertThat(it.next(), sameInstance(ifc1));
    }

    private static <T> T first(Collection<T> c) {
        return c.iterator().next();
    }

    private static class NetworkInterfaceStub implements NetworkInterfaceShim {
        private int counter = 0;

        private final IdentityHashMap<NetworkInterface, List<InterfaceAddress>> addressesByInterface =
                new IdentityHashMap<>();

        public Enumeration<NetworkInterface> getNetworkInterfaces() throws SocketException {
            return Collections.enumeration(addressesByInterface.keySet());
        }

        public List<InterfaceAddress> getInterfaceAddresses(NetworkInterface ifc) {
            return addressesByInterface.get(ifc);
        }

        public boolean isLoopback(NetworkInterface ifc) throws SocketException {
            return false;
        }

        public NetworkInterface add(String... ips) throws UnknownHostException {
            final List<InterfaceAddress> ias = new ArrayList<>();
            for (final String ip : ips) {
                final String[] parts = ip.split("/");
                ias.add(newInterfaceAddress(getByName(parts[0]), parseShort(parts[1])));
            }

            final NetworkInterface ifc = newNetworkInterface(String.valueOf(counter++));
            addressesByInterface.put(ifc, ias);

            return ifc;
        }
    }

    private static NetworkInterface newNetworkInterface(String name) {
        NetworkInterface networkInterface = null;
        try {
            final Constructor<NetworkInterface> ctor = NetworkInterface.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            final Field nameField = NetworkInterface.class.getDeclaredField("name");
            nameField.setAccessible(true);

            networkInterface = ctor.newInstance();
            nameField.set(networkInterface, name);

        } catch (final Exception ex) {
            LangUtil.rethrowUnchecked(ex);
        }

        return networkInterface;
    }

    private static InterfaceAddress newInterfaceAddress(InetAddress inetAddress, short maskLength) {
        InterfaceAddress interfaceAddress = null;
        try {
            final Constructor<InterfaceAddress> ctor = InterfaceAddress.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            final Field addressField = InterfaceAddress.class.getDeclaredField("address");
            addressField.setAccessible(true);
            final Field maskLengthField = InterfaceAddress.class.getDeclaredField("maskLength");
            maskLengthField.setAccessible(true);

            interfaceAddress = ctor.newInstance();
            addressField.set(interfaceAddress, inetAddress);
            maskLengthField.set(interfaceAddress, maskLength);
        } catch (final Exception ex) {
            LangUtil.rethrowUnchecked(ex);
        }

        return interfaceAddress;
    }

    private static byte[] asBytes(int i) {
        final byte[] bs = new byte[4];
        bs[0] = (byte) ((i >> 24) & 0xFF);
        bs[1] = (byte) ((i >> 16) & 0xFF);
        bs[2] = (byte) ((i >> 8) & 0xFF);
        bs[3] = (byte) (i & 0xFF);

        return bs;
    }
}
