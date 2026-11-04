package com.example.booking.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class AvailabilityCache {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityCache.class);

    private final CacheManager cacheManager;

    public AvailabilityCache(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public void evictAfterCommit(Long resourceId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictNow(resourceId);
                }
            });
        } else {
            evictNow(resourceId);
        }
    }

    private void evictNow(Long resourceId) {
        try {
            Cache cache = cacheManager.getCache(AvailabilityService.CACHE_NAME);
            if (cache != null) {
                cache.evict(resourceId);
            }
        } catch (RuntimeException e) {
            log.warn("Could not evict availability cache for resource {} ({})", resourceId, e.getMessage());
        }
    }
}