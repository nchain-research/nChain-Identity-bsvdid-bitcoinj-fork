/*
 * Modifications described in the NOTICE.txt file are licensed under the Open BSV Licence.
 * Modifications Copyright 2020 Bitcoin Association
 *
 * Copyright 2011 Google Inc.
 * Copyright 2014 Giannis Dzegoutanis
 * Copyright 2015 Andreas Schildbach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.bitcoinj.core;

import io.bitcoinj.exception.AddressFormatException;
import io.bitcoinj.exception.WrongNetworkException;
import io.bitcoinj.params.NetworkParameters;
import io.bitcoinj.params.Networks;
import io.bitcoinj.script.Script;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * <p>A Bitcoin address looks like 1MsScoe2fTJoq4ZPdQgqyhgWeoNamYPevy and is derived from an elliptic curve public key
 * plus a set of network parameters. Not to be confused with a { PeerAddress} or { AddressMessage}
 * which are about network (TCP) addresses.</p>
 *
 * <p>A standard address is built by taking the RIPE-MD160 hash of the public key bytes, with a version prefix and a
 * checksum suffix, then encoding it textually as base58. The version prefix is used to both denote the network for
 * which the address is valid (see {@link NetworkParameters}, and also to indicate how the bytes inside the address
 * should be interpreted. Whilst almost all addresses today are hashes of public keys, another (currently unsupported
 * type) can contain a hash of a script instead.</p>
 */
public class AddressLite extends VersionedChecksummedBytes implements Addressable {
    /**
     * An address is a RIPEMD160 hash of a public key, therefore is always 160 bits or 20 bytes.
     */
    public static final int LENGTH = 20;

    private transient NetworkParameters params;

    /**
     * Construct an address from parameters, the address version, and the hash160 form. Example:<p>
     *
     * <pre>new Address(MainNetParams.get(), NetworkParameters.getAddressHeader(), Hex.decode("4a22c3c4cbb31e4d03b15550636762bda0baf85a"));</pre>
     */
    public AddressLite(NetworkParameters params, int version, byte[] hash160) throws WrongNetworkException {
        super(version, hash160);
        checkNotNull(params);
        checkArgument(hash160.length == 20, "Addresses are 160-bit hashes, so you must provide 20 bytes");
        if (!isAcceptableVersion(params, version))
            throw new WrongNetworkException(version, params.getAcceptableAddressCodes());
        this.params = params;
    }

    public AddressLite(Addressable address) {
        this(address.getParams(), address.getVersion(), address.getHash160());
    }

    /** Returns an Address that represents the given P2SH script hash. */
    public static AddressLite fromP2SHHash(NetworkParameters params, byte[] hash160) {
        try {
            return new AddressLite(params, params.getP2SHHeader(), hash160);
        } catch (WrongNetworkException e) {
            throw new RuntimeException(e);  // Cannot happen.
        }
    }

    /** Returns an Address that represents the script hash extracted from the given scriptPubKey */
    public static AddressLite fromP2SHScript(NetworkParameters params, Script scriptPubKey) {
        checkArgument(scriptPubKey.isPayToScriptHash(), "Not a P2SH script");
        return fromP2SHHash(params, scriptPubKey.getPubKeyHash());
    }

    /**
     * Construct an address from its Base58 representation.
     * @param params
     *            The expected NetworkParameters or null if you don't want validation.
     * @param base58
     *            The textual form of the address, such as "17kzeh4N8g49GFvdDzSf8PjaPfyoD1MndL".
     * @throws AddressFormatException
     *             if the given base58 doesn't parse or the checksum is invalid
     * @throws WrongNetworkException
     *             if the given address is valid but for a different chain (eg testnet vs mainnet)
     */
    public static AddressLite fromBase58(@Nullable NetworkParameters params, String base58) throws AddressFormatException {
        return new AddressLite(params, base58);
    }

    /**
     * Construct an address from parameters and the hash160 form. Example:<p>
     *
     * <pre>new Address(MainNetParams.get(), Hex.decode("4a22c3c4cbb31e4d03b15550636762bda0baf85a"));</pre>
     */
    public AddressLite(NetworkParameters params, byte[] hash160) {
        super(params.getAddressHeader(), hash160);
        checkArgument(hash160.length == 20, "Addresses are 160-bit hashes, so you must provide 20 bytes");
        this.params = params;
    }

    /** @deprecated Use {@link #fromBase58(NetworkParameters, String)} */
    @Deprecated
    public AddressLite(@Nullable NetworkParameters params, String address) throws AddressFormatException {
        super(address);
        if (params != null) {
            if (!isAcceptableVersion(params, version)) {
                throw new WrongNetworkException(version, params.getAcceptableAddressCodes());
            }
            this.params = params;
        } else {
            NetworkParameters paramsFound = null;
            for (NetworkParameters p : Networks.get()) {
                if (isAcceptableVersion(p, version)) {
                    paramsFound = p;
                    break;
                }
            }
            if (paramsFound == null)
                throw new AddressFormatException("No network found for " + address);

            this.params = paramsFound;
        }
    }

    @Override
    public NetworkParameters getParams() {
        return params;
    }

    /** The (big endian) 20 byte hash that is the core of a Bitcoin address. */
    @Override
    public byte[] getHash160() {
        return bytes;
    }

    /**
     * Returns true if this address is a Pay-To-Script-Hash (P2SH) address.
     * See also https://github.com/bitcoin/bips/blob/master/bip-0013.mediawiki: Address Format for pay-to-script-hash
     */
    @Override
    public boolean isP2SHAddress() {
        final NetworkParameters parameters = getParameters();
        return parameters != null && this.version == parameters.getP2SHHeader();
    }

    /**
     * Examines the version byte of the address and attempts to find a matching NetworkParameters. If you aren't sure
     * which network the address is intended for (eg, it was provided by a user), you can use this to decide if it is
     * compatible with the current wallet. You should be able to handle a null response from this method. Note that the
     * parameters returned is not necessarily the same as the one the Address was created with.
     *
     * @return a NetworkParameters representing the network the address is intended for.
     */
    public NetworkParameters getParameters() {
        return params;
    }

    /**
     * Given an address, examines the version byte and attempts to find a matching NetworkParameters. If you aren't sure
     * which network the address is intended for (eg, it was provided by a user), you can use this to decide if it is
     * compatible with the current wallet.
     * @return a NetworkParameters of the address
     * @throws AddressFormatException if the string wasn't of a known version
     */
    public static NetworkParameters getParametersFromAddress(String address) throws AddressFormatException {
        try {
            return AddressLite.fromBase58(null, address).getParameters();
        } catch (WrongNetworkException e) {
            throw new RuntimeException(e);  // Cannot happen.
        }
    }

    /**
     * Check if a given address version is valid given the NetworkParameters.
     */
    public static boolean isAcceptableVersion(NetworkParameters params, int version) {
        for (int v : params.getAcceptableAddressCodes()) {
            if (version == v) {
                return true;
            }
        }
        return false;
    }

    /**
     * This implementation narrows the return type to <code>Address</code>.
     */
    @Override
    public AddressLite clone() throws CloneNotSupportedException {
        return (AddressLite) super.clone();
    }

    // Java serialization

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        out.writeUTF(params.getId());
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        params = Verification.fromID(in.readUTF());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Addressable other = (Addressable) o;
        return this.getVersion() == other.getVersion() && Arrays.equals(this.getHash160(), other.getHash160());
    }

}
