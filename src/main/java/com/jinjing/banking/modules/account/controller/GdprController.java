package com.jinjing.banking.modules.account.controller;

import com.jinjing.banking.modules.account.service.GdprService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * GDPR / DSGVO 接口。
 *
 * <p>权限：本人或 ADMIN（在 {@link GdprService} 内校验，因为要比较数据库主键）。
 * 这里保留 {@code @PreAuthorize("isAuthenticated()")} 作为第一道闸门，匿名请求直接 403。
 */
@Tag(name = "GDPR", description = "Data export and deletion (DSGVO)")
@RestController
@RequestMapping("/api/gdpr")
@RequiredArgsConstructor
public class GdprController {

    private final GdprService gdprService;

    @Operation(summary = "Export user data", description = "Self or ADMIN only; returns profile, accounts and transactions")
    @GetMapping("/user/{userId}/export")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> exportUserData(@PathVariable Long userId,
                                                              Authentication authentication) {
        return ResponseEntity.ok(gdprService.exportUserData(userId, authentication));
    }

    @Operation(summary = "Anonymize user data",
            description = "Self or ADMIN only; PII is anonymized while transaction history is retained for statutory retention")
    @DeleteMapping("/user/{userId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> deleteUserData(@PathVariable Long userId,
                                                              Authentication authentication) {
        return ResponseEntity.ok(gdprService.anonymizeUser(userId, authentication));
    }
}
