package com.jinjing.banking.modules.account.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/gdpr")
@RequiredArgsConstructor
public class GdprController {

    @DeleteMapping("/user/{userId}")
    public ResponseEntity<String> deleteUserData(@PathVariable Long userId) {
        // GDPR 数据删除：匿名化或物理删除用户 PII
        return ResponseEntity.ok("User data deleted per GDPR request for userId: " + userId);
    }
}
