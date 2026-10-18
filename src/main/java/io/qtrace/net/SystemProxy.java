/*
 * qTrace - QuPath workflow provenance extension
 * Copyright (C) 2026 Romain Tourte
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package io.qtrace.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The proxy this computer is set to use. QuPath's Java runtime ignores it
 * ({@code java.net.useSystemProxies=false} in its {@code net.properties}, read once when the JVM
 * starts), so qTrace reads it itself, in this order:
 * <ol>
 *   <li>the environment: {@code HTTPS_PROXY} / {@code HTTP_PROXY} / {@code NO_PROXY} (any case);</li>
 *   <li>Windows: the user's Internet settings (the ones Edge and Chrome use), from the registry;</li>
 *   <li>macOS: {@code scutil --proxy};</li>
 *   <li>Linux GNOME: {@code gsettings org.gnome.system.proxy}.</li>
 * </ol>
 * The JVM's own settings ({@code -Dhttps.proxyHost}, or {@code -Djava.net.useSystemProxies=true}
 * set in QuPath's launcher) come before all of these: see {@link QTraceHttp}.
 *
 * An automatic configuration script (PAC, WPAD) is detected but not run - qTrace has no
 * JavaScript engine to run it - and reported, so the user can enter the proxy by hand.
 */
public final class SystemProxy {

    private static final Logger log = LoggerFactory.getLogger(SystemProxy.class);

    private SystemProxy() {}

    /** A proxy address. */
    public record HostPort(String host, int port) {
        @Override public String toString() { return host + ":" + port; }
    }

    /**
     * What was found. {@code source} names where (for messages), {@code https}/{@code http} the
     * proxies, {@code bypass} the hosts reached directly, {@code pacUrl} an automatic configuration
     * script that qTrace cannot run, {@code autoDetect} WPAD switched on.
     */
    public record Config(String source, HostPort https, HostPort http, List<String> bypass,
                         String pacUrl, boolean autoDetect) {
        public static final Config NONE = new Config("none", null, null, List.of(), null, false);

        public boolean hasProxy() { return https != null || http != null; }
        public boolean hasAutomaticScript() { return pacUrl != null || autoDetect; }

        /** The proxy for {@code uri}, or null to connect directly. */
        public HostPort proxyFor(URI uri) {
            String host = uri.getHost();
            if (host == null || bypassed(host, bypass)) return null;
            boolean secure = "https".equalsIgnoreCase(uri.getScheme());
            return secure ? (https != null ? https : http) : (http != null ? http : https);
        }

        public Proxy javaProxyFor(URI uri) {
            HostPort hp = proxyFor(uri);
            return hp == null ? Proxy.NO_PROXY
                : new Proxy(Proxy.Type.HTTP, new InetSocketAddress(hp.host(), hp.port()));
        }
    }

    // ── Detection ─────────────────────────────────────────────────────────────

    /** Runs a command and returns its standard output, or null (absent, failed, too slow). */
    interface Commands { String run(String... command); }

    static final Commands PROCESS = command -> {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(false).start();
            p.getOutputStream().close();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            return p.exitValue() == 0 ? new String(out, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null; // not on this system
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    };

    private static final long CACHE_MS = 120_000; // a laptop moves between networks: read again now and then
    private static volatile Config cached;
    private static volatile long cachedAt;

    /** This computer's proxy settings, read at most every two minutes. */
    public static Config current() {
        Config c = cached;
        if (c == null || System.currentTimeMillis() - cachedAt > CACHE_MS) {
            c = detect(System.getenv(), System.getProperty("os.name", ""), PROCESS);
            cached = c;
            cachedAt = System.currentTimeMillis();
            log.debug("System proxy: {}", c);
        }
        return c;
    }

    /** Forgets what was read, e.g. when the user tests the connection after changing networks. */
    public static void refresh() { cached = null; }

    static Config detect(Map<String, String> env, String osName, Commands commands) {
        Config fromEnv = fromEnvironment(env::get);
        if (fromEnv != null) return fromEnv;
        String os = osName.toLowerCase(Locale.ROOT);
        Config c = null;
        try {
            if (os.contains("win")) {
                String out = commands.run("reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings");
                if (out != null) c = fromWindowsRegistry(out);
            } else if (os.contains("mac")) {
                String out = commands.run("scutil", "--proxy");
                if (out != null) c = fromScutil(out);
            } else {
                c = fromGnome(commands);
            }
        } catch (RuntimeException e) {
            log.warn("Could not read the system proxy settings: {}", e.toString());
        }
        return c != null ? c : Config.NONE;
    }

    // ── Environment ───────────────────────────────────────────────────────────

    static Config fromEnvironment(Function<String, String> env) {
        HostPort https = parseProxyUrl(firstSet(env, "https_proxy", "HTTPS_PROXY"));
        HostPort http  = parseProxyUrl(firstSet(env, "http_proxy", "HTTP_PROXY"));
        if (https == null && http == null) return null;
        String noProxy = firstSet(env, "no_proxy", "NO_PROXY");
        return new Config("HTTPS_PROXY", https, http, split(noProxy, "[,\\s]+"), null, false);
    }

    private static String firstSet(Function<String, String> env, String... names) {
        for (String n : names) {
            String v = env.apply(n);
            if (v != null && !v.isBlank()) return v.strip();
        }
        return null;
    }

    /** {@code http://user@proxy:3128/}, {@code proxy:3128} or {@code proxy} (port 80 for http, as curl). */
    static HostPort parseProxyUrl(String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.strip();
        String scheme = "http";
        int sep = v.indexOf("://");
        if (sep >= 0) { scheme = v.substring(0, sep).toLowerCase(Locale.ROOT); v = v.substring(sep + 3); }
        if (!scheme.equals("http") && !scheme.equals("https")) return null; // socks: not an HTTP proxy
        int at = v.lastIndexOf('@');
        if (at >= 0) v = v.substring(at + 1);
        int slash = v.indexOf('/');
        if (slash >= 0) v = v.substring(0, slash);
        return hostPort(v, scheme.equals("https") ? 443 : 80);
    }

    private static HostPort hostPort(String v, int defaultPort) {
        if (v == null) return null;
        v = v.strip();
        if (v.isEmpty()) return null;
        String host = v;
        int port = defaultPort;
        int colon = v.lastIndexOf(':');
        if (colon > 0 && v.indexOf(']') < colon) {
            host = v.substring(0, colon);
            try { port = Integer.parseInt(v.substring(colon + 1).strip()); } catch (NumberFormatException e) { return null; }
        }
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.isEmpty() || port < 1 || port > 65535) return null;
        return new HostPort(host, port);
    }

    // ── Windows ───────────────────────────────────────────────────────────────

    private static final Pattern REG_LINE = Pattern.compile("^\\s+(\\S+)\\s+(REG_\\w+)\\s*(.*)$");

    /** Output of {@code reg query "HKCU\...\Internet Settings"}. */
    static Config fromWindowsRegistry(String out) {
        String enable = null, server = null, override = null, pac = null;
        boolean autoDetect = false;
        for (String line : out.split("\\R")) {
            Matcher m = REG_LINE.matcher(line);
            if (!m.matches()) continue;
            String name = m.group(1), value = m.group(3).strip();
            switch (name) {
                case "ProxyEnable"   -> enable = value;
                case "ProxyServer"   -> server = value;
                case "ProxyOverride" -> override = value;
                case "AutoConfigURL" -> pac = value.isEmpty() ? null : value;
                case "AutoDetect"    -> autoDetect = isOne(value);
                default -> { }
            }
        }
        HostPort https = null, http = null;
        if (isOne(enable) && server != null && !server.isBlank()) {
            if (server.contains("=")) {
                for (String part : server.split(";")) {
                    String[] kv = part.split("=", 2);
                    if (kv.length != 2) continue;
                    String k = kv[0].strip().toLowerCase(Locale.ROOT);
                    if (k.equals("https")) https = hostPort(stripScheme(kv[1]), 80);
                    if (k.equals("http"))  http  = hostPort(stripScheme(kv[1]), 80);
                }
            } else {
                https = http = hostPort(stripScheme(server), 80);
            }
        }
        if (https == null && http == null && pac == null && !autoDetect) return null;
        return new Config("Windows", https, http, split(override, ";"), pac, autoDetect);
    }

    private static boolean isOne(String regValue) {
        if (regValue == null) return false;
        String v = regValue.strip().toLowerCase(Locale.ROOT);
        return v.equals("0x1") || v.equals("1");
    }

    private static String stripScheme(String v) {
        int sep = v.indexOf("://");
        return sep >= 0 ? v.substring(sep + 3) : v;
    }

    // ── macOS ─────────────────────────────────────────────────────────────────

    /** Output of {@code scutil --proxy}. */
    static Config fromScutil(String out) {
        java.util.Map<String, String> kv = new java.util.HashMap<>();
        List<String> exceptions = new ArrayList<>();
        boolean inExceptions = false;
        for (String raw : out.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("ExceptionsList")) { inExceptions = true; continue; }
            if (inExceptions) {
                if (line.startsWith("}")) { inExceptions = false; continue; }
                int c = line.indexOf(" : ");
                if (c > 0) exceptions.add(line.substring(c + 3).strip());
                continue;
            }
            int c = line.indexOf(" : ");
            if (c > 0) kv.put(line.substring(0, c).strip(), line.substring(c + 3).strip());
        }
        HostPort https = "1".equals(kv.get("HTTPSEnable")) ? hostPort(kv.get("HTTPSProxy") + ":" + kv.getOrDefault("HTTPSPort", "443"), 443) : null;
        HostPort http  = "1".equals(kv.get("HTTPEnable"))  ? hostPort(kv.get("HTTPProxy") + ":" + kv.getOrDefault("HTTPPort", "80"), 80) : null;
        String pac = "1".equals(kv.get("ProxyAutoConfigEnable")) ? kv.get("ProxyAutoConfigURLString") : null;
        boolean auto = "1".equals(kv.get("ProxyAutoDiscoveryEnable"));
        if (https == null && http == null && pac == null && !auto) return null;
        return new Config("macOS", https, http, exceptions, pac, auto);
    }

    // ── GNOME ─────────────────────────────────────────────────────────────────

    static Config fromGnome(Commands commands) {
        String mode = gsetting(commands, "org.gnome.system.proxy", "mode");
        if (mode == null || mode.equals("none")) return null;
        if (mode.equals("auto")) {
            String pac = gsetting(commands, "org.gnome.system.proxy", "autoconfig-url");
            return new Config("GNOME", null, null, List.of(), pac == null || pac.isEmpty() ? null : pac, pac == null || pac.isEmpty());
        }
        if (!mode.equals("manual")) return null;
        HostPort https = gnomeProxy(commands, "https");
        HostPort http  = gnomeProxy(commands, "http");
        if (https == null && http == null) return null;
        String ignore = commands.run("gsettings", "get", "org.gnome.system.proxy", "ignore-hosts");
        return new Config("GNOME", https, http, gvariantStrings(ignore), null, false);
    }

    private static HostPort gnomeProxy(Commands commands, String scheme) {
        String host = gsetting(commands, "org.gnome.system.proxy." + scheme, "host");
        String port = gsetting(commands, "org.gnome.system.proxy." + scheme, "port");
        if (host == null || host.isEmpty()) return null;
        return hostPort(host + ":" + (port == null || port.equals("0") ? "8080" : port), 8080);
    }

    private static String gsetting(Commands commands, String schema, String key) {
        String out = commands.run("gsettings", "get", schema, key);
        if (out == null) return null;
        String v = out.strip();
        if (v.startsWith("'") && v.endsWith("'") && v.length() >= 2) v = v.substring(1, v.length() - 1);
        return v;
    }

    /** {@code ['localhost', '127.0.0.0/8']} → the strings. */
    static List<String> gvariantStrings(String out) {
        List<String> list = new ArrayList<>();
        if (out == null) return list;
        Matcher m = Pattern.compile("'([^']*)'").matcher(out);
        while (m.find()) list.add(m.group(1));
        return list;
    }

    // ── Bypass ────────────────────────────────────────────────────────────────

    private static List<String> split(String s, String regex) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String p : s.split(regex)) if (!p.isBlank()) out.add(p.strip());
        return out;
    }

    /**
     * Whether {@code host} is reached directly: loopback always; then the bypass entries, as the
     * three systems write them ({@code <local>}, {@code *.chu.fr}, {@code .chu.fr}, {@code chu.fr}
     * for the domain and its sub-domains, {@code 10.*}, {@code *}).
     */
    public static boolean bypassed(String host, List<String> bypass) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        if (isLoopback(h)) return true;
        for (String raw : bypass) {
            String e = raw.strip().toLowerCase(Locale.ROOT);
            if (e.isEmpty()) continue;
            if (e.equals("<local>")) { if (!h.contains(".") && !h.contains(":")) return true; continue; }
            if (e.equals("*")) return true;
            if (e.contains("/")) continue; // CIDR ranges: only loopback matters to qTrace, handled above
            if (e.contains("*")) {
                if (h.matches(globToRegex(e))) return true;
                continue;
            }
            if (e.startsWith(".")) e = e.substring(1);
            if (h.equals(e) || h.endsWith("." + e)) return true;
        }
        return false;
    }

    static boolean isLoopback(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.endsWith(".localhost") || h.startsWith("127.") || h.equals("::1");
    }

    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (char c : glob.toCharArray()) {
            if (c == '*') sb.append(".*");
            else if ("\\.[]{}()+-?^$|".indexOf(c) >= 0) sb.append('\\').append(c);
            else sb.append(c);
        }
        return sb.toString();
    }

    // ── Automatic configuration script ────────────────────────────────────────

    private static final Pattern PAC_PROXY = Pattern.compile("PROXY\\s+([A-Za-z0-9.\\-]+:\\d{1,5})");

    /**
     * The proxies a PAC script names ({@code "PROXY host:port"}), to suggest them when qTrace
     * cannot run the script itself. Read, never executed.
     */
    public static List<String> proxiesNamedIn(String pacScript) {
        List<String> out = new ArrayList<>();
        if (pacScript == null) return out;
        Matcher m = PAC_PROXY.matcher(pacScript);
        while (m.find() && out.size() < 5) if (!out.contains(m.group(1))) out.add(m.group(1));
        return out;
    }
}
