package com.habit.common.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.Properties;

import static java.io.File.separator;

public class Decryptor {

    private static final String APP_NAME = "habit";
    private static final String DEFAULT_KEY = "habit";
    private static final String DEFAULT_SALT = "habit";

    private static final SecretKey KEY;

    static {
        try {
            String deploymentDir = System.getenv("DEPLOYMENT_DIRECTORY");

            File secretsFile = new File(deploymentDir + separator + "data"
                    + separator + APP_NAME + separator + "secrets.properties");

            Properties secrets = new Properties();

            if (secretsFile.exists()) {
                try (FileInputStream stream = new FileInputStream(secretsFile)) {
                    secrets.load(stream);
                }
            } else {
                secrets.put("key", DEFAULT_KEY);
                secrets.put("salt", DEFAULT_SALT);
            }

            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");

            String key = secrets.getProperty("key");
            String salt = secrets.getProperty("salt");

            KeySpec spec = new PBEKeySpec(key.toCharArray(), salt.getBytes(), 65536, 256);

            KEY = new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String encrypt(String value) {
        try {
            byte[] ivBytes = new byte[16];
            new SecureRandom().nextBytes(ivBytes);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, KEY, new IvParameterSpec(ivBytes));

            byte[] cipherBytes = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));

            byte[] outputText = new byte[16 + cipherBytes.length];

            System.arraycopy(ivBytes, 0, outputText, 0, 16);
            System.arraycopy(cipherBytes, 0, outputText, 16, outputText.length - 16);

            return "ENC:" + Base64.getEncoder().encodeToString(outputText);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String decrypt(String value) {
        try {
            if (value.startsWith("ENC:")) {
                value = value.replaceFirst("ENC:", "");
                byte[] inputBytes = Base64.getDecoder().decode(value);

                byte[] ivBytes = new byte[16];
                byte[] cipherBytes = new byte[inputBytes.length - 16];

                System.arraycopy(inputBytes, 0, ivBytes, 0, 16);
                System.arraycopy(inputBytes, 16, cipherBytes, 0, cipherBytes.length);

                Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipher.init(Cipher.DECRYPT_MODE, KEY, new IvParameterSpec(ivBytes));

                byte[] plainText = cipher.doFinal(cipherBytes);
                return new String(plainText, StandardCharsets.UTF_8);
            } else {
                return value;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
