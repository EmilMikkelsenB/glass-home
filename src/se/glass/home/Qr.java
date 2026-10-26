package se.glass.home;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.nio.charset.StandardCharsets;

/**
 * Minimal QR code encoder (byte mode, error correction L, versions 1–10, mask 0) for the "set up from your phone" URL.
 * A compact port of the reference algorithm (Project Nayuki, MIT licence).
 */
final class Qr {
    private static final int[] ECC_PER_BLOCK = {-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18};
    private static final int[] NUM_BLOCKS = {-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4};

    private final int ver, size;
    private final boolean[][] modules, isFunction;

    private Qr(int ver) {
        this.ver = ver;
        size = ver * 4 + 17;
        modules = new boolean[size][size];
        isFunction = new boolean[size][size];
    }

    /** Black-on-white QR image of `text`, about `px` pixels wide (whole modules, 2-module quiet zone). */
    static Bitmap bitmap(String text, int px) {
        Qr q = encode(text.getBytes(StandardCharsets.UTF_8));
        int n = q.size + 4, scale = Math.max(1, px / n);
        Bitmap bm = Bitmap.createBitmap(n * scale, n * scale, Bitmap.Config.ARGB_8888);
        bm.eraseColor(Color.WHITE);
        for (int y = 0; y < q.size; y++) {
            for (int x = 0; x < q.size; x++) {
                if (!q.modules[y][x]) continue;
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) bm.setPixel((x + 2) * scale + dx, (y + 2) * scale + dy, Color.BLACK);
                }
            }
        }
        return bm;
    }

    private static Qr encode(byte[] data) {
        for (int v = 1; v <= 10; v++) {
            int capBits = (rawModules(v) / 8 - ECC_PER_BLOCK[v] * NUM_BLOCKS[v]) * 8;
            int lenBits = v <= 9 ? 8 : 16;
            int need = 4 + lenBits + data.length * 8;
            if (need > capBits) continue;
            Bits bb = new Bits();
            bb.append(4, 4);
            bb.append(data.length, lenBits);
            for (byte b : data) bb.append(b & 0xff, 8);
            bb.append(0, Math.min(4, capBits - bb.len));
            bb.append(0, (8 - bb.len % 8) % 8);
            for (int pad = 0xEC; bb.len < capBits; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
            byte[] codewords = new byte[bb.len / 8];
            for (int i = 0; i < bb.len; i++) codewords[i >>> 3] |= bb.get(i) << (7 - (i & 7));
            Qr q = new Qr(v);
            q.drawFunctionPatterns();
            q.drawCodewords(q.addEcc(codewords));
            q.applyMask();
            q.drawFormatBits();
            return q;
        }
        throw new IllegalArgumentException("text too long for a QR code");
    }

    private static int rawModules(int v) {
        int r = (16 * v + 128) * v + 64;
        if (v >= 2) {
            int na = v / 7 + 2;
            r -= (25 * na - 10) * na - 55;
            if (v >= 7) r -= 36;
        }
        return r;
    }

    private void set(int x, int y, boolean dark) {
        modules[y][x] = dark;
        isFunction[y][x] = true;
    }

    private void drawFunctionPatterns() {
        for (int i = 0; i < size; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        int[] pos = alignmentPositions();
        int last = pos.length - 1;
        for (int i = 0; i < pos.length; i++) {
            for (int j = 0; j < pos.length; j++) {
                if ((i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0)) continue;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) set(pos[i] + dx, pos[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
                }
            }
        }
        drawFormatBits();   // reserve the area; real bits are drawn after masking
        if (ver >= 7) {
            int rem = ver;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            long bits = (long) ver << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean bit = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, b = i / 3;
                set(a, b, bit);
                set(b, a, bit);
            }
        }
    }

    private void finder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int d = Math.max(Math.abs(dx), Math.abs(dy)), xx = x + dx, yy = y + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) set(xx, yy, d != 2 && d != 4);
            }
        }
    }

    private int[] alignmentPositions() {
        if (ver == 1) return new int[0];
        int n = ver / 7 + 2, step = (ver * 8 + n * 3 + 5) / (n * 4 - 4) * 2;
        int[] r = new int[n];
        r[0] = 6;
        for (int i = n - 1, p = size - 7; i >= 1; i--, p -= step) r[i] = p;
        return r;
    }

    private void drawFormatBits() {
        int data = 1 << 3;   // ECC level L = 01, mask 0
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    private byte[] addEcc(byte[] data) {
        int blocks = NUM_BLOCKS[ver], eccLen = ECC_PER_BLOCK[ver], raw = rawModules(ver) / 8;
        int shortBlocks = blocks - raw % blocks, shortLen = raw / blocks;
        byte[] div = rsDivisor(eccLen);
        byte[][] bs = new byte[blocks][];
        for (int i = 0, k = 0; i < blocks; i++) {
            int len = shortLen - eccLen + (i < shortBlocks ? 0 : 1);
            byte[] dat = java.util.Arrays.copyOfRange(data, k, k + len);
            k += len;
            byte[] block = java.util.Arrays.copyOf(dat, shortLen + 1);
            byte[] ecc = rsRemainder(dat, div);
            System.arraycopy(ecc, 0, block, block.length - eccLen, ecc.length);
            bs[i] = block;
        }
        byte[] out = new byte[raw];
        for (int i = 0, k = 0; i < bs[0].length; i++) {
            for (int j = 0; j < blocks; j++) {
                if (i != shortLen - eccLen || j >= shortBlocks) out[k++] = bs[j][i];
            }
        }
        return out;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask() {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) if (!isFunction[y][x] && (x + y) % 2 == 0) modules[y][x] = !modules[y][x];
        }
    }

    private static byte[] rsDivisor(int degree) {
        byte[] r = new byte[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                r[j] = (byte) mul(r[j] & 0xff, root);
                if (j + 1 < degree) r[j] ^= r[j + 1];
            }
            root = mul(root, 0x02);
        }
        return r;
    }

    private static byte[] rsRemainder(byte[] data, byte[] div) {
        byte[] r = new byte[div.length];
        for (byte b : data) {
            int factor = (b ^ r[0]) & 0xff;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= (byte) mul(div[i] & 0xff, factor);
        }
        return r;
    }

    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private static final class Bits {
        private final java.util.BitSet bits = new java.util.BitSet();
        int len;

        void append(int val, int n) {
            for (int i = n - 1; i >= 0; i--, len++) bits.set(len, ((val >>> i) & 1) != 0);
        }

        int get(int i) {
            return bits.get(i) ? 1 : 0;
        }
    }
}
