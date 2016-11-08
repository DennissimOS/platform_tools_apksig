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

package com.android.apksigner;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import com.android.apksig.apk.MinSdkVersionException;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.DSAKey;
import java.security.interfaces.DSAParams;
import java.security.interfaces.ECKey;
import java.security.interfaces.RSAKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;

import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Command-line tool for signing APKs and for checking whether an APK's signature are expected to
 * verify on Android devices.
 */
public class ApkSignerTool {

    private static final String VERSION = "0.2";
    private static final String HELP_PAGE_GENERAL = "help.txt";
    private static final String HELP_PAGE_SIGN = "help_sign.txt";
    private static final String HELP_PAGE_VERIFY = "help_verify.txt";

    public static void main(String[] params) throws Exception {
        if ((params.length == 0) || ("--help".equals(params[0])) || ("-h".equals(params[0]))) {
            printUsage(HELP_PAGE_GENERAL);
            return;
        } else if ("--version".equals(params[0])) {
            System.out.println(VERSION);
            return;
        }

        String cmd = params[0];
        try {
            if ("sign".equals(cmd)) {
                sign(Arrays.copyOfRange(params, 1, params.length));
                return;
            } else if ("verify".equals(cmd)) {
                verify(Arrays.copyOfRange(params, 1, params.length));
                return;
            } else if ("help".equals(cmd)) {
                printUsage(HELP_PAGE_GENERAL);
                return;
            } else if ("version".equals(cmd)) {
                System.out.println(VERSION);
                return;
            } else {
                throw new ParameterException(
                        "Unsupported command: " + cmd + ". See --help for supported commands");
            }
        } catch (ParameterException | OptionsParser.OptionsException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
    }

    private static void sign(String[] params) throws Exception {
        if (params.length == 0) {
            printUsage(HELP_PAGE_SIGN);
            return;
        }

        File outputApk = null;
        boolean verbose = false;
        boolean v1SigningEnabled = true;
        boolean v2SigningEnabled = true;
        int minSdkVersion = 1;
        boolean minSdkVersionSpecified = false;
        int maxSdkVersion = Integer.MAX_VALUE;
        List<SignerParams> signers = new ArrayList<>(1);
        SignerParams signerParams = new SignerParams();
        OptionsParser optionsParser = new OptionsParser(params);
        String optionName;
        while ((optionName = optionsParser.nextOption()) != null) {
            String optionOriginalForm = optionsParser.getOptionOriginalForm();
            if (("help".equals(optionName)) || ("h".equals(optionName))) {
                printUsage(HELP_PAGE_SIGN);
                return;
            } else if ("out".equals(optionName)) {
                outputApk = new File(optionsParser.getRequiredValue("Output file name"));
            } else if ("min-sdk-version".equals(optionName)) {
                minSdkVersion = optionsParser.getRequiredIntValue("Mininimum API Level");
                minSdkVersionSpecified = true;
            } else if ("max-sdk-version".equals(optionName)) {
                maxSdkVersion = optionsParser.getRequiredIntValue("Maximum API Level");
            } else if ("v1-signing-enabled".equals(optionName)) {
                v1SigningEnabled = optionsParser.getOptionalBooleanValue(true);
            } else if ("v2-signing-enabled".equals(optionName)) {
                v2SigningEnabled = optionsParser.getOptionalBooleanValue(true);
            } else if ("next-signer".equals(optionName)) {
                if (!signerParams.isEmpty()) {
                    signers.add(signerParams);
                    signerParams = new SignerParams();
                }
            } else if ("ks".equals(optionName)) {
                signerParams.keystoreFile = optionsParser.getRequiredValue("KeyStore file");
            } else if ("ks-key-alias".equals(optionName)) {
                signerParams.keystoreKeyAlias =
                        optionsParser.getRequiredValue("KeyStore key alias");
            } else if ("ks-pass".equals(optionName)) {
                signerParams.keystorePasswordSpec =
                        optionsParser.getRequiredValue("KeyStore password");
            } else if ("key-pass".equals(optionName)) {
                signerParams.keyPasswordSpec = optionsParser.getRequiredValue("Key password");
            } else if ("v1-signer-name".equals(optionName)) {
                signerParams.v1SigFileBasename =
                        optionsParser.getRequiredValue("JAR signature file basename");
            } else if ("ks-type".equals(optionName)) {
                signerParams.keystoreType = optionsParser.getRequiredValue("KeyStore type");
            } else if ("ks-provider-name".equals(optionName)) {
                signerParams.keystoreProviderName =
                        optionsParser.getRequiredValue("JCA KeyStore Provider name");
            } else if ("ks-provider-class".equals(optionName)) {
                signerParams.keystoreProviderClass =
                        optionsParser.getRequiredValue("JCA KeyStore Provider class name");
            } else if ("ks-provider-arg".equals(optionName)) {
                signerParams.keystoreProviderArg =
                        optionsParser.getRequiredValue(
                                "JCA KeyStore Provider constructor argument");
            } else if ("key".equals(optionName)) {
                signerParams.keyFile = optionsParser.getRequiredValue("Private key file");
            } else if ("cert".equals(optionName)) {
                signerParams.certFile = optionsParser.getRequiredValue("Certificate file");
            } else if (("v".equals(optionName)) || ("verbose".equals(optionName))) {
                verbose = optionsParser.getOptionalBooleanValue(true);
            } else {
                throw new ParameterException(
                        "Unsupported option: " + optionOriginalForm + ". See --help for supported"
                                + " options.");
            }
        }
        if (!signerParams.isEmpty()) {
            signers.add(signerParams);
        }
        signerParams = null;

        if (signers.isEmpty()) {
            throw new ParameterException("At least one signer must be specified");
        }

        params = optionsParser.getRemainingParams();
        if (params.length < 1) {
            throw new ParameterException("Missing input APK");
        } else if (params.length > 1) {
            throw new ParameterException(
                    "Unexpected parameter(s) after input APK (" + params[0] + ")");
        }
        if ((minSdkVersionSpecified) && (minSdkVersion > maxSdkVersion)) {
            throw new ParameterException(
                    "Min API Level (" + minSdkVersion + ") > max API Level (" + maxSdkVersion
                            + ")");
        }

        List<ApkSigner.SignerConfig> signerConfigs = new ArrayList<>(signers.size());
        int signerNumber = 0;
        try (PasswordRetriever passwordRetriever = new PasswordRetriever()) {
            for (SignerParams signer : signers) {
                signerNumber++;
                signer.name = "signer #" + signerNumber;
                try {
                    signer.loadPrivateKeyAndCerts(passwordRetriever);
                } catch (ParameterException e) {
                    System.err.println(
                            "Failed to load signer \"" + signer.name + "\": "
                                    + e.getMessage());
                    System.exit(2);
                    return;
                } catch (Exception e) {
                    System.err.println("Failed to load signer \"" + signer.name + "\"");
                    e.printStackTrace();
                    System.exit(2);
                    return;
                }
                String v1SigBasename;
                if (signer.v1SigFileBasename != null) {
                    v1SigBasename = signer.v1SigFileBasename;
                } else if (signer.keystoreKeyAlias != null) {
                    v1SigBasename = signer.keystoreKeyAlias;
                } else if (signer.keyFile != null) {
                    String keyFileName = new File(signer.keyFile).getName();
                    int delimiterIndex = keyFileName.indexOf('.');
                    if (delimiterIndex == -1) {
                        v1SigBasename = keyFileName;
                    } else {
                        v1SigBasename = keyFileName.substring(0, delimiterIndex);
                    }
                } else {
                    throw new RuntimeException(
                            "Neither KeyStore key alias nor private key file available");
                }
                ApkSigner.SignerConfig signerConfig =
                        new ApkSigner.SignerConfig.Builder(
                                v1SigBasename, signer.privateKey, signer.certs)
                        .build();
                signerConfigs.add(signerConfig);
            }
        }

        File inputApk = new File(params[0]);
        if (outputApk == null) {
            outputApk = inputApk;
        }
        File tmpOutputApk;
        if (inputApk.getCanonicalPath().equals(outputApk.getCanonicalPath())) {
            tmpOutputApk = File.createTempFile("apksigner", ".apk");
            tmpOutputApk.deleteOnExit();
        } else {
            tmpOutputApk = outputApk;
        }
        ApkSigner.Builder apkSignerBuilder =
                new ApkSigner.Builder(signerConfigs)
                        .setInputApk(inputApk)
                        .setOutputApk(tmpOutputApk)
                        .setOtherSignersSignaturesPreserved(false)
                        .setV1SigningEnabled(v1SigningEnabled)
                        .setV2SigningEnabled(v2SigningEnabled)
                        .setCreatedBy(VERSION + " (Android apksigner)");
        if (minSdkVersionSpecified) {
            apkSignerBuilder.setMinSdkVersion(minSdkVersion);
        }
        ApkSigner apkSigner = apkSignerBuilder.build();
        try {
            apkSigner.sign();
        } catch (MinSdkVersionException e) {
            String msg = e.getMessage();
            if (!msg.endsWith(".")) {
                msg += '.';
            }
            throw new ParameterException(msg + " Use --min-sdk-version to override.");
        }
        if (!tmpOutputApk.getCanonicalPath().equals(outputApk.getCanonicalPath())) {
            FileSystem fs = FileSystems.getDefault();
            Files.move(
                    fs.getPath(tmpOutputApk.getPath()),
                    fs.getPath(outputApk.getPath()),
                    StandardCopyOption.REPLACE_EXISTING);
        }

        if (verbose) {
            System.out.println("Signed");
        }
    }

    private static void verify(String[] params) throws Exception {
        if (params.length == 0) {
            printUsage(HELP_PAGE_VERIFY);
            return;
        }

        int minSdkVersion = 1;
        boolean minSdkVersionSpecified = false;
        int maxSdkVersion = Integer.MAX_VALUE;
        boolean maxSdkVersionSpecified = false;
        boolean printCerts = false;
        boolean verbose = false;
        boolean warningsTreatedAsErrors = false;
        OptionsParser optionsParser = new OptionsParser(params);
        String optionName;
        while ((optionName = optionsParser.nextOption()) != null) {
            String optionOriginalForm = optionsParser.getOptionOriginalForm();
            if ("min-sdk-version".equals(optionName)) {
                minSdkVersion = optionsParser.getRequiredIntValue("Mininimum API Level");
                minSdkVersionSpecified = true;
            } else if ("max-sdk-version".equals(optionName)) {
                maxSdkVersion = optionsParser.getRequiredIntValue("Maximum API Level");
                maxSdkVersionSpecified = true;
            } else if ("print-certs".equals(optionName)) {
                printCerts = optionsParser.getOptionalBooleanValue(true);
            } else if (("v".equals(optionName)) || ("verbose".equals(optionName))) {
                verbose = optionsParser.getOptionalBooleanValue(true);
            } else if ("Werr".equals(optionName)) {
                warningsTreatedAsErrors = optionsParser.getOptionalBooleanValue(true);
            } else if (("help".equals(optionName)) || ("h".equals(optionName))) {
                printUsage(HELP_PAGE_VERIFY);
                return;
            } else {
                throw new ParameterException(
                        "Unsupported option: " + optionOriginalForm + ". See --help for supported"
                                + " options.");
            }
        }
        params = optionsParser.getRemainingParams();

        if (params.length < 1) {
            throw new ParameterException("Missing APK");
        } else if (params.length > 1) {
            throw new ParameterException("Unexpected parameter(s) after APK (" + params[0] + ")");
        }

        if ((minSdkVersionSpecified) && (maxSdkVersionSpecified)
                && (minSdkVersion > maxSdkVersion)) {
            throw new ParameterException(
                    "Min API Level (" + minSdkVersion + ") > max API Level (" + maxSdkVersion
                            + ")");
        }

        File inputApk = new File(params[0]);
        ApkVerifier.Builder apkVerifierBuilder = new ApkVerifier.Builder(inputApk);
        if (minSdkVersionSpecified) {
            apkVerifierBuilder.setMinCheckedPlatformVersion(minSdkVersion);
        }
        if (maxSdkVersionSpecified) {
            apkVerifierBuilder.setMaxCheckedPlatformVersion(maxSdkVersion);
        }
        ApkVerifier apkVerifier = apkVerifierBuilder.build();
        ApkVerifier.Result result;
        try {
            result = apkVerifier.verify();
        } catch (MinSdkVersionException e) {
            String msg = e.getMessage();
            if (!msg.endsWith(".")) {
                msg += '.';
            }
            throw new ParameterException(msg + " Use --min-sdk-version to override.");
        }
        boolean verified = result.isVerified();

        boolean warningsEncountered = false;
        if (verified) {
            List<X509Certificate> signerCerts = result.getSignerCertificates();
            if (verbose) {
                System.out.println("Verifies");
                System.out.println(
                        "Verified using v1 scheme (JAR signing): "
                                + result.isVerifiedUsingV1Scheme());
                System.out.println(
                        "Verified using v2 scheme (APK Signature Scheme v2): "
                                + result.isVerifiedUsingV2Scheme());
                System.out.println("Number of signers: " + signerCerts.size());
            }
            if (printCerts) {
                int signerNumber = 0;
                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
                MessageDigest md5 = MessageDigest.getInstance("MD5");
                for (X509Certificate signerCert : signerCerts) {
                    signerNumber++;
                    System.out.println(
                            "Signer #" + signerNumber + " certificate DN"
                                    + ": " + signerCert.getSubjectDN());
                    byte[] encodedCert = signerCert.getEncoded();
                    System.out.println(
                            "Signer #" + signerNumber + " certificate SHA-256 digest: "
                                    + HexEncoding.encode(sha256.digest(encodedCert)));
                    System.out.println(
                            "Signer #" + signerNumber + " certificate SHA-1 digest: "
                                    + HexEncoding.encode(sha1.digest(encodedCert)));
                    System.out.println(
                            "Signer #" + signerNumber + " certificate MD5 digest: "
                                    + HexEncoding.encode(md5.digest(encodedCert)));
                    if (verbose) {
                        PublicKey publicKey = signerCert.getPublicKey();
                        System.out.println(
                                "Signer #" + signerNumber + " key algorithm: "
                                        + publicKey.getAlgorithm());
                        int keySize = -1;
                        if (publicKey instanceof RSAKey) {
                            keySize = ((RSAKey) publicKey).getModulus().bitLength();
                        } else if (publicKey instanceof ECKey) {
                            keySize = ((ECKey) publicKey).getParams()
                                    .getOrder().bitLength();
                        } else if (publicKey instanceof DSAKey) {
                            // DSA parameters may be inherited from the certificate. We
                            // don't handle this case at the moment.
                            DSAParams dsaParams = ((DSAKey) publicKey).getParams();
                            if (dsaParams != null) {
                                keySize = dsaParams.getP().bitLength();
                            }
                        }
                        System.out.println(
                                "Signer #" + signerNumber + " key size (bits): "
                                        + ((keySize != -1)
                                                ? String.valueOf(keySize) : "n/a"));
                        byte[] encodedKey = publicKey.getEncoded();
                        System.out.println(
                                "Signer #" + signerNumber + " public key SHA-256 digest: "
                                        + HexEncoding.encode(sha256.digest(encodedKey)));
                        System.out.println(
                                "Signer #" + signerNumber + " public key SHA-1 digest: "
                                        + HexEncoding.encode(sha1.digest(encodedKey)));
                        System.out.println(
                                "Signer #" + signerNumber + " public key MD5 digest: "
                                        + HexEncoding.encode(md5.digest(encodedKey)));
                    }
                }
            }
        } else {
            System.err.println("DOES NOT VERIFY");
        }

        for (ApkVerifier.IssueWithParams error : result.getErrors()) {
            System.err.println("ERROR: " + error);
        }

        @SuppressWarnings("resource") // false positive -- this resource is not opened here
        PrintStream warningsOut = (warningsTreatedAsErrors) ? System.err : System.out;
        for (ApkVerifier.IssueWithParams warning : result.getWarnings()) {
            warningsEncountered = true;
            warningsOut.println("WARNING: " + warning);
        }
        for (ApkVerifier.Result.V1SchemeSignerInfo signer : result.getV1SchemeSigners()) {
            String signerName = signer.getName();
            for (ApkVerifier.IssueWithParams error : signer.getErrors()) {
                System.err.println("ERROR: JAR signer " + signerName + ": " + error);
            }
            for (ApkVerifier.IssueWithParams warning : signer.getWarnings()) {
                warningsEncountered = true;
                warningsOut.println("WARNING: JAR signer " + signerName + ": " + warning);
            }
        }
        for (ApkVerifier.Result.V2SchemeSignerInfo signer : result.getV2SchemeSigners()) {
            String signerName = "signer #" + (signer.getIndex() + 1);
            for (ApkVerifier.IssueWithParams error : signer.getErrors()) {
                System.err.println(
                        "ERROR: APK Signature Scheme v2 " + signerName + ": " + error);
            }
            for (ApkVerifier.IssueWithParams warning : signer.getWarnings()) {
                warningsEncountered = true;
                warningsOut.println(
                        "WARNING: APK Signature Scheme v2 " + signerName + ": " + warning);
            }
        }

        if (!verified) {
            System.exit(1);
            return;
        }
        if ((warningsTreatedAsErrors) && (warningsEncountered)) {
            System.exit(1);
            return;
        }
    }

    private static void printUsage(String page) {
        try (BufferedReader in =
                new BufferedReader(
                        new InputStreamReader(
                                ApkSignerTool.class.getResourceAsStream(page),
                                StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                System.out.println(line);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + page + " resource");
        }
    }

    private static class SignerParams {
        String name;

        String keystoreFile;
        String keystoreKeyAlias;
        String keystorePasswordSpec;
        String keyPasswordSpec;
        String keystoreType;
        String keystoreProviderName;
        String keystoreProviderClass;
        String keystoreProviderArg;

        String keyFile;
        String certFile;

        String v1SigFileBasename;

        PrivateKey privateKey;
        List<X509Certificate> certs;

        private boolean isEmpty() {
            return (name == null)
                    && (keystoreFile == null)
                    && (keystoreKeyAlias == null)
                    && (keystorePasswordSpec == null)
                    && (keyPasswordSpec == null)
                    && (keystoreType == null)
                    && (keystoreProviderName == null)
                    && (keystoreProviderClass == null)
                    && (keystoreProviderArg == null)
                    && (keyFile == null)
                    && (certFile == null)
                    && (v1SigFileBasename == null)
                    && (privateKey == null)
                    && (certs == null);
        }

        private void loadPrivateKeyAndCerts(PasswordRetriever passwordRetriever) throws Exception {
            if (keystoreFile != null) {
                if (keyFile != null) {
                    throw new ParameterException(
                            "--ks and --key may not be specified at the same time");
                } else if (certFile != null) {
                    throw new ParameterException(
                            "--ks and --cert may not be specified at the same time");
                }
                loadPrivateKeyAndCertsFromKeyStore(passwordRetriever);
            } else if (keyFile != null) {
                loadPrivateKeyAndCertsFromFiles(passwordRetriever);
            } else {
                throw new ParameterException(
                        "KeyStore (--ks) or private key file (--key) must be specified");
            }
        }

        private void loadPrivateKeyAndCertsFromKeyStore(PasswordRetriever passwordRetriever)
                throws Exception {
            if (keystoreFile == null) {
                throw new ParameterException("KeyStore (--ks) must be specified");
            }

            // 1. Obtain a KeyStore implementation
            String ksType = (keystoreType != null) ? keystoreType : KeyStore.getDefaultType();
            KeyStore ks;
            if (keystoreProviderName != null) {
                // Use a named Provider (assumes the provider is already installed)
                ks = KeyStore.getInstance(ksType, keystoreProviderName);
            } else if (keystoreProviderClass != null) {
                // Use a new Provider instance (does not require the provider to be installed)
                Class<?> ksProviderClass = Class.forName(keystoreProviderClass);
                if (!Provider.class.isAssignableFrom(ksProviderClass)) {
                    throw new ParameterException(
                            "Keystore Provider class " + keystoreProviderClass + " not subclass of "
                                    + Provider.class.getName());
                }
                Provider ksProvider;
                if (keystoreProviderArg != null) {
                    // Single-arg Provider constructor
                    ksProvider =
                            (Provider) ksProviderClass.getConstructor(String.class)
                                    .newInstance(keystoreProviderArg);
                } else {
                    // No-arg Provider constructor
                    ksProvider = (Provider) ksProviderClass.getConstructor().newInstance();
                }
                ks = KeyStore.getInstance(ksType, ksProvider);
            } else {
                // Use the highest-priority Provider which offers the requested KeyStore type
                ks = KeyStore.getInstance(ksType);
            }

            // 2. Load the KeyStore
            char[] keystorePwd = null;
            if ("NONE".equals(keystoreFile)) {
                ks.load(null);
            } else {
                String keystorePasswordSpec =
                        (this.keystorePasswordSpec != null)
                                ?  this.keystorePasswordSpec : PasswordRetriever.SPEC_STDIN;
                String keystorePwdString =
                        passwordRetriever.getPassword(
                                keystorePasswordSpec, "Keystore password for " + name);
                keystorePwd = keystorePwdString.toCharArray();
                try (FileInputStream in = new FileInputStream(keystoreFile)) {
                    ks.load(in, keystorePwd);
                }
            }

            // 3. Load the PrivateKey and cert chain from KeyStore
            char[] keyPwd;
            if (keyPasswordSpec == null) {
                keyPwd = keystorePwd;
            } else {
                keyPwd =
                        passwordRetriever.getPassword(keyPasswordSpec, "Key password for " + name)
                                .toCharArray();
            }
            String keyAlias = null;
            PrivateKey key = null;
            try {
                if (keystoreKeyAlias == null) {
                    // Private key entry alias not specified. Find the key entry contained in this
                    // KeyStore. If the KeyStore contains multiple key entries, return an error.
                    Enumeration<String> aliases = ks.aliases();
                    if (aliases != null) {
                        while (aliases.hasMoreElements()) {
                            String entryAlias = aliases.nextElement();
                            if (ks.isKeyEntry(entryAlias)) {
                                keyAlias = entryAlias;
                                if (keystoreKeyAlias != null) {
                                    throw new ParameterException(
                                            keystoreFile + " contains multiple key entries"
                                            + ". --ks-key-alias option must be used to specify"
                                            + " which entry to use.");
                                }
                                keystoreKeyAlias = keyAlias;
                            }
                        }
                    }
                    if (keystoreKeyAlias == null) {
                        throw new ParameterException(
                                keystoreFile + " does not contain key entries");
                    }
                }

                // Private key entry alias known. Load that entry's private key.
                keyAlias = keystoreKeyAlias;
                if (!ks.isKeyEntry(keyAlias)) {
                    throw new ParameterException(
                            keystoreFile + " entry \"" + keyAlias + "\" does not contain a key");
                }
                Key entryKey;
                if (keyPwd != null) {
                    // Key password specified -- load this key as a password-protected key
                    entryKey = ks.getKey(keyAlias, keyPwd);
                } else {
                    // Key password not specified -- try to load this key without using a password
                    try {
                        entryKey = ks.getKey(keyAlias, null);
                    } catch (UnrecoverableKeyException expected) {
                        // Looks like this might be a password-protected key. Prompt for password
                        // and try loading the key using the password.
                        keyPwd =
                                passwordRetriever.getPassword(
                                        PasswordRetriever.SPEC_STDIN,
                                        "Password for key with alias \"" + keyAlias + "\"")
                                                .toCharArray();
                        entryKey = ks.getKey(keyAlias, keyPwd);
                    }
                }
                if (entryKey == null) {
                    throw new ParameterException(
                            keystoreFile + " entry \"" + keyAlias + "\" does not contain a key");
                } else if (!(entryKey instanceof PrivateKey)) {
                    throw new ParameterException(
                            keystoreFile + " entry \"" + keyAlias + "\" does not contain a private"
                                    + " key. It contains a key of algorithm: "
                                    + entryKey.getAlgorithm());
                }
                key = (PrivateKey) entryKey;
            } catch (UnrecoverableKeyException e) {
                throw new IOException(
                        "Failed to obtain key with alias \"" + keyAlias + "\" from " + keystoreFile
                                + ". Wrong password?",
                        e);
            }
            this.privateKey = key;
            Certificate[] certChain = ks.getCertificateChain(keyAlias);
            if ((certChain == null) || (certChain.length == 0)) {
                throw new ParameterException(
                        keystoreFile + " entry \"" + keyAlias + "\" does not contain certificates");
            }
            this.certs = new ArrayList<>(certChain.length);
            for (Certificate cert : certChain) {
                this.certs.add((X509Certificate) cert);
            }
        }

        private void loadPrivateKeyAndCertsFromFiles(PasswordRetriever passwordRetriver)
                throws Exception {
            if (keyFile == null) {
                throw new ParameterException("Private key file (--key) must be specified");
            }
            if (certFile == null) {
                throw new ParameterException("Certificate file (--cert) must be specified");
            }
            byte[] privateKeyBlob = readFully(new File(keyFile));

            PKCS8EncodedKeySpec keySpec;
            // Potentially encrypted key blob
            try {
                EncryptedPrivateKeyInfo encryptedPrivateKeyInfo =
                        new EncryptedPrivateKeyInfo(privateKeyBlob);

                // The blob is indeed an encrypted private key blob
                String passwordSpec =
                        (keyPasswordSpec != null) ? keyPasswordSpec : PasswordRetriever.SPEC_STDIN;
                String keyPassword =
                        passwordRetriver.getPassword(
                                passwordSpec, "Private key password for " + name);

                PBEKeySpec decryptionKeySpec = new PBEKeySpec(keyPassword.toCharArray());
                SecretKey decryptionKey =
                        SecretKeyFactory.getInstance(encryptedPrivateKeyInfo.getAlgName())
                                .generateSecret(decryptionKeySpec);
                keySpec = encryptedPrivateKeyInfo.getKeySpec(decryptionKey);
            } catch (IOException e) {
                // The blob is not an encrypted private key blob
                if (keyPasswordSpec == null) {
                    // Given that no password was specified, assume the blob is an unencrypted
                    // private key blob
                    keySpec = new PKCS8EncodedKeySpec(privateKeyBlob);
                } else {
                    throw new InvalidKeySpecException(
                            "Failed to parse encrypted private key blob " + keyFile, e);
                }
            }

            // Load the private key from its PKCS #8 encoded form.
            try {
                privateKey = loadPkcs8EncodedPrivateKey(keySpec);
            } catch (InvalidKeySpecException e) {
                throw new InvalidKeySpecException(
                        "Failed to load PKCS #8 encoded private key from " + keyFile, e);
            }

            // Load certificates
            Collection<? extends Certificate> certs;
            try (FileInputStream in = new FileInputStream(certFile)) {
                certs = CertificateFactory.getInstance("X.509").generateCertificates(in);
            }
            List<X509Certificate> certList = new ArrayList<>(certs.size());
            for (Certificate cert : certs) {
                certList.add((X509Certificate) cert);
            }
            this.certs = certList;
        }

        private static PrivateKey loadPkcs8EncodedPrivateKey(PKCS8EncodedKeySpec spec)
                throws InvalidKeySpecException, NoSuchAlgorithmException {
            try {
                return KeyFactory.getInstance("RSA").generatePrivate(spec);
            } catch (InvalidKeySpecException expected) {
            }
            try {
                return KeyFactory.getInstance("EC").generatePrivate(spec);
            } catch (InvalidKeySpecException expected) {
            }
            try {
                return KeyFactory.getInstance("DSA").generatePrivate(spec);
            } catch (InvalidKeySpecException expected) {
            }
            throw new InvalidKeySpecException("Not an RSA, EC, or DSA private key");
        }
    }

    private static byte[] readFully(File file) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (FileInputStream in = new FileInputStream(file)) {
            drain(in, result);
        }
        return result.toByteArray();
    }

    private static void drain(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int chunkSize;
        while ((chunkSize = in.read(buf)) != -1) {
            out.write(buf, 0, chunkSize);
        }
    }

    /**
     * Indicates that there is an issue with command-line parameters provided to this tool.
     */
    private static class ParameterException extends Exception {
        private static final long serialVersionUID = 1L;

        ParameterException(String message) {
            super(message);
        }
    }
}
