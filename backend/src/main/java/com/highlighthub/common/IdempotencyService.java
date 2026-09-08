package com.highlighthub.common;

import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * Wraps an operation with Idempotency-Key semantics.
 * - first request: claims the key, runs the operation, records the response
 * - same key + same requestHash: replays the recorded response
 * - same key + different requestHash: 409
 */
@Service
public class IdempotencyService {
    private final IdempotencyRecordMapper mapper;

    public IdempotencyService(IdempotencyRecordMapper mapper) {
        this.mapper = mapper;
    }

    public Object execute(Long userId, String operation, String idemKey, Object requestBody,
                          Supplier<Object> action) {
        if (idemKey == null || idemKey.isBlank() || idemKey.length() > 128) {
            // no usable key: run without idempotency (caller validates keys when required)
            return action.get();
        }
        String requestHash = Utils.requestHash(requestBody);
        IdempotencyRecordEntity existing = mapper.find(userId, operation, idemKey);
        if (existing != null) {
            if (!existing.getRequestHash().equals(requestHash)) {
                throw BusinessException.conflict(ErrorCodes.CONFLICT,
                        "Idempotency-Key was already used with a different request body");
            }
            if ("COMPLETED".equals(existing.getStatus())) {
                return Utils.fromJson(existing.getResponseJson(), Object.class);
            }
            // PROCESSING: previous attempt still running or crashed mid-run
            throw BusinessException.conflict(ErrorCodes.CONFLICT,
                    "an identical request is already in progress; retry shortly");
        }
        int claimed = mapper.insertClaim(userId, operation, idemKey, requestHash);
        if (claimed == 0) {
            throw BusinessException.conflict(ErrorCodes.CONFLICT, "idempotency key raced; retry");
        }
        try {
            Object response = action.get();
            mapper.complete(userId, operation, idemKey,
                    response == null ? null : response.getClass().getSimpleName(),
                    null, Utils.toJson(response));
            return response;
        } catch (RuntimeException e) {
            mapper.deleteClaim(userId, operation, idemKey); // free the key for a corrected retry
            throw e;
        }
    }
}
