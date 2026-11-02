/*
 * AWSOpenVPN — AWS Client VPN SAML (auth-federate) support.
 * Part of a modified OpenVPN for Android (GPLv2). Created by MR.TRYCATCH @ CODEMARVEL INDONESIA.
 *
 * Flow (same as the AWS VPN Client):
 *  1. Answer the 'Auth' query with N/A / ACS::35001.
 *  2. The server replies AUTH_FAILED,CRV1:<flags>:<sid>:<user>:<login url>.
 *  3. Open the login URL in the browser and receive the IdP's POST (SAMLResponse) on 127.0.0.1:35001.
 *  4. Answer the next 'Auth' query with N/A / CRV1::<sid>::<SAMLResponse> to the same server IP.
 * The SAML response is a credential: it is kept only in memory and never logged.
 */
package de.blinkt.openvpn.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

public class AwsSamlFlow {
    public static final int PORT = 35001;
    public static final String FIRST_PASSWORD = "ACS::" + PORT;
    public static final String USERNAME = "N/A";
    private static final int MAX_BODY = 1 << 20;
    private static final long WAIT_MS = 5 * 60 * 1000;

    public interface Listener {
        /** Called on a background thread with the password to send (CRV1::sid::SAML). */
        void onSamlPassword(String password);

        void onSamlFailed(String reason);
    }

    /** Random host label like OpenVPN's remote-random-hostname (6 random bytes as hex). */
    public static String randomHostname(String host) {
        byte[] r = new byte[6];
        new SecureRandom().nextBytes(r);
        StringBuilder sb = new StringBuilder();
        for (byte b : r) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb + "." + host;
    }

    /** Resolve <random>.<host> once so every reconnect of this session goes to the same server. */
    public static String resolvePinned(String host) throws IOException {
        InetAddress[] all = InetAddress.getAllByName(randomHostname(host));
        for (InetAddress a : all)
            if (a.getAddress().length == 4) return a.getHostAddress();
        return all[0].getHostAddress();
    }

    /** Parsed CRV1 challenge, or null if the text is not one. */
    public static String[] parseCrv1(String text) {
        int i = text.indexOf("CRV1:");
        if (i < 0) return null;
        String s = text.substring(i);
        int end = s.lastIndexOf('\'');
        if (end > 0) s = s.substring(0, end);
        if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
        // CRV1:<flags>:<state id>:<base64 user>:<text = login URL>
        String[] p = s.split(":", 5);
        if (p.length < 5 || p[2].isEmpty() || !p[4].startsWith("http")) return null;
        return new String[]{p[2], p[4]};
    }

    private ServerSocket server;
    private volatile boolean done;

    /** Start listening on 127.0.0.1:35001; must be called before opening the login URL. */
    public synchronized void start(final String sid, final Listener listener) throws IOException {
        stop();
        done = false;
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
        server.setSoTimeout(1000);
        final ServerSocket ss = server;
        final long deadline = System.currentTimeMillis() + WAIT_MS;
        Thread t = new Thread(() -> {
            try {
                while (!done && System.currentTimeMillis() < deadline) {
                    Socket c;
                    try {
                        c = ss.accept();
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    // A browser may open idle pre-connections; serve each one on its own thread.
                    new Thread(() -> handle(c, sid, listener), "AwsSamlConn").start();
                }
                if (!done) listener.onSamlFailed("timeout");
            } catch (IOException e) {
                if (!done) listener.onSamlFailed(e.getMessage());
            } finally {
                closeQuietly(ss);
            }
        }, "AwsSamlReceiver");
        t.start();
    }

    public synchronized void stop() {
        done = true;
        closeQuietly(server);
        server = null;
    }

    private void handle(Socket c, String sid, Listener listener) {
        try (Socket s = c) {
            s.setSoTimeout(15000);
            InputStream in = s.getInputStream();
            String head = readHead(in);
            if (head == null) return;
            String first = head.split("\r\n", 2)[0];
            int len = 0;
            for (String line : head.split("\r\n")) {
                int k = line.indexOf(':');
                if (k > 0 && line.substring(0, k).trim().equalsIgnoreCase("content-length"))
                    len = Integer.parseInt(line.substring(k + 1).trim());
            }
            String saml = null;
            if (first.startsWith("POST ") && len > 0 && len <= MAX_BODY) {
                byte[] body = new byte[len];
                int off = 0;
                while (off < len) {
                    int n = in.read(body, off, len - off);
                    if (n < 0) break;
                    off += n;
                }
                String form = new String(body, 0, off, StandardCharsets.US_ASCII);
                for (String kv : form.split("&")) {
                    if (kv.startsWith("SAMLResponse=")) {
                        saml = URLDecoder.decode(kv.substring(13), "UTF-8");
                    }
                }
            }
            boolean ok = saml != null && !saml.isEmpty() && !done;
            String html = ok
                    ? "<h2>AWSOpenVPN: sign-in accepted.</h2><p>The VPN is connecting. You can close this tab and return to your app.</p>"
                    : "<h2>AWSOpenVPN</h2><p>Waiting for the SAML sign-in.</p>";
            byte[] out = ("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>"
                    + "<body style='font-family:sans-serif;padding:24px'>" + html + "</body>").getBytes(StandardCharsets.UTF_8);
            OutputStream os = s.getOutputStream();
            os.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + out.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            os.write(out);
            os.flush();
            if (ok) {
                synchronized (this) {
                    if (done) return;
                    stop();
                }
                listener.onSamlPassword("CRV1::" + sid + "::" + saml);
            }
        } catch (IOException | RuntimeException ignored) {
            // idle/aborted browser connection
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0;
        while (b.size() < 65536) {
            int ch = in.read();
            if (ch < 0) return null;
            b.write(ch);
            if ((state == 0 || state == 2) && ch == '\r') state++;
            else if ((state == 1 || state == 3) && ch == '\n') state++;
            else state = 0;
            if (state == 4) return b.toString("ISO-8859-1");
        }
        return null;
    }

    private static void closeQuietly(ServerSocket s) {
        try {
            if (s != null) s.close();
        } catch (IOException ignored) {
        }
    }
}
