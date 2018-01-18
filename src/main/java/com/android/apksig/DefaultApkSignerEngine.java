/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.apksig;

import com.android.apksig.apk.ApkFormatException;
import com.android.apksig.apk.ApkUtils;
import com.android.apksig.internal.apk.ApkSigningBlockUtils;
import com.android.apksig.internal.apk.v1.DigestAlgorithm;
import com.android.apksig.internal.apk.v1.V1SchemeSigner;
import com.android.apksig.internal.apk.v2.V2SchemeSigner;
import com.android.apksig.internal.util.Pair;
import com.android.apksig.internal.util.TeeDataSink;
import com.android.apksig.util.DataSink;
import com.android.apksig.util.DataSinks;
import com.android.apksig.util.DataSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Default implementation of {@link ApkSignerEngine}.
 *
 * <p>Use {@link Builder} to obtain instances of this engine.
 */
public class DefaultApkSignerEngine implements ApkSignerEngine {

    // IMPLEMENTATION NOTE: This engine generates a signed APK as follows:
    // 1. The engine asks its client to output input JAR entries which are not part of JAR
    //    signature.
    // 2. If JAR signing (v1 signing) is enabled, the engine inspects the output JAR entries to
    //    compute their digests, to be placed into output META-INF/MANIFEST.MF. It also inspects
    //    the contents of input and output META-INF/MANIFEST.MF to borrow the main section of the
    //    file. It does not care about individual (i.e., JAR entry-specific) sections. It then
    //    emits the v1 signature (a set of JAR entries) and asks the client to output them.
    // 3. If APK Signature Scheme v2 (v2 signing) is enabled, the engine emits an APK Signing Block
    //    from outputZipSections() and asks its client to insert this block into the output.

    private final boolean mV1SigningEnabled;
    private final boolean mV2SigningEnabled;
    private final boolean mDebuggableApkPermitted;
    private final boolean mOtherSignersSignaturesPreserved;
    private final String mCreatedBy;
    private final List<SignerConfig> mSignerConfigs;
    private final int mMinSdkVersion;

    private List<V1SchemeSigner.SignerConfig> mV1SignerConfigs = Collections.emptyList();
    private DigestAlgorithm mV1ContentDigestAlgorithm;

    private boolean mClosed;

    private boolean mV1SignaturePending;

    /**
     * Names of JAR entries which this engine is expected to output as part of v1 signing.
     */
    private Set<String> mSignatureExpectedOutputJarEntryNames = Collections.emptySet();

    /** Requests for digests of output JAR entries. */
    private final Map<String, GetJarEntryDataDigestRequest> mOutputJarEntryDigestRequests =
            new HashMap<>();

    /** Digests of output JAR entries. */
    private final Map<String, byte[]> mOutputJarEntryDigests = new HashMap<>();

    /** Data of JAR entries emitted by this engine as v1 signature. */
    private final Map<String, byte[]> mEmittedSignatureJarEntryData = new HashMap<>();

    /** Requests for data of output JAR entries which comprise the v1 signature. */
    private final Map<String, GetJarEntryDataRequest> mOutputSignatureJarEntryDataRequests =
            new HashMap<>();
    /**
     * Request to obtain the data of MANIFEST.MF or {@code null} if the request hasn't been issued.
     */
    private GetJarEntryDataRequest mInputJarManifestEntryDataRequest;

    /**
     * Request to obtain the data of AndroidManifest.xml or {@code null} if the request hasn't been
     * issued.
     */
    private GetJarEntryDataRequest mOutputAndroidManifestEntryDataRequest;

    /**
     * Whether the package being signed is marked as {@code android:debuggable} or {@code null}
     * if this is not yet known.
     */
    private Boolean mDebuggable;

    /**
     * Request to output the emitted v1 signature or {@code null} if the request hasn't been issued.
     */
    private OutputJarSignatureRequestImpl mAddV1SignatureRequest;

    private boolean mV2SignaturePending;

    /**
     * Request to output the emitted v2 signature or {@code null} if the request hasn't been issued.
     */
    private OutputApkSigningBlockRequestImpl mAddV2SignatureRequest;

    private DefaultApkSignerEngine(
            List<SignerConfig> signerConfigs,
            int minSdkVersion,
            boolean v1SigningEnabled,
            boolean v2SigningEnabled,
            boolean debuggableApkPermitted,
            boolean otherSignersSignaturesPreserved,
            String createdBy) throws InvalidKeyException {
        if (signerConfigs.isEmpty()) {
            throw new IllegalArgumentException("At least one signer config must be provided");
        }
        if (otherSignersSignaturesPreserved) {
            throw new UnsupportedOperationException(
                    "Preserving other signer's signatures is not yet implemented");
        }

        mV1SigningEnabled = v1SigningEnabled;
        mV2SigningEnabled = v2SigningEnabled;
        mV1SignaturePending = v1SigningEnabled;
        mV2SignaturePending = v2SigningEnabled;
        mDebuggableApkPermitted = debuggableApkPermitted;
        mOtherSignersSignaturesPreserved = otherSignersSignaturesPreserved;
        mCreatedBy = createdBy;
        mSignerConfigs = signerConfigs;
        mMinSdkVersion = minSdkVersion;

        if (v1SigningEnabled) {
            createV1SignerConfigs(signerConfigs, minSdkVersion);
        }
    }

    private void createV1SignerConfigs(List<SignerConfig> signerConfigs, int minSdkVersion)
            throws InvalidKeyException {
        mV1SignerConfigs = new ArrayList<>(signerConfigs.size());
        Map<String, Integer> v1SignerNameToSignerIndex = new HashMap<>(signerConfigs.size());
        DigestAlgorithm v1ContentDigestAlgorithm = null;
        for (int i = 0; i < signerConfigs.size(); i++) {
            SignerConfig signerConfig = signerConfigs.get(i);
            List<X509Certificate> certificates = signerConfig.getCertificates();
            PublicKey publicKey = certificates.get(0).getPublicKey();

            String v1SignerName = V1SchemeSigner.getSafeSignerName(signerConfig.getName());
            // Check whether the signer's name is unique among all v1 signers
            Integer indexOfOtherSignerWithSameName =
                    v1SignerNameToSignerIndex.put(v1SignerName, i);
            if (indexOfOtherSignerWithSameName != null) {
                throw new IllegalArgumentException(
                        "Signers #" + (indexOfOtherSignerWithSameName + 1)
                        + " and #" + (i + 1)
                        + " have the same name: " + v1SignerName
                        + ". v1 signer names must be unique");
            }

            DigestAlgorithm v1SignatureDigestAlgorithm =
                    V1SchemeSigner.getSuggestedSignatureDigestAlgorithm(
                            publicKey, minSdkVersion);
            V1SchemeSigner.SignerConfig v1SignerConfig = new V1SchemeSigner.SignerConfig();
            v1SignerConfig.name = v1SignerName;
            v1SignerConfig.privateKey = signerConfig.getPrivateKey();
            v1SignerConfig.certificates = certificates;
            v1SignerConfig.signatureDigestAlgorithm = v1SignatureDigestAlgorithm;
            // For digesting contents of APK entries and of MANIFEST.MF, pick the algorithm
            // of comparable strength to the digest algorithm used for computing the signature.
            // When there are multiple signers, pick the strongest digest algorithm out of their
            // signature digest algorithms. This avoids reducing the digest strength used by any
            // of the signers to protect APK contents.
            if (v1ContentDigestAlgorithm == null) {
                v1ContentDigestAlgorithm = v1SignatureDigestAlgorithm;
            } else {
                if (DigestAlgorithm.BY_STRENGTH_COMPARATOR.compare(
                        v1SignatureDigestAlgorithm, v1ContentDigestAlgorithm) > 0) {
                    v1ContentDigestAlgorithm = v1SignatureDigestAlgorithm;
                }
            }
            mV1SignerConfigs.add(v1SignerConfig);
        }
        mV1ContentDigestAlgorithm = v1ContentDigestAlgorithm;
        mSignatureExpectedOutputJarEntryNames =
                V1SchemeSigner.getOutputEntryNames(mV1SignerConfigs);
    }

    private List<ApkSigningBlockUtils.SignerConfig> createV2SignerConfigs(
            boolean apkSigningBlockPaddingSupported) throws InvalidKeyException {
        List<ApkSigningBlockUtils.SignerConfig> v2SignerConfigs = new ArrayList<>(mSignerConfigs.size());
        for (int i = 0; i < mSignerConfigs.size(); i++) {
            SignerConfig signerConfig = mSignerConfigs.get(i);
            List<X509Certificate> certificates = signerConfig.getCertificates();
            PublicKey publicKey = certificates.get(0).getPublicKey();

            ApkSigningBlockUtils.SignerConfig v2SignerConfig =
                    new ApkSigningBlockUtils.SignerConfig();
            v2SignerConfig.privateKey = signerConfig.getPrivateKey();
            v2SignerConfig.certificates = certificates;
            v2SignerConfig.signatureAlgorithms =
                    V2SchemeSigner.getSuggestedSignatureAlgorithms(publicKey, mMinSdkVersion,
                            apkSigningBlockPaddingSupported);
            v2SignerConfigs.add(v2SignerConfig);
        }
        return v2SignerConfigs;
    }

    @Override
    public void inputApkSigningBlock(DataSource apkSigningBlock) {
        checkNotClosed();

        if ((apkSigningBlock == null) || (apkSigningBlock.size() == 0)) {
            return;
        }

        if (mOtherSignersSignaturesPreserved) {
            // TODO: Preserve blocks other than APK Signature Scheme v2 blocks of signers configured
            // in this engine.
            return;
        }
        // TODO: Preserve blocks other than APK Signature Scheme v2 blocks.
    }

    @Override
    public InputJarEntryInstructions inputJarEntry(String entryName) {
        checkNotClosed();

        InputJarEntryInstructions.OutputPolicy outputPolicy =
                getInputJarEntryOutputPolicy(entryName);
        switch (outputPolicy) {
            case SKIP:
                return new InputJarEntryInstructions(InputJarEntryInstructions.OutputPolicy.SKIP);
            case OUTPUT:
                return new InputJarEntryInstructions(InputJarEntryInstructions.OutputPolicy.OUTPUT);
            case OUTPUT_BY_ENGINE:
                if (V1SchemeSigner.MANIFEST_ENTRY_NAME.equals(entryName)) {
                    // We copy the main section of the JAR manifest from input to output. Thus, this
                    // invalidates v1 signature and we need to see the entry's data.
                    mInputJarManifestEntryDataRequest = new GetJarEntryDataRequest(entryName);
                    return new InputJarEntryInstructions(
                            InputJarEntryInstructions.OutputPolicy.OUTPUT_BY_ENGINE,
                            mInputJarManifestEntryDataRequest);
                }
                return new InputJarEntryInstructions(
                        InputJarEntryInstructions.OutputPolicy.OUTPUT_BY_ENGINE);
            default:
                throw new RuntimeException("Unsupported output policy: " + outputPolicy);
        }
    }

    @Override
    public InspectJarEntryRequest outputJarEntry(String entryName) {
        checkNotClosed();
        invalidateV2Signature();

        if ((!mDebuggableApkPermitted)
                && (ApkUtils.ANDROID_MANIFEST_ZIP_ENTRY_NAME.equals(entryName))) {
            forgetOutputApkDebuggableStatus();
        }

        if (!mV1SigningEnabled) {
            // No need to inspect JAR entries when v1 signing is not enabled.
            if ((!mDebuggableApkPermitted)
                    && (ApkUtils.ANDROID_MANIFEST_ZIP_ENTRY_NAME.equals(entryName))) {
                // To reject debuggable APKs we need to inspect the APK's AndroidManifest.xml to
                // check whether it declares that the APK is debuggable
                mOutputAndroidManifestEntryDataRequest = new GetJarEntryDataRequest(entryName);
                return mOutputAndroidManifestEntryDataRequest;
            }
            return null;
        }
        // v1 signing is enabled

        if (V1SchemeSigner.isJarEntryDigestNeededInManifest(entryName)) {
            // This entry is covered by v1 signature. We thus need to inspect the entry's data to
            // compute its digest(s) for v1 signature.

            // TODO: Handle the case where other signer's v1 signatures are present and need to be
            // preserved. In that scenario we can't modify MANIFEST.MF and add/remove JAR entries
            // covered by v1 signature.
            invalidateV1Signature();
            GetJarEntryDataDigestRequest dataDigestRequest =
                    new GetJarEntryDataDigestRequest(
                            entryName,
                            V1SchemeSigner.getJcaMessageDigestAlgorithm(mV1ContentDigestAlgorithm));
            mOutputJarEntryDigestRequests.put(entryName, dataDigestRequest);
            mOutputJarEntryDigests.remove(entryName);

            if ((!mDebuggableApkPermitted)
                    && (ApkUtils.ANDROID_MANIFEST_ZIP_ENTRY_NAME.equals(entryName))) {
                // To reject debuggable APKs we need to inspect the APK's AndroidManifest.xml to
                // check whether it declares that the APK is debuggable
                mOutputAndroidManifestEntryDataRequest = new GetJarEntryDataRequest(entryName);
                return new CompoundInspectJarEntryRequest(
                        entryName, mOutputAndroidManifestEntryDataRequest, dataDigestRequest);
            }

            return dataDigestRequest;
        }

        if (mSignatureExpectedOutputJarEntryNames.contains(entryName)) {
            // This entry is part of v1 signature generated by this engine. We need to check whether
            // the entry's data is as output by the engine.
            invalidateV1Signature();
            GetJarEntryDataRequest dataRequest;
            if (V1SchemeSigner.MANIFEST_ENTRY_NAME.equals(entryName)) {
                dataRequest = new GetJarEntryDataRequest(entryName);
                mInputJarManifestEntryDataRequest = dataRequest;
            } else {
                // If this entry is part of v1 signature which has been emitted by this engine,
                // check whether the output entry's data matches what the engine emitted.
                dataRequest =
                        (mEmittedSignatureJarEntryData.containsKey(entryName))
                                ? new GetJarEntryDataRequest(entryName) : null;
            }

            if (dataRequest != null) {
                mOutputSignatureJarEntryDataRequests.put(entryName, dataRequest);
            }
            return dataRequest;
        }

        // This entry is not covered by v1 signature and isn't part of v1 signature.
        return null;
    }

    @Override
    public InputJarEntryInstructions.OutputPolicy inputJarEntryRemoved(String entryName) {
        checkNotClosed();
        return getInputJarEntryOutputPolicy(entryName);
    }

    @Override
    public void outputJarEntryRemoved(String entryName) {
        checkNotClosed();
        invalidateV2Signature();
        if (!mV1SigningEnabled) {
            return;
        }

        if (V1SchemeSigner.isJarEntryDigestNeededInManifest(entryName)) {
            // This entry is covered by v1 signature.
            invalidateV1Signature();
            mOutputJarEntryDigests.remove(entryName);
            mOutputJarEntryDigestRequests.remove(entryName);
            mOutputSignatureJarEntryDataRequests.remove(entryName);
            return;
        }

        if (mSignatureExpectedOutputJarEntryNames.contains(entryName)) {
            // This entry is part of the v1 signature generated by this engine.
            invalidateV1Signature();
            return;
        }
    }

    @Override
    public OutputJarSignatureRequest outputJarEntries()
            throws ApkFormatException, InvalidKeyException, SignatureException,
                    NoSuchAlgorithmException {
        checkNotClosed();

        if (!mV1SignaturePending) {
            return null;
        }

        if ((mInputJarManifestEntryDataRequest != null)
                && (!mInputJarManifestEntryDataRequest.isDone())) {
            throw new IllegalStateException(
                    "Still waiting to inspect input APK's "
                            + mInputJarManifestEntryDataRequest.getEntryName());
        }

        for (GetJarEntryDataDigestRequest digestRequest
                : mOutputJarEntryDigestRequests.values()) {
            String entryName = digestRequest.getEntryName();
            if (!digestRequest.isDone()) {
                throw new IllegalStateException(
                        "Still waiting to inspect output APK's " + entryName);
            }
            mOutputJarEntryDigests.put(entryName, digestRequest.getDigest());
        }
        mOutputJarEntryDigestRequests.clear();

        for (GetJarEntryDataRequest dataRequest : mOutputSignatureJarEntryDataRequests.values()) {
            if (!dataRequest.isDone()) {
                throw new IllegalStateException(
                        "Still waiting to inspect output APK's " + dataRequest.getEntryName());
            }
        }

        List<Integer> apkSigningSchemeIds =
                (mV2SigningEnabled) ? Collections.singletonList(2) : Collections.emptyList();
        byte[] inputJarManifest =
                (mInputJarManifestEntryDataRequest != null)
                    ? mInputJarManifestEntryDataRequest.getData() : null;

        // Check whether the most recently used signature (if present) is still fine.
        checkOutputApkNotDebuggableIfDebuggableMustBeRejected();
        List<Pair<String, byte[]>> signatureZipEntries;
        if ((mAddV1SignatureRequest == null) || (!mAddV1SignatureRequest.isDone())) {
            try {
                signatureZipEntries =
                        V1SchemeSigner.sign(
                                mV1SignerConfigs,
                                mV1ContentDigestAlgorithm,
                                mOutputJarEntryDigests,
                                apkSigningSchemeIds,
                                inputJarManifest,
                                mCreatedBy);
            } catch (CertificateException e) {
                throw new SignatureException("Failed to generate v1 signature", e);
            }
        } else {
            V1SchemeSigner.OutputManifestFile newManifest =
                    V1SchemeSigner.generateManifestFile(
                            mV1ContentDigestAlgorithm,
                            mOutputJarEntryDigests,
                            inputJarManifest);
            byte[] emittedSignatureManifest =
                    mEmittedSignatureJarEntryData.get(V1SchemeSigner.MANIFEST_ENTRY_NAME);
            if (!Arrays.equals(newManifest.contents, emittedSignatureManifest)) {
                // Emitted v1 signature is no longer valid.
                try {
                    signatureZipEntries =
                            V1SchemeSigner.signManifest(
                                    mV1SignerConfigs,
                                    mV1ContentDigestAlgorithm,
                                    apkSigningSchemeIds,
                                    mCreatedBy,
                                    newManifest);
                } catch (CertificateException e) {
                    throw new SignatureException("Failed to generate v1 signature", e);
                }
            } else {
                // Emitted v1 signature is still valid. Check whether the signature is there in the
                // output.
                signatureZipEntries = new ArrayList<>();
                for (Map.Entry<String, byte[]> expectedOutputEntry
                        : mEmittedSignatureJarEntryData.entrySet()) {
                    String entryName = expectedOutputEntry.getKey();
                    byte[] expectedData = expectedOutputEntry.getValue();
                    GetJarEntryDataRequest actualDataRequest =
                            mOutputSignatureJarEntryDataRequests.get(entryName);
                    if (actualDataRequest == null) {
                        // This signature entry hasn't been output.
                        signatureZipEntries.add(Pair.of(entryName, expectedData));
                        continue;
                    }
                    byte[] actualData = actualDataRequest.getData();
                    if (!Arrays.equals(expectedData, actualData)) {
                        signatureZipEntries.add(Pair.of(entryName, expectedData));
                    }
                }
                if (signatureZipEntries.isEmpty()) {
                    // v1 signature in the output is valid
                    return null;
                }
                // v1 signature in the output is not valid.
            }
        }

        if (signatureZipEntries.isEmpty()) {
            // v1 signature in the output is valid
            mV1SignaturePending = false;
            return null;
        }

        List<OutputJarSignatureRequest.JarEntry> sigEntries =
                new ArrayList<>(signatureZipEntries.size());
        for (Pair<String, byte[]> entry : signatureZipEntries) {
            String entryName = entry.getFirst();
            byte[] entryData = entry.getSecond();
            sigEntries.add(new OutputJarSignatureRequest.JarEntry(entryName, entryData));
            mEmittedSignatureJarEntryData.put(entryName, entryData);
        }
        mAddV1SignatureRequest = new OutputJarSignatureRequestImpl(sigEntries);
        return mAddV1SignatureRequest;
    }

    @Deprecated
    @Override
    public OutputApkSigningBlockRequest outputZipSections(
            DataSource zipEntries,
            DataSource zipCentralDirectory,
            DataSource zipEocd)
                    throws IOException, InvalidKeyException, SignatureException,
                            NoSuchAlgorithmException {
        return outputZipSectionsInternal(zipEntries, zipCentralDirectory, zipEocd, false);
    }

    @Override
    public OutputApkSigningBlockRequest2 outputZipSections2(
            DataSource zipEntries,
            DataSource zipCentralDirectory,
            DataSource zipEocd)
                    throws IOException, InvalidKeyException, SignatureException,
                            NoSuchAlgorithmException {
        return outputZipSectionsInternal(zipEntries, zipCentralDirectory, zipEocd, true);
    }

    private OutputApkSigningBlockRequestImpl outputZipSectionsInternal(
            DataSource zipEntries,
            DataSource zipCentralDirectory,
            DataSource zipEocd,
            boolean apkSigningBlockPaddingSupported)
                    throws IOException, InvalidKeyException, SignatureException,
                            NoSuchAlgorithmException {
        checkNotClosed();
        checkV1SigningDoneIfEnabled();
        if (!mV2SigningEnabled) {
            return null;
        }
        invalidateV2Signature();
        checkOutputApkNotDebuggableIfDebuggableMustBeRejected();

        // adjust to proper padding
        Pair<DataSource, Integer> paddingPair =
                ApkSigningBlockUtils.generateApkSigningBlockPadding(zipEntries,
                        apkSigningBlockPaddingSupported);
        DataSource beforeCentralDir = paddingPair.getFirst();
        int padSizeBeforeApkSigningBlock  = paddingPair.getSecond();
        DataSource eocd =
                ApkSigningBlockUtils.copyWithModifiedCDOffset(beforeCentralDir, zipEocd);

        // create APK Signature Scheme V2 Signature
        List<ApkSigningBlockUtils.SignerConfig> v2SignerConfigs =
                createV2SignerConfigs(apkSigningBlockPaddingSupported);
        Pair<byte[], Integer> apkSignatureSchemeV2Block =
                V2SchemeSigner.generateApkSignatureSchemeV2Block(beforeCentralDir,
                        zipCentralDirectory, eocd, v2SignerConfigs);

        // create APK Signing Block with v2
        byte[] apkSigningBlock =
                ApkSigningBlockUtils.generateApkSigningBlock(apkSignatureSchemeV2Block);

        mAddV2SignatureRequest = new OutputApkSigningBlockRequestImpl(apkSigningBlock,
                padSizeBeforeApkSigningBlock);
        return mAddV2SignatureRequest;
    }

    @Override
    public void outputDone() {
        checkNotClosed();
        checkV1SigningDoneIfEnabled();
        checkV2SigningDoneIfEnabled();
    }

    @Override
    public void close() {
        mClosed = true;

        mAddV1SignatureRequest = null;
        mInputJarManifestEntryDataRequest = null;
        mOutputAndroidManifestEntryDataRequest = null;
        mDebuggable = null;
        mOutputJarEntryDigestRequests.clear();
        mOutputJarEntryDigests.clear();
        mEmittedSignatureJarEntryData.clear();
        mOutputSignatureJarEntryDataRequests.clear();

        mAddV2SignatureRequest = null;
    }

    private void invalidateV1Signature() {
        if (mV1SigningEnabled) {
            mV1SignaturePending = true;
        }
        invalidateV2Signature();
    }

    private void invalidateV2Signature() {
        if (mV2SigningEnabled) {
            mV2SignaturePending = true;
            mAddV2SignatureRequest = null;
        }
    }

    private void checkNotClosed() {
        if (mClosed) {
            throw new IllegalStateException("Engine closed");
        }
    }

    private void checkV1SigningDoneIfEnabled() {
        if (!mV1SignaturePending) {
            return;
        }

        if (mAddV1SignatureRequest == null) {
            throw new IllegalStateException(
                    "v1 signature (JAR signature) not yet generated. Skipped outputJarEntries()?");
        }
        if (!mAddV1SignatureRequest.isDone()) {
            throw new IllegalStateException(
                    "v1 signature (JAR signature) addition requested by outputJarEntries() hasn't"
                            + " been fulfilled");
        }
        for (Map.Entry<String, byte[]> expectedOutputEntry
                : mEmittedSignatureJarEntryData.entrySet()) {
            String entryName = expectedOutputEntry.getKey();
            byte[] expectedData = expectedOutputEntry.getValue();
            GetJarEntryDataRequest actualDataRequest =
                    mOutputSignatureJarEntryDataRequests.get(entryName);
            if (actualDataRequest == null) {
                throw new IllegalStateException(
                        "APK entry " + entryName + " not yet output despite this having been"
                                + " requested");
            } else if (!actualDataRequest.isDone()) {
                throw new IllegalStateException(
                        "Still waiting to inspect output APK's " + entryName);
            }
            byte[] actualData = actualDataRequest.getData();
            if (!Arrays.equals(expectedData, actualData)) {
                throw new IllegalStateException(
                        "Output APK entry " + entryName + " data differs from what was requested");
            }
        }
        mV1SignaturePending = false;
    }

    private void checkV2SigningDoneIfEnabled() {
        if (!mV2SignaturePending) {
            return;
        }
        if (mAddV2SignatureRequest == null) {
            throw new IllegalStateException(
                    "v2 signature (APK Signature Scheme v2 signature) not yet generated."
                            + " Skipped outputZipSections()?");
        }
        if (!mAddV2SignatureRequest.isDone()) {
            throw new IllegalStateException(
                    "v2 signature (APK Signature Scheme v2 signature) addition requested by"
                            + " outputZipSections() hasn't been fulfilled yet");
        }
        mAddV2SignatureRequest = null;
        mV2SignaturePending = false;
    }

    private void checkOutputApkNotDebuggableIfDebuggableMustBeRejected()
            throws SignatureException {
        if (mDebuggableApkPermitted) {
            return;
        }

        try {
            if (isOutputApkDebuggable()) {
                throw new SignatureException(
                        "APK is debuggable (see android:debuggable attribute) and this engine is"
                                + " configured to refuse to sign debuggable APKs");
            }
        } catch (ApkFormatException e) {
            throw new SignatureException("Failed to determine whether the APK is debuggable", e);
        }
    }

    /**
     * Returns whether the output APK is debuggable according to its
     * {@code android:debuggable} declaration.
     */
    private boolean isOutputApkDebuggable() throws ApkFormatException {
        if (mDebuggable != null) {
            return mDebuggable;
        }

        if (mOutputAndroidManifestEntryDataRequest == null) {
            throw new IllegalStateException(
                    "Cannot determine debuggable status of output APK because "
                            + ApkUtils.ANDROID_MANIFEST_ZIP_ENTRY_NAME
                            + " entry contents have not yet been requested");
        }

        if (!mOutputAndroidManifestEntryDataRequest.isDone()) {
            throw new IllegalStateException(
                    "Still waiting to inspect output APK's "
                            + mOutputAndroidManifestEntryDataRequest.getEntryName());
        }
        mDebuggable =
                ApkUtils.getDebuggableFromBinaryAndroidManifest(
                        ByteBuffer.wrap(mOutputAndroidManifestEntryDataRequest.getData()));
        return mDebuggable;
    }

    private void forgetOutputApkDebuggableStatus() {
        mDebuggable = null;
    }

    /**
     * Returns the output policy for the provided input JAR entry.
     */
    private InputJarEntryInstructions.OutputPolicy getInputJarEntryOutputPolicy(String entryName) {
        if (mSignatureExpectedOutputJarEntryNames.contains(entryName)) {
            return InputJarEntryInstructions.OutputPolicy.OUTPUT_BY_ENGINE;
        }
        if ((mOtherSignersSignaturesPreserved)
                || (V1SchemeSigner.isJarEntryDigestNeededInManifest(entryName))) {
            return InputJarEntryInstructions.OutputPolicy.OUTPUT;
        }
        return InputJarEntryInstructions.OutputPolicy.SKIP;
    }

    private static class OutputJarSignatureRequestImpl implements OutputJarSignatureRequest {
        private final List<JarEntry> mAdditionalJarEntries;
        private volatile boolean mDone;

        private OutputJarSignatureRequestImpl(List<JarEntry> additionalZipEntries) {
            mAdditionalJarEntries =
                    Collections.unmodifiableList(new ArrayList<>(additionalZipEntries));
        }

        @Override
        public List<JarEntry> getAdditionalJarEntries() {
            return mAdditionalJarEntries;
        }

        @Override
        public void done() {
            mDone = true;
        }

        private boolean isDone() {
            return mDone;
        }
    }

    @SuppressWarnings("deprecation")
    private static class OutputApkSigningBlockRequestImpl
            implements OutputApkSigningBlockRequest, OutputApkSigningBlockRequest2 {
        private final byte[] mApkSigningBlock;
        private final int mPaddingBeforeApkSigningBlock;
        private volatile boolean mDone;

        private OutputApkSigningBlockRequestImpl(byte[] apkSigingBlock, int paddingBefore) {
            mApkSigningBlock = apkSigingBlock.clone();
            mPaddingBeforeApkSigningBlock = paddingBefore;
        }

        @Override
        public byte[] getApkSigningBlock() {
            return mApkSigningBlock.clone();
        }

        @Override
        public void done() {
            mDone = true;
        }

        private boolean isDone() {
            return mDone;
        }

        @Override
        public int getPaddingSizeBeforeApkSigningBlock() {
            return mPaddingBeforeApkSigningBlock;
        }
    }

    /**
     * JAR entry inspection request which obtain the entry's uncompressed data.
     */
    private static class GetJarEntryDataRequest implements InspectJarEntryRequest {
        private final String mEntryName;
        private final Object mLock = new Object();

        private boolean mDone;
        private DataSink mDataSink;
        private ByteArrayOutputStream mDataSinkBuf;

        private GetJarEntryDataRequest(String entryName) {
            mEntryName = entryName;
        }

        @Override
        public String getEntryName() {
            return mEntryName;
        }

        @Override
        public DataSink getDataSink() {
            synchronized (mLock) {
                checkNotDone();
                if (mDataSinkBuf == null) {
                    mDataSinkBuf = new ByteArrayOutputStream();
                }
                if (mDataSink == null) {
                    mDataSink = DataSinks.asDataSink(mDataSinkBuf);
                }
                return mDataSink;
            }
        }

        @Override
        public void done() {
            synchronized (mLock) {
                if (mDone) {
                    return;
                }
                mDone = true;
            }
        }

        private boolean isDone() {
            synchronized (mLock) {
                return mDone;
            }
        }

        private void checkNotDone() throws IllegalStateException {
            synchronized (mLock) {
                if (mDone) {
                    throw new IllegalStateException("Already done");
                }
            }
        }

        private byte[] getData() {
            synchronized (mLock) {
                if (!mDone) {
                    throw new IllegalStateException("Not yet done");
                }
                return (mDataSinkBuf != null) ? mDataSinkBuf.toByteArray() : new byte[0];
            }
        }
    }

    /**
     * JAR entry inspection request which obtains the digest of the entry's uncompressed data.
     */
    private static class GetJarEntryDataDigestRequest implements InspectJarEntryRequest {
        private final String mEntryName;
        private final String mJcaDigestAlgorithm;
        private final Object mLock = new Object();

        private boolean mDone;
        private DataSink mDataSink;
        private MessageDigest mMessageDigest;
        private byte[] mDigest;

        private GetJarEntryDataDigestRequest(String entryName, String jcaDigestAlgorithm) {
            mEntryName = entryName;
            mJcaDigestAlgorithm = jcaDigestAlgorithm;
        }

        @Override
        public String getEntryName() {
            return mEntryName;
        }

        @Override
        public DataSink getDataSink() {
            synchronized (mLock) {
                checkNotDone();
                if (mDataSink == null) {
                    mDataSink = DataSinks.asDataSink(getMessageDigest());
                }
                return mDataSink;
            }
        }

        private MessageDigest getMessageDigest() {
            synchronized (mLock) {
                if (mMessageDigest == null) {
                    try {
                        mMessageDigest = MessageDigest.getInstance(mJcaDigestAlgorithm);
                    } catch (NoSuchAlgorithmException e) {
                        throw new RuntimeException(
                                mJcaDigestAlgorithm + " MessageDigest not available", e);
                    }
                }
                return mMessageDigest;
            }
        }

        @Override
        public void done() {
            synchronized (mLock) {
                if (mDone) {
                    return;
                }
                mDone = true;
                mDigest = getMessageDigest().digest();
                mMessageDigest = null;
                mDataSink = null;
            }
        }

        private boolean isDone() {
            synchronized (mLock) {
                return mDone;
            }
        }

        private void checkNotDone() throws IllegalStateException {
            synchronized (mLock) {
                if (mDone) {
                    throw new IllegalStateException("Already done");
                }
            }
        }

        private byte[] getDigest() {
            synchronized (mLock) {
                if (!mDone) {
                    throw new IllegalStateException("Not yet done");
                }
                return mDigest.clone();
            }
        }
    }

    /**
     * JAR entry inspection request which transparently satisfies multiple such requests.
     */
    private static class CompoundInspectJarEntryRequest implements InspectJarEntryRequest {
        private final String mEntryName;
        private final InspectJarEntryRequest[] mRequests;
        private final Object mLock = new Object();

        private DataSink mSink;

        private CompoundInspectJarEntryRequest(
                String entryName, InspectJarEntryRequest... requests) {
            mEntryName = entryName;
            mRequests = requests;
        }

        @Override
        public String getEntryName() {
            return mEntryName;
        }

        @Override
        public DataSink getDataSink() {
            synchronized (mLock) {
                if (mSink == null) {
                    DataSink[] sinks = new DataSink[mRequests.length];
                    for (int i = 0; i < sinks.length; i++) {
                        sinks[i] = mRequests[i].getDataSink();
                    }
                    mSink = new TeeDataSink(sinks);
                }
                return mSink;
            }
        }

        @Override
        public void done() {
            for (InspectJarEntryRequest request : mRequests) {
                request.done();
            }
        }
    }

    /**
     * Configuration of a signer.
     *
     * <p>Use {@link Builder} to obtain configuration instances.
     */
    public static class SignerConfig {
        private final String mName;
        private final PrivateKey mPrivateKey;
        private final List<X509Certificate> mCertificates;

        private SignerConfig(
                String name,
                PrivateKey privateKey,
                List<X509Certificate> certificates) {
            mName = name;
            mPrivateKey = privateKey;
            mCertificates = Collections.unmodifiableList(new ArrayList<>(certificates));
        }

        /**
         * Returns the name of this signer.
         */
        public String getName() {
            return mName;
        }

        /**
         * Returns the signing key of this signer.
         */
        public PrivateKey getPrivateKey() {
            return mPrivateKey;
        }

        /**
         * Returns the certificate(s) of this signer. The first certificate's public key corresponds
         * to this signer's private key.
         */
        public List<X509Certificate> getCertificates() {
            return mCertificates;
        }

        /**
         * Builder of {@link SignerConfig} instances.
         */
        public static class Builder {
            private final String mName;
            private final PrivateKey mPrivateKey;
            private final List<X509Certificate> mCertificates;

            /**
             * Constructs a new {@code Builder}.
             *
             * @param name signer's name. The name is reflected in the name of files comprising the
             *        JAR signature of the APK.
             * @param privateKey signing key
             * @param certificates list of one or more X.509 certificates. The subject public key of
             *        the first certificate must correspond to the {@code privateKey}.
             */
            public Builder(
                    String name,
                    PrivateKey privateKey,
                    List<X509Certificate> certificates) {
                if (name.isEmpty()) {
                    throw new IllegalArgumentException("Empty name");
                }
                mName = name;
                mPrivateKey = privateKey;
                mCertificates = new ArrayList<>(certificates);
            }

            /**
             * Returns a new {@code SignerConfig} instance configured based on the configuration of
             * this builder.
             */
            public SignerConfig build() {
                return new SignerConfig(
                        mName,
                        mPrivateKey,
                        mCertificates);
            }
        }
    }

    /**
     * Builder of {@link DefaultApkSignerEngine} instances.
     */
    public static class Builder {
        private final List<SignerConfig> mSignerConfigs;
        private final int mMinSdkVersion;

        private boolean mV1SigningEnabled = true;
        private boolean mV2SigningEnabled = true;
        private boolean mDebuggableApkPermitted = true;
        private boolean mOtherSignersSignaturesPreserved;
        private String mCreatedBy = "1.0 (Android)";

        /**
         * Constructs a new {@code Builder}.
         *
         * @param signerConfigs information about signers with which the APK will be signed. At
         *        least one signer configuration must be provided.
         * @param minSdkVersion API Level of the oldest Android platform on which the APK is
         *        supposed to be installed. See {@code minSdkVersion} attribute in the APK's
         *        {@code AndroidManifest.xml}. The higher the version, the stronger signing features
         *        will be enabled.
         */
        public Builder(
                List<SignerConfig> signerConfigs,
                int minSdkVersion) {
            if (signerConfigs.isEmpty()) {
                throw new IllegalArgumentException("At least one signer config must be provided");
            }
            mSignerConfigs = new ArrayList<>(signerConfigs);
            mMinSdkVersion = minSdkVersion;
        }

        /**
         * Returns a new {@code DefaultApkSignerEngine} instance configured based on the
         * configuration of this builder.
         */
        public DefaultApkSignerEngine build() throws InvalidKeyException {
            return new DefaultApkSignerEngine(
                    mSignerConfigs,
                    mMinSdkVersion,
                    mV1SigningEnabled,
                    mV2SigningEnabled,
                    mDebuggableApkPermitted,
                    mOtherSignersSignaturesPreserved,
                    mCreatedBy);
        }

        /**
         * Sets whether the APK should be signed using JAR signing (aka v1 signature scheme).
         *
         * <p>By default, the APK will be signed using this scheme.
         */
        public Builder setV1SigningEnabled(boolean enabled) {
            mV1SigningEnabled = enabled;
            return this;
        }

        /**
         * Sets whether the APK should be signed using APK Signature Scheme v2 (aka v2 signature
         * scheme).
         *
         * <p>By default, the APK will be signed using this scheme.
         */
        public Builder setV2SigningEnabled(boolean enabled) {
            mV2SigningEnabled = enabled;
            return this;
        }

        /**
         * Sets whether the APK should be signed even if it is marked as debuggable
         * ({@code android:debuggable="true"} in its {@code AndroidManifest.xml}). For backward
         * compatibility reasons, the default value of this setting is {@code true}.
         *
         * <p>It is dangerous to sign debuggable APKs with production/release keys because Android
         * platform loosens security checks for such APKs. For example, arbitrary unauthorized code
         * may be executed in the context of such an app by anybody with ADB shell access.
         */
        public Builder setDebuggableApkPermitted(boolean permitted) {
            mDebuggableApkPermitted = permitted;
            return this;
        }

        /**
         * Sets whether signatures produced by signers other than the ones configured in this engine
         * should be copied from the input APK to the output APK.
         *
         * <p>By default, signatures of other signers are omitted from the output APK.
         */
        public Builder setOtherSignersSignaturesPreserved(boolean preserved) {
            mOtherSignersSignaturesPreserved = preserved;
            return this;
        }

        /**
         * Sets the value of the {@code Created-By} field in JAR signature files.
         */
        public Builder setCreatedBy(String createdBy) {
            if (createdBy == null) {
                throw new NullPointerException();
            }
            mCreatedBy = createdBy;
            return this;
        }
    }
}
