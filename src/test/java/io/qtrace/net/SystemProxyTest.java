package io.qtrace.net;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Reading the computer's proxy: environment, Windows registry, macOS scutil, GNOME gsettings. */
class SystemProxyTest {

    private static final URI QTRACE = URI.create("https://www.qtrace.ca/api/modules");

    @Test
    void environment_httpsProxyWins_andNoProxyIsHonoured() {
        var c = SystemProxy.fromEnvironment(Map.of(
            "HTTPS_PROXY", "http://user:pw@proxy.chu.fr:3128/",
            "HTTP_PROXY", "http://other:80",
            "NO_PROXY", "localhost, .intra.chu.fr")::get);
        assertEquals(new SystemProxy.HostPort("proxy.chu.fr", 3128), c.proxyFor(QTRACE));
        assertNull(c.proxyFor(URI.create("https://pacs.intra.chu.fr/x")));
        assertNull(SystemProxy.fromEnvironment(Map.<String, String>of()::get));
        assertNull(SystemProxy.parseProxyUrl("socks5://proxy:1080"), "not an HTTP proxy");
        assertEquals(new SystemProxy.HostPort("proxy", 80), SystemProxy.parseProxyUrl("proxy"));
    }

    @Test
    void windowsRegistry_singleProxy_perScheme_andBypass() {
        String out = """

            HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings
                CertificateRevocation    REG_DWORD    0x1
                ProxyEnable    REG_DWORD    0x1
                ProxyServer    REG_SZ    proxy.chu.fr:8080
                ProxyOverride    REG_SZ    <local>;*.chu.fr;10.*
            """;
        var c = SystemProxy.fromWindowsRegistry(out);
        assertEquals("Windows", c.source());
        assertEquals(new SystemProxy.HostPort("proxy.chu.fr", 8080), c.proxyFor(QTRACE));
        assertNull(c.proxyFor(URI.create("https://pacs.chu.fr/")));
        assertNull(c.proxyFor(URI.create("https://intranet/")), "<local>: names without a dot");
        assertNull(c.proxyFor(URI.create("https://10.1.2.3/")));

        var perScheme = SystemProxy.fromWindowsRegistry("""
                ProxyEnable    REG_DWORD    0x1
                ProxyServer    REG_SZ    http=web:80;https=secure:8443;ftp=f:21
            """);
        assertEquals(new SystemProxy.HostPort("secure", 8443), perScheme.proxyFor(QTRACE));
        assertEquals(new SystemProxy.HostPort("web", 80), perScheme.proxyFor(URI.create("http://x.org/")));
    }

    @Test
    void windowsRegistry_disabledProxy_isNone_butAScriptIsReported() {
        assertNull(SystemProxy.fromWindowsRegistry("""
                ProxyEnable    REG_DWORD    0x0
                ProxyServer    REG_SZ    proxy:8080
            """));
        var pac = SystemProxy.fromWindowsRegistry("""
                ProxyEnable    REG_DWORD    0x0
                AutoConfigURL    REG_SZ    http://wpad.chu.fr/proxy.pac
            """);
        assertFalse(pac.hasProxy());
        assertTrue(pac.hasAutomaticScript());
        assertEquals("http://wpad.chu.fr/proxy.pac", pac.pacUrl());
    }

    @Test
    void macOsScutil() {
        var c = SystemProxy.fromScutil("""
            <dictionary> {
              ExceptionsList : <array> {
                0 : *.local
                1 : pacs.chu.fr
              }
              FTPPassive : 1
              HTTPEnable : 1
              HTTPPort : 3128
              HTTPProxy : proxy.chu.fr
              HTTPSEnable : 1
              HTTPSPort : 3129
              HTTPSProxy : proxy.chu.fr
            }
            """);
        assertEquals(new SystemProxy.HostPort("proxy.chu.fr", 3129), c.proxyFor(QTRACE));
        assertNull(c.proxyFor(URI.create("https://pacs.chu.fr/")));
        assertNull(c.proxyFor(URI.create("https://printer.local/")));
        assertNull(SystemProxy.fromScutil("<dictionary> {\n  HTTPEnable : 0\n}\n"));
    }

    @Test
    void gnomeSettings() {
        Map<String, String> g = Map.of(
            "org.gnome.system.proxy mode", "'manual'\n",
            "org.gnome.system.proxy.https host", "'proxy.chu.fr'\n",
            "org.gnome.system.proxy.https port", "3128\n",
            "org.gnome.system.proxy.http host", "''\n",
            "org.gnome.system.proxy.http port", "0\n",
            "org.gnome.system.proxy ignore-hosts", "['localhost', '127.0.0.0/8', '*.chu.fr']\n");
        SystemProxy.Commands cmd = args -> g.get(args[2] + " " + args[3]);
        var c = SystemProxy.fromGnome(cmd);
        assertEquals(new SystemProxy.HostPort("proxy.chu.fr", 3128), c.proxyFor(QTRACE));
        assertNull(c.proxyFor(URI.create("https://pacs.chu.fr/")));

        SystemProxy.Commands auto = args -> args[3].equals("mode") ? "'auto'" : "'http://wpad/wpad.dat'";
        assertTrue(SystemProxy.fromGnome(auto).hasAutomaticScript());
        assertNull(SystemProxy.fromGnome(args -> "'none'"));
        assertNull(SystemProxy.fromGnome(args -> null), "no gsettings on this system");
    }

    @Test
    void detect_prefersTheEnvironment_thenTheSystem_andNeverFails() {
        SystemProxy.Commands none = args -> null;
        assertEquals(SystemProxy.Config.NONE, SystemProxy.detect(Map.of(), "Linux", none));
        assertEquals("HTTPS_PROXY", SystemProxy.detect(Map.of("https_proxy", "p:1"), "Windows 11", none).source());
        SystemProxy.Commands broken = args -> { throw new IllegalStateException("boom"); };
        assertEquals(SystemProxy.Config.NONE, SystemProxy.detect(Map.of(), "Windows 11", broken));
    }

    @Test
    void bypass_rules() {
        assertTrue(SystemProxy.bypassed("localhost", List.of()));
        assertTrue(SystemProxy.bypassed("127.0.0.1", List.of()));
        assertTrue(SystemProxy.bypassed("www.qtrace.ca", List.of("qtrace.ca")));
        assertTrue(SystemProxy.bypassed("www.qtrace.ca", List.of(".qtrace.ca")));
        assertTrue(SystemProxy.bypassed("anything", List.of("*")));
        assertFalse(SystemProxy.bypassed("notqtrace.ca", List.of("qtrace.ca")));
        assertFalse(SystemProxy.bypassed("www.qtrace.ca", List.of("<local>", "10.0.0.0/8")));
    }

    @Test
    void aPacScriptIsReadForTheProxiesItNames_neverRun() {
        String pac = """
            function FindProxyForURL(url, host) {
              if (isPlainHostName(host)) return "DIRECT";
              if (shExpMatch(host, "*.chu.fr")) return "DIRECT";
              return "PROXY proxy1.chu.fr:8080; PROXY proxy2.chu.fr:8080; DIRECT";
            }
            """;
        assertEquals(List.of("proxy1.chu.fr:8080", "proxy2.chu.fr:8080"), SystemProxy.proxiesNamedIn(pac));
    }
}
