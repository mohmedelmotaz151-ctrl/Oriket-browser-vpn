package com.orkeit.browservpn

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import java.util.Date

/**
 * Loads and displays an AdMob App Open ad. Failed requests are retried while
 * the app process remains alive; ads are refreshed after display and considered
 * stale after four hours.
 */
internal class AppOpenAdManager(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingCallbacks = mutableListOf<() -> Unit>()
    private var appOpenAd: AppOpenAd? = null
    private var isLoadingAd = false
    private var isShowingAd = false
    private var retryScheduled = false
    private var loadTime: Long = 0L

    fun loadAd(onAdLoaded: (() -> Unit)? = null) {
        if (onAdLoaded != null) {
            if (isAdAvailable()) {
                onAdLoaded()
                return
            }
            pendingCallbacks.add(onAdLoaded)
        }
        if (isLoadingAd || isAdAvailable()) return

        isLoadingAd = true
        AppOpenAd.load(
            appContext,
            AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    appOpenAd = ad
                    loadTime = Date().time
                    isLoadingAd = false
                    retryScheduled = false
                    Log.i(TAG, "App-open ad loaded successfully")
                    val callbacks = pendingCallbacks.toList()
                    pendingCallbacks.clear()
                    callbacks.forEach { callback ->
                        try {
                            callback()
                        } catch (error: Exception) {
                            Log.e(TAG, "Ad-loaded callback failed", error)
                        }
                    }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoadingAd = false
                    appOpenAd = null
                    Log.e(TAG, "App-open ad failed to load. Code=${error.code}; domain=${error.domain}; message=${error.message}")
                    scheduleRetry()
                }
            }
        )
    }

    private fun scheduleRetry() {
        if (retryScheduled) return
        retryScheduled = true
        mainHandler.postDelayed({
            retryScheduled = false
            loadAd()
        }, RETRY_DELAY_MS)
    }

    fun showAdIfAvailable(activity: Activity) {
        if (isShowingAd || activity.isFinishing || activity.isDestroyed) return

        if (!isAdAvailable()) {
            loadAd {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    showAdIfAvailable(activity)
                }
            }
            return
        }

        val ad = appOpenAd ?: return
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                isShowingAd = true
                Log.i(TAG, "App-open ad is now visible")
            }

            override fun onAdDismissedFullScreenContent() {
                isShowingAd = false
                appOpenAd = null
                loadAd()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                isShowingAd = false
                appOpenAd = null
                Log.e(TAG, "App-open ad failed to show. Code=${error.code}; domain=${error.domain}; message=${error.message}")
                loadAd()
            }
        }
        ad.show(activity)
    }

    private fun isAdAvailable(): Boolean =
        appOpenAd != null && Date().time - loadTime < FOUR_HOURS_MS

    private companion object {
        const val TAG = "OriketAppOpenAd"
        const val AD_UNIT_ID = "ca-app-pub-6392100417582868/9605629838"
        const val FOUR_HOURS_MS = 4 * 60 * 60 * 1000L
        const val RETRY_DELAY_MS = 30_000L
    }
}
