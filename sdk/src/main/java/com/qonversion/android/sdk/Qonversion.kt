package com.qonversion.android.sdk

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Pair
import androidx.preference.PreferenceManager
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.SkuDetails
import com.qonversion.android.s.LifecycleCallback
import com.qonversion.android.sdk.ad.AdvertisingProvider
import com.qonversion.android.sdk.billing.Billing
import com.qonversion.android.sdk.converter.GooglePurchaseConverter
import com.qonversion.android.sdk.converter.PurchaseConverter
import com.qonversion.android.sdk.extractor.SkuDetailsTokenExtractor
import com.qonversion.android.sdk.logger.ConsoleLogger
import com.qonversion.android.sdk.logger.StubLogger
import com.qonversion.android.sdk.storage.TokenStorage
import com.qonversion.android.sdk.storage.UserPropertiesStorage
import com.qonversion.android.sdk.validator.TokenValidator

class Qonversion private constructor(
    private val billing: QonversionBilling?,
    private val repository: QonversionRepository,
    private val converter: PurchaseConverter<Pair<SkuDetails, Purchase>>
) {

    init {
        billing?.setReadyListener { purchase, details ->
            purchase(details, purchase)
        }
    }

    @Volatile
    var billingClient: Billing? = billing
        @Synchronized private set
        @Synchronized get


    companion object {

        private const val SDK_VERSION = "1.1.0"
        private const val PROPERTY_UPLOAD_PERIOD = 5 * 1000

        @JvmStatic
        @Volatile
        var instance: Qonversion? = null
            @Synchronized private set
            @Synchronized get


        @JvmStatic
        fun initialize(
            context: Application,
            key: String,
            internalUserId: String
        ): Qonversion {
            return initialize(context, key, internalUserId, null, false, null)
        }

        @JvmStatic
        fun initialize(
            context: Application,
            key: String
        ): Qonversion {
            return initialize(context, key, "", null, false, null)
        }

        @JvmStatic
        fun initialize(
            context: Application,
            key: String,
            internalUserId: String,
            callback: QonversionCallback?
        ): Qonversion {
            return initialize(context, key, internalUserId, null, false, callback)
        }

        @JvmStatic
        fun initialize(
            context: Application,
            key: String,
            callback: QonversionCallback?
        ): Qonversion {
            return initialize(context, key, "", null, false, callback)
        }

        @JvmStatic
        fun initialize(
            context: Application,
            key: String,
            internalUserId: String,
            billingBuilder: QonversionBillingBuilder?,
            autoTracking: Boolean
        ): Qonversion {
            return initialize(context, key, internalUserId, billingBuilder, autoTracking, null)
        }

        @JvmStatic
        fun initialize(
            context: Application,
            key: String,
            billingBuilder: QonversionBillingBuilder?,
            autoTracking: Boolean
        ): Qonversion {
            return initialize(context, key, "", billingBuilder, autoTracking, null)
        }

        @JvmStatic
        fun initialize(
            context: Application,
            key: String,
            billingBuilder: QonversionBillingBuilder?,
            autoTracking: Boolean,
            callback: QonversionCallback?
        ): Qonversion {
            return initialize(context, key, "", billingBuilder, autoTracking, callback)
        }

        @JvmStatic
        fun initialize(
            context: Application,
            key: String,
            internalUserId: String,
            billingBuilder: QonversionBillingBuilder?,
            autoTracking: Boolean,
            callback: QonversionCallback?
        ): Qonversion {
            if (instance != null) {
                return instance!!
            }

            if (key.isEmpty()) {
                throw RuntimeException("Qonversion initialization error! Key should not be empty!")
            }

            if (autoTracking && billingBuilder == null) {
                throw RuntimeException("Qonversion initialization error! billingBuilder must not be null, when auto tracking is TRUE")
            }

            val logger = if (BuildConfig.DEBUG) {
                ConsoleLogger()
            } else {
                StubLogger()
            }
            val storage = TokenStorage(
                PreferenceManager.getDefaultSharedPreferences(context),
                TokenValidator()
            )
            val propertiesStorage = UserPropertiesStorage()
            val environment = EnvironmentProvider(context)
            val config = QonversionConfig(SDK_VERSION, key, autoTracking)
            val repository = QonversionRepository.initialize(
                context,
                storage,
                propertiesStorage,
                logger,
                environment,
                config,
                internalUserId
            )
            val converter = GooglePurchaseConverter(SkuDetailsTokenExtractor())
            val adProvider = AdvertisingProvider()
            adProvider.init(context, object : AdvertisingProvider.Callback {
                override fun onSuccess(advertisingId: String) {
                    repository.init(advertisingId, callback)
                }

                override fun onFailure(t: Throwable) {
                    repository.init(callback)
                }
            })
            val billingClient = if (billingBuilder != null) {
                QonversionBilling(context, billingBuilder, logger, autoTracking)
            } else {
                null
            }

            val fbAttributionId = FacebookAttribution().getAttributionId(context.contentResolver)
            fbAttributionId?.let {
                repository.setProperty(QUserProperties.FacebookAttribution.userPropertyCode,
                    it
                )
            }

            val lifecycleCallback = LifecycleCallback(repository)
            context.registerActivityLifecycleCallbacks(lifecycleCallback)
            sendPropertiesAtPeriod(repository)

            return Qonversion(billingClient, repository, converter).also {
                instance = it
            }
        }

        private fun sendPropertiesAtPeriod(repository: QonversionRepository){
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed(object : Runnable {
                override fun run() {
                    repository.sendProperties()
                    handler.postDelayed(this, PROPERTY_UPLOAD_PERIOD.toLong())
                }
            }, PROPERTY_UPLOAD_PERIOD.toLong())
        }
    }

    fun purchase(details: SkuDetails, p: Purchase) {
        purchase(android.util.Pair.create(details, p), null)
    }

    fun purchase(details: SkuDetails, p: Purchase, callback: QonversionCallback?) {
        purchase(android.util.Pair.create(details, p), callback)
    }

    private fun purchase(
        purchaseInfo: android.util.Pair<SkuDetails, Purchase>,
        callback: QonversionCallback?
    ) {
        val purchase = converter.convert(purchaseInfo)
        repository.purchase(purchase, callback)
    }

    fun attribution(
        conversionInfo: Map<String, Any>,
        from: AttributionSource,
        conversionUid: String
    ) {
        repository.attribution(conversionInfo, from.id, conversionUid)
    }

    fun setProperty(key: QUserProperties, value: String) {
        repository.setProperty(key.userPropertyCode, value)
    }

    fun setUserProperty(key: String, value: String) {
        repository.setProperty(key, value)
    }

    fun setUserID(value: String){
        repository.setProperty(QUserProperties.CustomUserId.userPropertyCode, value)
    }
}


