package com.mobildroid.cloudshelf.app.data.s3

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bucket -> region cache. In-memory for now; if it turns out we want
 * persistence across app restarts we'll Room-back this in Phase 3 when
 * the transfer-queue database lands.
 *
 * Region rarely changes for a given bucket — once resolved, this saves a
 * GetBucketLocation request (and a request charge) per future op.
 */
@Singleton
class BucketRegionCache @Inject constructor() {

    private val map = ConcurrentHashMap<String, String>()

    fun get(bucket: String): String? = map[bucket]

    fun put(bucket: String, region: String) {
        map[bucket] = region
    }

    fun clear() = map.clear()
}
