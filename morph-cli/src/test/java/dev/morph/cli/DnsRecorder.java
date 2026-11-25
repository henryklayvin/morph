package dev.morph.cli;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Replaces the JVM's name resolution during the tests (registered through META-INF/services). Contacting any remote
 * service starts with a lookup, so recording and refusing them makes an accidental "phone home" visible.
 */
public final class DnsRecorder extends InetAddressResolverProvider {

    static final List<String> LOOKUPS = new CopyOnWriteArrayList<>();

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver system = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
                if (host.equals("localhost")) {
                    return system.lookupByName(host, policy);
                }
                LOOKUPS.add(host);
                throw new UnknownHostException("Refused by the test resolver");
            }

            @Override
            public String lookupByAddress(byte[] address) throws UnknownHostException {
                if (InetAddress.getByAddress(address).isLoopbackAddress()) {
                    return "localhost";
                }
                LOOKUPS.add(InetAddress.getByAddress(address).getHostAddress());
                throw new UnknownHostException("Refused by the test resolver");
            }
        };
    }

    @Override
    public String name() {
        return "morph-test-dns-recorder";
    }
}
