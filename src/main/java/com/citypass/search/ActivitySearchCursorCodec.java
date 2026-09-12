package com.citypass.search;

import cn.hutool.json.JSONUtil;
import com.citypass.config.ActivitySearchProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

@Component
public class ActivitySearchCursorCodec {
    private final ActivitySearchProperties properties;

    public ActivitySearchCursorCodec(ActivitySearchProperties properties) {
        this.properties = properties;
    }

    public String encode(ActivitySearchCursor cursor) {
        requireSecret();
        byte[] payload = JSONUtil.toJsonStr(cursor).getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload) + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
    }

    public ActivitySearchCursor decode(String encoded) {
        requireSecret();
        try {
            String[] parts = encoded.split("\\.", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            if (!MessageDigest.isEqual(signature, sign(payload))) throw new IllegalArgumentException();
            ActivitySearchCursor cursor = JSONUtil.toBean(new String(payload, StandardCharsets.UTF_8), ActivitySearchCursor.class);
            if (cursor == null || cursor.getPitId() == null || cursor.getQueryFingerprint() == null
                    || cursor.getSnapshotEpochMillis() == null || cursor.getExpiresAtEpochMillis() == null
                    || cursor.getSortValues() == null) {
                throw new IllegalArgumentException();
            }
            return cursor;
        } catch (Exception e) {
            throw new IllegalArgumentException("搜索游标格式错误或已被篡改");
        }
    }

    public String fingerprint(NormalizedActivitySearchRequest request, long snapshotEpochMillis) {
        String canonical = string(request.getKeyword()) + "\n" + string(request.getCategory()) + "\n"
                + string(request.getEventFrom()) + "\n" + string(request.getEventTo()) + "\n"
                + string(request.getMinPriceCents()) + "\n" + string(request.getMaxPriceCents()) + "\n"
                + string(request.getLongitude()) + "\n" + string(request.getLatitude()) + "\n"
                + string(request.getRadiusMeters()) + "\n" + request.getSort() + "\n" + request.getSize() + "\n"
                + snapshotEpochMillis;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("无法生成搜索查询指纹", e);
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getCursorSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (Exception e) {
            throw new IllegalStateException("无法签名搜索游标", e);
        }
    }

    private void requireSecret() {
        if (properties.getCursorSecret() == null || properties.getCursorSecret().length() < 16) {
            throw new SearchModuleUnavailableException("活动搜索未正确配置：游标密钥至少需要 16 个字符");
        }
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
