package exchange.core2.rest.controller;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.api.*;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.rest.dto.AdjustBalanceRequest;
import exchange.core2.rest.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.ExecutionException;

/**
 * REST controller for user management operations.
 *
 * ENDPOINTS:
 * - POST   /api/users             - Create a new user
 * - POST   /api/users/{uid}/balance  - Adjust user balance (deposit/withdrawal)
 * - POST   /api/users/{uid}/suspend  - Suspend user (prevent trading)
 * - POST   /api/users/{uid}/resume   - Resume suspended user
 *
 * USAGE EXAMPLES:
 *
 * Create user:
 *   curl -X POST http://localhost:8080/api/users?uid=301
 *
 * Deposit funds:
 *   curl -X POST http://localhost:8080/api/users/301/balance \
 *     -H "Content-Type: application/json" \
 *     -d '{"currency":15,"amount":2000000000,"transactionId":1001}'
 *
 * Withdraw funds:
 *   curl -X POST http://localhost:8080/api/users/301/balance \
 *     -H "Content-Type: application/json" \
 *     -d '{"currency":15,"amount":-1000000000,"transactionId":1002}'
 *
 * Suspend user:
 *   curl -X POST http://localhost:8080/api/users/301/suspend
 */
@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final ExchangeApi exchangeApi;

    /**
     * Create a new user.
     *
     * @param uid User ID (must be unique)
     * @return Success/failure response
     */
    @PostMapping
    public ApiResponse<String> createUser(@RequestParam long uid) {
        log.info("Creating user: uid={}", uid);

        try {
            // Submit command and wait for result
            CommandResultCode result = exchangeApi.submitCommandAsync(
                    ApiAddUser.builder()
                            .uid(uid)
                            .build()
            ).get();

            if (result == CommandResultCode.SUCCESS) {
                return ApiResponse.success("User created successfully", "uid=" + uid);
            } else {
                return ApiResponse.error("Failed to create user", result.toString());
            }

        } catch (InterruptedException | ExecutionException e) {
            log.error("Error creating user uid={}", uid, e);
            return ApiResponse.error("Error creating user", e.getMessage());
        }
    }

    /**
     * Adjust user balance (deposit or withdrawal).
     *
     * @param uid User ID
     * @param request Balance adjustment details
     * @return Success/failure response
     */
    @PostMapping("/{uid}/balance")
    public ApiResponse<String> adjustBalance(
            @PathVariable long uid,
            @RequestBody AdjustBalanceRequest request) {

        String operation = request.getAmount() > 0 ? "deposit" : "withdrawal";
        log.info("Adjusting balance: uid={}, currency={}, amount={}, txId={}",
                uid, request.getCurrency(), request.getAmount(), request.getTransactionId());

        try {
            CommandResultCode result = exchangeApi.submitCommandAsync(
                    ApiAdjustUserBalance.builder()
                            .uid(uid)
                            .currency(request.getCurrency())
                            .amount(request.getAmount())
                            .transactionId(request.getTransactionId())
                            .build()
            ).get();

            if (result == CommandResultCode.SUCCESS) {
                return ApiResponse.success(
                        operation + " successful",
                        String.format("uid=%d, amount=%d, currency=%d",
                                uid, request.getAmount(), request.getCurrency())
                );
            } else {
                return ApiResponse.error("Failed to adjust balance", result.toString());
            }

        } catch (InterruptedException | ExecutionException e) {
            log.error("Error adjusting balance for uid={}", uid, e);
            return ApiResponse.error("Error adjusting balance", e.getMessage());
        }
    }

    /**
     * Suspend a user (prevents all trading activity).
     *
     * @param uid User ID
     * @return Success/failure response
     */
    @PostMapping("/{uid}/suspend")
    public ApiResponse<String> suspendUser(@PathVariable long uid) {
        log.info("Suspending user: uid={}", uid);

        try {
            CommandResultCode result = exchangeApi.submitCommandAsync(
                    ApiSuspendUser.builder()
                            .uid(uid)
                            .build()
            ).get();

            if (result == CommandResultCode.SUCCESS) {
                return ApiResponse.success("User suspended successfully", "uid=" + uid);
            } else {
                return ApiResponse.error("Failed to suspend user", result.toString());
            }

        } catch (InterruptedException | ExecutionException e) {
            log.error("Error suspending user uid={}", uid, e);
            return ApiResponse.error("Error suspending user", e.getMessage());
        }
    }

    /**
     * Resume a suspended user (allows trading again).
     *
     * @param uid User ID
     * @return Success/failure response
     */
    @PostMapping("/{uid}/resume")
    public ApiResponse<String> resumeUser(@PathVariable long uid) {
        log.info("Resuming user: uid={}", uid);

        try {
            CommandResultCode result = exchangeApi.submitCommandAsync(
                    ApiResumeUser.builder()
                            .uid(uid)
                            .build()
            ).get();

            if (result == CommandResultCode.SUCCESS) {
                return ApiResponse.success("User resumed successfully", "uid=" + uid);
            } else {
                return ApiResponse.error("Failed to resume user", result.toString());
            }

        } catch (InterruptedException | ExecutionException e) {
            log.error("Error resuming user uid={}", uid, e);
            return ApiResponse.error("Error resuming user", e.getMessage());
        }
    }
}
