/*
 * Haikyo Plugin - Server integration for the Haikyo Minecraft Client
 * Copyright (C) 2026 Haikyo Project Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package dev.haikyo.core.crypto;

import dev.haikyo.core.util.Hex;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/**
 * Ed25519 signature verification with automatic JDK 15+ fast path and zero-dependency
 * RFC 8032 pure-Java fallback for Java 8-14 servers (Minecraft 1.8 through 1.16.5).
 */
public final class PureEd25519 {
    static final byte[] ED25519_SPKI_PREFIX = Hex.decode("302a300506032b6570032100");

    // Curve25519 / Ed25519 parameters (RFC 8032 section 5.1)
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger L = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger TWO = BigInteger.valueOf(2);
    private static final BigInteger P_MINUS_5_DIV_8 = P.subtract(BigInteger.valueOf(5)).shiftRight(3);
    private static final BigInteger SQRT_MINUS_1 = TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);

    // Base point B
    private static final BigInteger B_Y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P);
    private static final BigInteger B_X = recoverX(B_Y, 0);
    private static final Point B = new Point(B_X, B_Y, BigInteger.ONE, B_X.multiply(B_Y).mod(P));
    private static final Point IDENTITY = new Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);

    private static final boolean JDK_ED25519_AVAILABLE = detectJdkEd25519();

    private PureEd25519() {
    }

    private static boolean detectJdkEd25519() {
        try {
            KeyFactory.getInstance("Ed25519");
            Signature.getInstance("Ed25519");
            return true;
        } catch (NoSuchAlgorithmException e) {
            return false;
        }
    }

    /**
     * Parses a raw 32-byte or X.509 SPKI 44-byte Ed25519 public key into a {@link PublicKey}.
     * Uses JDK Ed25519 on Java 15+ and falls back to {@link RawEd25519PublicKey} on Java 8-14.
     */
    public static PublicKey parsePublicKeyBytes(byte[] keyBytes) {
        if (keyBytes == null) {
            throw new IllegalArgumentException("Ed25519 public key bytes must not be null");
        }
        byte[] raw32;
        byte[] spki;
        if (keyBytes.length == 32) {
            raw32 = Arrays.copyOf(keyBytes, 32);
            spki = new byte[ED25519_SPKI_PREFIX.length + 32];
            System.arraycopy(ED25519_SPKI_PREFIX, 0, spki, 0, ED25519_SPKI_PREFIX.length);
            System.arraycopy(raw32, 0, spki, ED25519_SPKI_PREFIX.length, 32);
        } else if (keyBytes.length == ED25519_SPKI_PREFIX.length + 32) {
            for (int i = 0; i < ED25519_SPKI_PREFIX.length; i++) {
                if (keyBytes[i] != ED25519_SPKI_PREFIX[i]) {
                    throw new IllegalArgumentException("Invalid Ed25519 SPKI prefix");
                }
            }
            spki = Arrays.copyOf(keyBytes, keyBytes.length);
            raw32 = Arrays.copyOfRange(keyBytes, ED25519_SPKI_PREFIX.length, keyBytes.length);
        } else {
            throw new IllegalArgumentException("Invalid Ed25519 public key length: " + keyBytes.length);
        }

        if (JDK_ED25519_AVAILABLE) {
            try {
                KeyFactory kf = KeyFactory.getInstance("Ed25519");
                return kf.generatePublic(new X509EncodedKeySpec(spki));
            } catch (GeneralSecurityException e) {
                throw new IllegalArgumentException("Failed to decode Ed25519 public key", e);
            }
        }
        return new RawEd25519PublicKey(raw32, spki);
    }

    /**
     * Verifies an Ed25519 signature using JDK Ed25519 when available or RFC 8032 pure-Java fallback.
     */
    public static boolean verify(PublicKey publicKey, byte[] message, byte[] signature) {
        if (publicKey == null || message == null || signature == null || signature.length != 64) {
            return false;
        }
        if (JDK_ED25519_AVAILABLE && !(publicKey instanceof RawEd25519PublicKey)) {
            try {
                Signature sig = Signature.getInstance("Ed25519");
                sig.initVerify(publicKey);
                sig.update(message);
                return sig.verify(signature);
            } catch (GeneralSecurityException e) {
                return false;
            }
        }
        byte[] rawKey = extractRaw32(publicKey);
        if (rawKey == null) {
            return false;
        }
        return verifyPureJava(rawKey, message, signature);
    }

    /**
     * Pure-Java RFC 8032 Ed25519 signature verification (works on Java 8+ with zero dependencies).
     */
    public static boolean verifyPureJava(byte[] publicKey32, byte[] message, byte[] signature64) {
        if (publicKey32 == null || publicKey32.length != 32
                || message == null
                || signature64 == null || signature64.length != 64) {
            return false;
        }
        Point aPoint = decodePoint(publicKey32);
        if (aPoint == null) {
            return false;
        }
        byte[] rBytes = Arrays.copyOfRange(signature64, 0, 32);
        Point rPoint = decodePoint(rBytes);
        if (rPoint == null) {
            return false;
        }
        BigInteger s = decodeLittleEndian(signature64, 32, 32);
        if (s.compareTo(L) >= 0) {
            return false;
        }

        byte[] digest;
        try {
            MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
            sha512.update(rBytes);
            sha512.update(publicKey32);
            sha512.update(message);
            digest = sha512.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 unavailable", e);
        }

        BigInteger k = decodeLittleEndian(digest, 0, digest.length).mod(L);
        Point lhs = scalarMult(B, s);
        Point rhs = add(rPoint, scalarMult(aPoint, k));
        return Arrays.equals(encodePoint(lhs), encodePoint(rhs));
    }

    private static byte[] extractRaw32(PublicKey key) {
        if (key instanceof RawEd25519PublicKey) {
            return ((RawEd25519PublicKey) key).rawBytes();
        }
        byte[] encoded = key.getEncoded();
        if (encoded == null) {
            return null;
        }
        if (encoded.length == 32) {
            return encoded;
        }
        if (encoded.length == ED25519_SPKI_PREFIX.length + 32) {
            return Arrays.copyOfRange(encoded, ED25519_SPKI_PREFIX.length, encoded.length);
        }
        return null;
    }

    private static BigInteger decodeLittleEndian(byte[] buf, int off, int len) {
        byte[] be = new byte[len];
        for (int i = 0; i < len; i++) {
            be[len - 1 - i] = buf[off + i];
        }
        return new BigInteger(1, be);
    }

    private static Point decodePoint(byte[] enc) {
        int signBit = (enc[31] & 0x80) >>> 7;
        byte[] yBytes = Arrays.copyOf(enc, 32);
        yBytes[31] &= 0x7F;
        BigInteger y = decodeLittleEndian(yBytes, 0, 32);
        if (y.compareTo(P) >= 0) {
            return null;
        }
        BigInteger x = recoverX(y, signBit);
        if (x == null) {
            return null;
        }
        return new Point(x, y, BigInteger.ONE, x.multiply(y).mod(P));
    }

    private static BigInteger recoverX(BigInteger y, int signBit) {
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger u = y2.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(y2).add(BigInteger.ONE).mod(P);
        BigInteger v3 = v.multiply(v).mod(P).multiply(v).mod(P);
        BigInteger v7 = v3.multiply(v3).mod(P).multiply(v).mod(P);
        BigInteger x = u.multiply(v3).mod(P)
                .multiply(u.multiply(v7).mod(P).modPow(P_MINUS_5_DIV_8, P))
                .mod(P);

        BigInteger check = v.multiply(x).mod(P).multiply(x).mod(P);
        if (!check.equals(u)) {
            if (check.equals(P.subtract(u).mod(P))) {
                x = x.multiply(SQRT_MINUS_1).mod(P);
            } else {
                return null;
            }
        }
        if (x.signum() == 0 && signBit == 1) {
            return null;
        }
        if ((x.testBit(0) ? 1 : 0) != signBit) {
            x = P.subtract(x).mod(P);
        }
        return x;
    }

    private static byte[] encodePoint(Point pt) {
        BigInteger zInv = pt.z.modInverse(P);
        BigInteger x = pt.x.multiply(zInv).mod(P);
        BigInteger y = pt.y.multiply(zInv).mod(P);
        byte[] out = new byte[32];
        byte[] yBe = y.toByteArray();
        for (int i = 0; i < yBe.length; i++) {
            int destIdx = yBe.length - 1 - i;
            if (destIdx < 32) {
                out[destIdx] = yBe[i];
            }
        }
        if (x.testBit(0)) {
            out[31] |= (byte) 0x80;
        }
        return out;
    }

    private static Point add(Point p1, Point p2) {
        // Extended twisted Edwards coordinates (a = -1): RFC 8032 section 5.1.4
        BigInteger a = p1.y.subtract(p1.x).multiply(p2.y.subtract(p2.x)).mod(P);
        BigInteger b = p1.y.add(p1.x).multiply(p2.y.add(p2.x)).mod(P);
        BigInteger c = TWO.multiply(p1.t).mod(P).multiply(p2.t).mod(P).multiply(D).mod(P);
        BigInteger d = TWO.multiply(p1.z).mod(P).multiply(p2.z).mod(P);
        BigInteger e = b.subtract(a).mod(P);
        BigInteger f = d.subtract(c).mod(P);
        BigInteger g = d.add(c).mod(P);
        BigInteger h = b.add(a).mod(P);
        BigInteger x3 = e.multiply(f).mod(P);
        BigInteger y3 = g.multiply(h).mod(P);
        BigInteger t3 = e.multiply(h).mod(P);
        BigInteger z3 = f.multiply(g).mod(P);
        return new Point(x3, y3, z3, t3);
    }

    private static Point scalarMult(Point base, BigInteger scalar) {
        Point result = IDENTITY;
        Point addend = base;
        int bitLen = scalar.bitLength();
        for (int i = 0; i < bitLen; i++) {
            if (scalar.testBit(i)) {
                result = add(result, addend);
            }
            addend = add(addend, addend);
        }
        return result;
    }

    private static final class Point {
        final BigInteger x;
        final BigInteger y;
        final BigInteger z;
        final BigInteger t;

        Point(BigInteger x, BigInteger y, BigInteger z, BigInteger t) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.t = t;
        }
    }

    /**
     * Lightweight {@link PublicKey} wrapper used on Java 8-14 runtimes lacking built-in Ed25519 JCA support.
     */
    public static final class RawEd25519PublicKey implements PublicKey {
        private static final long serialVersionUID = 1L;
        private final byte[] raw32;
        private final byte[] spki;

        RawEd25519PublicKey(byte[] raw32, byte[] spki) {
            this.raw32 = raw32;
            this.spki = spki;
        }

        byte[] rawBytes() {
            return raw32;
        }

        @Override
        public String getAlgorithm() {
            return "Ed25519";
        }

        @Override
        public String getFormat() {
            return "X.509";
        }

        @Override
        public byte[] getEncoded() {
            return Arrays.copyOf(spki, spki.length);
        }
    }
}
