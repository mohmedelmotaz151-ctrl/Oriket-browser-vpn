package com.orkeit.browservpn

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import java.util.Date

/**
 * Loads and displays an AdMob App Open ad. Ads are refreshed after display
 * and considered stale after four hours, following Google's app-open guidance.
 */
internal class AppOpenAdManager(context: Context) {
    private val appContext = context.applicationContext
    private var appOpenAd: AppOpenAd? = null
    private var isLoadingAd = false
    private var isShowingAd = false
    private var loadTime: Long = 0L

    fun loadAd(onAdLoaded: (() -> Unit)? = null) {
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
                    Log.d(TAG, "App-open ad loaded")
                    onAdLoaded?.invoke()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoadingAd = false
                    appOpenAd = null
                    Log.w(TAG, "App-open ad failed to load: ${error.message}")
                }
            }
        )
    }

    fun showAdIfAvailable(activity: Activity) {
        if (isShowingAd) return
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
            }

            override fun onAdDismissedFullScreenContent() {
                isShowingAd = false
                appOpenAd = null
                loadAd()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                isShowingAd = false
                appOpenAd = null
                Log.w(TAG, "App-open ad failed to show: ${error.message}")
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
    }
}
