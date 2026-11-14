package com.example.adapter.csvtool;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Network traffic goes only through {@code context.http()}: the adapter has
 * no HTTP client of its own. The host owns the HTTP path (address check,
 * TLS, proxy, rate limit, retries, deadlines), and a client of the adapter
 * would connect past all of it. The rule checks the compiled classes of
 * {@code src/main}; the TCK sees the same only on the paths it exercises
 * (case 2).
 *
 * <p>For your own adapter, change {@link #ADAPTER_PACKAGE}. If you use an
 * HTTP library with a connector of its own (such as Spring WebFlux through
 * {@code rmm_adapter_http_spring}), allow only the class that builds it on
 * the HTTP path of the host. The rule guards against accidents, not against
 * intent (reflection, {@code Class.forName}). It checks only the classes of
 * the adapter itself: a library with its own client inside the shaded jar
 * goes unnoticed as long as the adapter code calls only its facade.</p>
 *
 * <p>Also forbidden: {@code URL.getContent()}, datagrams, server sockets,
 * asynchronous and NIO channels and an {@code SSLEngine} of the adapter. A
 * {@code WebClient} from an injected {@code WebClient.Builder} is caught
 * here by the forbidden package
 * {@code org.springframework.web.reactive.function.client}; if you allow
 * WebFlux, add a rule for the builder and {@code mutate()}.</p>
 */
public class TestCsvToolNetworkPathRule {

    /** The package of the adapter. */
    static final String ADAPTER_PACKAGE = "com.example.adapter.csvtool";

    /** Packages of network clients. */
    static final List<String> CLIENT_PACKAGES = List.of("java.net.http", "org.apache.http", "org.apache.hc",
            "org.eclipse.jetty.client", "okhttp3", "reactor.netty", "io.netty", "org.springframework.http.client",
            "org.springframework.web.reactive.function.client");

    /** Single classes that open a connection past the HTTP path of the host. */
    static final Set<String> CLIENT_CLASSES = Set.of("java.net.Socket", "java.net.HttpURLConnection",
            "java.net.URLConnection", "java.net.Proxy", "java.nio.channels.SocketChannel", "javax.net.SocketFactory",
            "javax.net.ssl.SSLSocketFactory", "javax.net.ssl.HttpsURLConnection", "javax.net.ssl.SSLContext",
            "org.springframework.web.client.RestTemplate", "org.springframework.web.client.RestClient",
            //Datagrams, server sockets, asynchronous and NIO channels, a TLS engine of the adapter
            "java.net.DatagramSocket", "java.net.MulticastSocket", "java.net.ServerSocket",
            "java.nio.channels.DatagramChannel", "java.nio.channels.ServerSocketChannel",
            "java.nio.channels.AsynchronousSocketChannel", "java.nio.channels.AsynchronousServerSocketChannel",
            "javax.net.ssl.SSLEngine");

    static boolean isNetworkClient(JavaClass type) {
        String name = type.getName();
        return CLIENT_CLASSES.contains(name)
                || CLIENT_PACKAGES.stream().anyMatch(prefix -> name.startsWith(prefix + "."));
    }

    static boolean opensAConnection(JavaMethodCall call) {
        return call.getTargetOwner().getName().equals("java.net.URL")
                && (call.getName().equals("openConnection") || call.getName().equals("openStream")
                        || call.getName().equals("getContent"));
    }

    static ArchRule noOwnNetworkClient(String packageName) {
        return noClasses().that().resideInAPackage(packageName + "..")
                .should().dependOnClassesThat(DescribedPredicate.describe("a network client of its own",
                        TestCsvToolNetworkPathRule::isNetworkClient))
                .orShould().callMethodWhere(DescribedPredicate.describe("URL.openConnection/openStream/getContent",
                        TestCsvToolNetworkPathRule::opensAConnection))
                .because("an adapter sends only through context.http()");
    }

    @Test
    public void theAdapterHasNoNetworkClientOfItsOwn() {
        JavaClasses main = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(ADAPTER_PACKAGE);
        //Without this check the rule would also pass if the import found nothing
        assertTrue(main.contain(CsvToolAdapter.class), "CsvToolAdapter is missing");

        noOwnNetworkClient(ADAPTER_PACKAGE).check(main);
    }

    /** The rule is not hollow: a client of the adapter is caught. */
    @Test
    public void theRuleSeesADirectClient() {
        JavaClasses fixture = new ClassFileImporter().importClasses(DirectClient.class);

        assertTrue(noOwnNetworkClient(DirectClient.class.getPackageName()).evaluate(fixture).hasViolation());
    }

    /** Each way around the HTTP path is caught on its own: one class per case. */
    @Test
    public void theRuleSeesEachWayAroundTheHttpPath() {
        for (Class<?> bypass : List.of(UrlContent.class, Datagram.class, ServerSide.class, AsynchronousChannel.class,
                NioChannel.class, OwnTlsEngine.class)) {
            JavaClasses fixture = new ClassFileImporter().importClasses(bypass);
            assertTrue(noOwnNetworkClient(bypass.getPackageName()).evaluate(fixture).hasViolation(),
                    bypass.getSimpleName());
        }
    }

    static final class UrlContent {
        Object read() throws java.io.IOException {
            return new java.net.URL("https://csvtool.example").getContent();
        }
    }

    static final class Datagram {
        Object open() throws java.io.IOException {
            return new java.net.DatagramSocket();
        }
    }

    static final class ServerSide {
        Object open() throws java.io.IOException {
            return new java.net.ServerSocket(0);
        }
    }

    static final class AsynchronousChannel {
        Object open() throws java.io.IOException {
            return java.nio.channels.AsynchronousSocketChannel.open();
        }
    }

    static final class NioChannel {
        Object open() throws java.io.IOException {
            return java.nio.channels.SocketChannel.open();
        }
    }

    /** Only the {@code SSLEngine}, without an {@code SSLContext}: the engine alone is caught. */
    static final class OwnTlsEngine {
        Object handshake(javax.net.ssl.SSLEngine engine) throws javax.net.ssl.SSLException {
            engine.beginHandshake();
            return engine.getSession();
        }
    }

    /** What an adapter with a client of its own would do. */
    static final class DirectClient {
        Object jdk() {
            return java.net.http.HttpClient.newHttpClient();
        }
    }
}
