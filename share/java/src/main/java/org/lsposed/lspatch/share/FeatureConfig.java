package org.lsposed.lspatch.share;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;

/**
 * Shared configuration for per-app Identity Randomizer & Anti-Detection features.
 * Persisted in {@link PatchConfig} (embedded in config.json) and dynamically served by
 * Manager for Local-mode (dynamic) patched apps.
 */
public class FeatureConfig {

    // --- Anti-Detection ---
    public boolean antiDetectionEnabled;
    public boolean hideLspatchTraces;
    public boolean hidePackages;
    public boolean hideDevOptions;
    public boolean disableFlagSecure;
    public boolean hideRootFiles;
    public boolean maskProcMaps;

    // --- Identity Randomizer / Spoofer ---
    public boolean identitySpoofEnabled;
    public String androidId;
    public String widevineId;
    public String advertisingId;
    public String gsfId;
    public String appSetId;
    public String bootId;

    // Build / Device fingerprint
    public String devicePreset;
    public String brand;
    public String manufacturer;
    public String model;
    public String device;
    public String product;
    public String hardware;
    public String board;
    public String bootloader;
    public String fingerprint;
    public String serial;

    // Network / Wi-Fi / Bluetooth
    public String wifiMac;
    public String wifiBssid;
    public String wifiSsid;
    public String bluetoothMac;
    public String bluetoothName;

    // Telephony / SIM / Carrier
    public String imei;
    public String imsi;
    public String iccid;
    public String phoneNumber;
    public String carrierName;
    public String carrierMccMnc;
    public String countryIso;

    // WebView / User-Agent
    public String userAgent;

    public FeatureConfig() {}

    public FeatureConfig copy() {
        FeatureConfig c = new FeatureConfig();
        c.antiDetectionEnabled = antiDetectionEnabled;
        c.hideLspatchTraces = hideLspatchTraces;
        c.hidePackages = hidePackages;
        c.hideDevOptions = hideDevOptions;
        c.disableFlagSecure = disableFlagSecure;
        c.hideRootFiles = hideRootFiles;
        c.maskProcMaps = maskProcMaps;

        c.identitySpoofEnabled = identitySpoofEnabled;
        c.androidId = androidId;
        c.widevineId = widevineId;
        c.advertisingId = advertisingId;
        c.gsfId = gsfId;
        c.appSetId = appSetId;
        c.bootId = bootId;

        c.devicePreset = devicePreset;
        c.brand = brand;
        c.manufacturer = manufacturer;
        c.model = model;
        c.device = device;
        c.product = product;
        c.hardware = hardware;
        c.board = board;
        c.bootloader = bootloader;
        c.fingerprint = fingerprint;
        c.serial = serial;

        c.wifiMac = wifiMac;
        c.wifiBssid = wifiBssid;
        c.wifiSsid = wifiSsid;
        c.bluetoothMac = bluetoothMac;
        c.bluetoothName = bluetoothName;

        c.imei = imei;
        c.imsi = imsi;
        c.iccid = iccid;
        c.phoneNumber = phoneNumber;
        c.carrierName = carrierName;
        c.carrierMccMnc = carrierMccMnc;
        c.countryIso = countryIso;

        c.userAgent = userAgent;
        return c;
    }

    public boolean hasAnyAntiDetection() {
        return antiDetectionEnabled
                || hideLspatchTraces
                || hidePackages
                || hideDevOptions
                || disableFlagSecure
                || hideRootFiles
                || maskProcMaps;
    }

    public boolean hasAnyBuildSpoof() {
        return nonEmpty(brand)
                || nonEmpty(manufacturer)
                || nonEmpty(model)
                || nonEmpty(device)
                || nonEmpty(product)
                || nonEmpty(hardware)
                || nonEmpty(board)
                || nonEmpty(bootloader)
                || nonEmpty(fingerprint)
                || nonEmpty(serial);
    }

    public boolean hasAnyIdentitySpoof() {
        return identitySpoofEnabled
                || nonEmpty(androidId)
                || nonEmpty(widevineId)
                || nonEmpty(advertisingId)
                || nonEmpty(gsfId)
                || nonEmpty(appSetId)
                || nonEmpty(bootId)
                || hasAnyBuildSpoof()
                || nonEmpty(wifiMac)
                || nonEmpty(wifiBssid)
                || nonEmpty(wifiSsid)
                || nonEmpty(bluetoothMac)
                || nonEmpty(bluetoothName)
                || nonEmpty(imei)
                || nonEmpty(imsi)
                || nonEmpty(iccid)
                || nonEmpty(phoneNumber)
                || nonEmpty(carrierName)
                || nonEmpty(carrierMccMnc)
                || nonEmpty(countryIso)
                || nonEmpty(userAgent);
    }

    public boolean isEmpty() {
        return !hasAnyAntiDetection() && !hasAnyIdentitySpoof();
    }

    public boolean needsNativeHooks() {
        return maskProcMaps
                || hideRootFiles
                || hideDevOptions
                || hideLspatchTraces
                || nonEmpty(bootId)
                || hasAnyBuildSpoof();
    }

    public static boolean nonEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    public void enableAllAntiDetection() {
        antiDetectionEnabled = true;
        hideLspatchTraces = true;
        hidePackages = true;
        hideDevOptions = true;
        disableFlagSecure = true;
        hideRootFiles = true;
        maskProcMaps = true;
    }

    public static final class DevicePreset {
        public final String name;
        public final String brand;
        public final String manufacturer;
        public final String model;
        public final String device;
        public final String product;
        public final String hardware;
        public final String board;
        public final String bootloader;
        public final String fingerprint;

        public DevicePreset(
                String name,
                String brand,
                String manufacturer,
                String model,
                String device,
                String product,
                String hardware,
                String board,
                String bootloader,
                String fingerprint) {
            this.name = name;
            this.brand = brand;
            this.manufacturer = manufacturer;
            this.model = model;
            this.device = device;
            this.product = product;
            this.hardware = hardware;
            this.board = board;
            this.bootloader = bootloader;
            this.fingerprint = fingerprint;
        }
    }

    public static final DevicePreset[] DEVICE_PRESETS = new DevicePreset[] {
        new DevicePreset(
                "Google Pixel 9 Pro",
                "google",
                "Google",
                "Pixel 9 Pro",
                "caiman",
                "caiman",
                "caiman",
                "caiman",
                "caiman-1.4-12345678",
                "google/caiman/caiman:15/AP4A.250205.002/12874123:user/release-keys"),
        new DevicePreset(
                "Samsung Galaxy S24 Ultra",
                "samsung",
                "samsung",
                "SM-S928B",
                "e3q",
                "e3qxxx",
                "qcom",
                "pineapple",
                "S928BXXS4AXK4",
                "samsung/e3qxxx/e3q:14/UP1A.231005.007/S928BXXS4AXK4:user/release-keys"),
        new DevicePreset(
                "OnePlus 12",
                "OnePlus",
                "OnePlus",
                "CPH2581",
                "OP5929L1",
                "CPH2581EEA",
                "qcom",
                "pineapple",
                "unknown",
                "OnePlus/CPH2581EEA/OP5929L1:14/UKQ1.230924.001/T.182f9f2_1-1:user/release-keys"),
        new DevicePreset(
                "Xiaomi 14 Ultra",
                "Xiaomi",
                "Xiaomi",
                "24030PN60G",
                "aurora",
                "aurora_global",
                "qcom",
                "pineapple",
                "unknown",
                "Xiaomi/aurora_global/aurora:14/UKQ1.231003.002/V816.0.9.0.UNAMIXM:user/release-keys"),
        new DevicePreset(
                "Nothing Phone (2)",
                "Nothing",
                "Nothing",
                "A065",
                "Pong",
                "Pong",
                "qcom",
                "taro",
                "unknown",
                "Nothing/Pong/Pong:14/UQ1A.240205.004/2408281910:user/release-keys"),
        new DevicePreset(
                "Sony Xperia 1 VI",
                "Sony",
                "Sony",
                "XQ-EC54",
                "XQ-EC54",
                "XQ-EC54_EEA",
                "qcom",
                "pineapple",
                "xboot",
                "Sony/XQ-EC54_EEA/XQ-EC54:14/69.0.A.2.50/069000A00200500020240801:user/release-keys"),
    };

    private static final String[][] CARRIERS = new String[][] {
        {"T-Mobile", "310260", "us"},
        {"AT&T", "310410", "us"},
        {"Verizon", "311480", "us"},
        {"Vodafone", "23415", "gb"},
        {"Telekom.de", "26201", "de"},
        {"Orange", "20801", "fr"},
        {"Docomo", "44010", "jp"},
    };

    private static final String[] WIFI_SSIDS = new String[] {
        "Home_WiFi_5G", "TP-Link_A4F2", "NETGEAR72-5G", "ASUS_AX3000", "Linksys04192", "Studio_5GHz"
    };

    private static final SecureRandom RNG = new SecureRandom();

    public void applyDevicePreset(DevicePreset preset) {
        if (preset == null) return;
        this.devicePreset = preset.name;
        this.brand = preset.brand;
        this.manufacturer = preset.manufacturer;
        this.model = preset.model;
        this.device = preset.device;
        this.product = preset.product;
        this.hardware = preset.hardware;
        this.board = preset.board;
        this.bootloader = preset.bootloader;
        this.fingerprint = preset.fingerprint;
        this.userAgent = buildDefaultUserAgent(preset.model);
    }

    public static String buildDefaultUserAgent(String modelName) {
        String m = nonEmpty(modelName) ? modelName : "Pixel 9 Pro";
        return "Mozilla/5.0 (Linux; Android 14; " + m + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    }

    public void regenerateAll(boolean pickNewPreset) {
        this.identitySpoofEnabled = true;
        this.androidId = randomHex(16);
        this.widevineId = randomHex(32);
        this.advertisingId = UUID.randomUUID().toString();
        this.gsfId = randomDecDigits(19);
        this.appSetId = UUID.randomUUID().toString();
        this.bootId = UUID.randomUUID().toString();

        if (pickNewPreset || !nonEmpty(this.model)) {
            DevicePreset preset = DEVICE_PRESETS[RNG.nextInt(DEVICE_PRESETS.length)];
            applyDevicePreset(preset);
        } else {
            this.userAgent = buildDefaultUserAgent(this.model);
        }
        this.serial = randomAlphaNumUpper(10);

        this.wifiMac = randomMac();
        this.wifiBssid = randomMac();
        this.wifiSsid = WIFI_SSIDS[RNG.nextInt(WIFI_SSIDS.length)];
        this.bluetoothMac = randomMac();
        this.bluetoothName = nonEmpty(this.model) ? this.model : "Android Device";

        String[] carrier = CARRIERS[RNG.nextInt(CARRIERS.length)];
        this.carrierName = carrier[0];
        this.carrierMccMnc = carrier[1];
        this.countryIso = carrier[2];
        this.imei = randomImei();
        this.imsi = this.carrierMccMnc + randomDecDigits(15 - this.carrierMccMnc.length());
        this.iccid = "89" + (this.carrierMccMnc.length() >= 3 ? this.carrierMccMnc.substring(0, 3) : "144") + randomDecDigits(14);
        this.phoneNumber = "+" + (countryIso.equals("us") ? "1" : "44") + randomDecDigits(10);
    }

    public static String randomHex(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(Integer.toHexString(RNG.nextInt(16)));
        }
        return sb.toString();
    }

    public static String randomAlphaNumUpper(int length) {
        final String chars = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(RNG.nextInt(chars.length())));
        }
        return sb.toString();
    }

    public static String randomDecDigits(int length) {
        if (length <= 0) return "";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('0' + RNG.nextInt(10)));
        }
        return sb.toString();
    }

    public static String randomMac() {
        byte[] mac = new byte[6];
        RNG.nextBytes(mac);
        mac[0] = (byte) ((mac[0] & (byte) 0xFC) | (byte) 0x02); // locally administered, unicast
        return String.format(
                Locale.ROOT,
                "%02x:%02x:%02x:%02x:%02x:%02x",
                mac[0] & 0xff,
                mac[1] & 0xff,
                mac[2] & 0xff,
                mac[3] & 0xff,
                mac[4] & 0xff,
                mac[5] & 0xff);
    }

    public static String randomImei() {
        int[] digits = new int[15];
        digits[0] = 3;
        digits[1] = 5;
        for (int i = 2; i < 14; i++) {
            digits[i] = RNG.nextInt(10);
        }
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int d = digits[i];
            if (i % 2 == 1) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
        }
        digits[14] = (10 - (sum % 10)) % 10;
        StringBuilder sb = new StringBuilder(15);
        for (int d : digits) sb.append((char) ('0' + d));
        return sb.toString();
    }
}
