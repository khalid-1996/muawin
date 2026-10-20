package ae.khalid.muawin;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Minimal ADB client speaking the classic TCP wire protocol to the car's own
 * adbd on 127.0.0.1:5555 (same trick as BYD-dashboard). Lets the launcher
 * place itself on the passenger screen and install its own updates without a PC.
 *
 * Only what we need: connect + RSA auth, then run one shell command at a time
 * and collect its output. The RSA key is generated once and kept in prefs, so
 * the car's "Allow debugging" prompt appears only on the first connect.
 */
public class AdbClient {

    private static final int A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_OPEN = 0x4e45504f,
            A_OKAY = 0x59414b4f, A_WRTE = 0x45545257, A_CLSE = 0x45534c43;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    // ASN.1 DigestInfo prefix for SHA-1, prepended to the 20-byte token before PKCS#1 signing.
    private static final byte[] SHA1_PREFIX = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private final Context ctx;
    private Socket sock;
    private InputStream in;
    private OutputStream out;
    private int maxData = 256 * 1024;
    private int nextId = 1;
    private RSAPrivateKey priv;
    private RSAPublicKey pub;

    public AdbClient(Context c) { ctx = c; }

    public synchronized boolean isConnected() {
        return sock != null && sock.isConnected() && !sock.isClosed();
    }

    /** Connects and authenticates. Blocks up to ~70s while the user approves the prompt. */
    public synchronized void connect(String host, int port) throws Exception {
        close();
        loadKeys();
        sock = new Socket();
        sock.connect(new InetSocketAddress(host, port), 8000);
        sock.setSoTimeout(70000);
        in = sock.getInputStream();
        out = sock.getOutputStream();

        send(A_CNXN, 0x01000000, 256 * 1024, "host::features=shell_v2,cmd\0".getBytes("UTF-8"));

        boolean sentSig = false, sentPub = false;
        int[] h = new int[4];
        while (true) {
            byte[] payload = readMessage(h);
            if (h[0] == A_CNXN) {
                if (h[2] > 0) maxData = h[2];
                return;
            } else if (h[0] == A_AUTH && h[1] == AUTH_TOKEN) {
                if (!sentSig) {
                    sentSig = true;
                    send(A_AUTH, AUTH_SIGNATURE, 0, sign(payload));
                } else if (!sentPub) {
                    sentPub = true;
                    send(A_AUTH, AUTH_RSAPUBLICKEY, 0, adbPublicKey());
                }
                // After the public key, keep reading: the car shows its prompt and
                // replies CNXN once approved (or the read times out).
            }
        }
    }

    /** Runs one shell command and returns its combined output. */
    public synchronized String exec(String command) throws Exception {
        if (!isConnected()) throw new IOException("not connected");
        int local = nextId++;
        send(A_OPEN, local, 0, ("shell:" + command + "\0").getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        int[] h = new int[4];
        while (true) {
            byte[] p = readMessage(h);
            if (h[0] == A_OKAY) {
                // stream accepted
            } else if (h[0] == A_WRTE) {
                sb.append(new String(p, "UTF-8"));
                send(A_OKAY, h[2], h[1], new byte[0]); // ack: our id, their id
            } else if (h[0] == A_CLSE) {
                send(A_CLSE, h[2], h[1], new byte[0]);
                break;
            }
        }
        return sb.toString();
    }

    public synchronized void close() {
        try { if (sock != null) sock.close(); } catch (Exception ignored) {}
        sock = null; in = null; out = null;
    }

    // ----------------------------------------------------------------- wire

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        int crc = 0;
        if (data != null) for (byte x : data) crc += (x & 0xff);
        ByteBuffer b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd); b.putInt(arg0); b.putInt(arg1); b.putInt(len); b.putInt(crc); b.putInt(cmd ^ 0xffffffff);
        out.write(b.array());
        if (len > 0) out.write(data);
        out.flush();
    }

    /** Reads one message; fills hdr[cmd,arg0,arg1,len] and returns the payload. */
    private byte[] readMessage(int[] hdr) throws IOException {
        byte[] head = readFully(24);
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        hdr[0] = b.getInt(); hdr[1] = b.getInt(); hdr[2] = b.getInt();
        int len = b.getInt();
        hdr[3] = len;
        b.getInt(); b.getInt(); // crc, magic — not verified
        return len > 0 ? readFully(len) : new byte[0];
    }

    private byte[] readFully(int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new IOException("stream closed");
            off += r;
        }
        return b;
    }

    // ----------------------------------------------------------------- crypto

    private void loadKeys() throws Exception {
        if (priv != null) return;
        SharedPreferences sp = ctx.getSharedPreferences("adb", Context.MODE_PRIVATE);
        String pk = sp.getString("priv", null), pb = sp.getString("pub", null);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (pk != null && pb != null) {
            priv = (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(pk, Base64.NO_WRAP)));
            pub = (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(Base64.decode(pb, Base64.NO_WRAP)));
        } else {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            priv = (RSAPrivateKey) kp.getPrivate();
            pub = (RSAPublicKey) kp.getPublic();
            sp.edit()
                    .putString("priv", Base64.encodeToString(priv.getEncoded(), Base64.NO_WRAP))
                    .putString("pub", Base64.encodeToString(pub.getEncoded(), Base64.NO_WRAP))
                    .apply();
        }
    }

    private byte[] sign(byte[] token) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, priv);
        byte[] d = new byte[SHA1_PREFIX.length + token.length];
        System.arraycopy(SHA1_PREFIX, 0, d, 0, SHA1_PREFIX.length);
        System.arraycopy(token, 0, d, SHA1_PREFIX.length, token.length);
        return c.doFinal(d);
    }

    /** The public key in Android's "androidpubkey" format, base64 + a name, NUL-terminated. */
    private byte[] adbPublicKey() throws Exception {
        final int WORDS = 2048 / 32; // 64
        BigInteger r32 = BigInteger.ZERO.setBit(32);
        BigInteger n = pub.getModulus();
        BigInteger r = BigInteger.ZERO.setBit(2048);
        BigInteger rr = r.modPow(BigInteger.valueOf(2), n);
        BigInteger n0inv = r32.subtract(n.remainder(r32).modInverse(r32));

        int[] myN = new int[WORDS], myRr = new int[WORDS];
        BigInteger nn = n, rrr = rr;
        for (int i = 0; i < WORDS; i++) {
            BigInteger[] q = rrr.divideAndRemainder(r32); rrr = q[0]; myRr[i] = q[1].intValue();
            BigInteger[] q2 = nn.divideAndRemainder(r32); nn = q2[0]; myN[i] = q2[1].intValue();
        }
        ByteBuffer bb = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(WORDS);
        bb.putInt(n0inv.intValue());
        for (int x : myN) bb.putInt(x);
        for (int x : myRr) bb.putInt(x);
        bb.putInt(pub.getPublicExponent().intValue());
        String b64 = Base64.encodeToString(bb.array(), Base64.NO_WRAP);
        return (b64 + " muawin@byd\0").getBytes("UTF-8");
    }
}
