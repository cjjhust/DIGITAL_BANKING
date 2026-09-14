package com.jinjing.banking.modules.account.controller;

import com.jinjing.banking.modules.account.dto.UserDto;
import com.jinjing.banking.modules.account.service.AdminService;
import com.jinjing.banking.modules.ledger.service.LedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理员管理接口
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final LedgerService ledgerService;

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<UserDto>> listUsers() {
        List<UserDto> users = adminService.listAllUsers();
        return ResponseEntity.ok(users);
    }

    /**
     * 复式记账对账：每个币种下借方合计必须等于贷方合计。
     * 任何单边写入（只写了一条分录、金额不一致）都会让某个币种不平，在这里被抓到。
     */
    @GetMapping("/ledger/reconcile")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<LedgerService.ReconciliationReport> reconcile() {
        return ResponseEntity.ok(ledgerService.reconcile());
    }
}
