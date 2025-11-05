/*
 * Do not remove or alter the notices in this preamble.
 *
 * Copyright © 2025 Worldline and/or its affiliates.
 *
 * All rights reserved. License grant and user rights and obligations according to the applicable license agreement.
 *
 * Please contact Worldline for questions regarding license and user rights.
 */

package com.onlinepayments.client.kotlin.exampleapp.compose

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wallet.AutoResolveHelper
import com.google.android.gms.wallet.PaymentData
import com.onlinepayments.client.kotlin.exampleapp.common.PaymentSharedViewModel
import com.onlinepayments.client.kotlin.exampleapp.common.googlepay.GooglePayPaymentUtil
import com.onlinepayments.client.kotlin.exampleapp.common.googlepay.GooglePayPaymentViewModel
import com.onlinepayments.client.kotlin.exampleapp.common.utils.Status
import com.onlinepayments.sdk.client.android.model.PreparedPaymentRequest
import com.onlinepayments.sdk.client.android.model.paymentproduct.BasicPaymentProduct
import org.json.JSONException
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private val paymentGooglePayViewModel: GooglePayPaymentViewModel by viewModels()
    private val paymentSharedViewModel: PaymentSharedViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        observePaymentProductStatus()
        observeEncryptedPaymentRequestStatus()
        setContent {
            ComposeApp(paymentSharedViewModel, launchGooglePay = {
                launchGooglePay()
            })
        }
    }

    private fun observePaymentProductStatus() {
        paymentGooglePayViewModel.paymentProductStatus.observe(this) { paymentProductStatus ->
            when (paymentProductStatus) {
                is Status.ApiError -> {
                    paymentSharedViewModel.globalErrorMessage.value =
                        paymentProductStatus.apiError.message
                }

                is Status.Success -> {
                    requestGooglePayPayment(paymentProductStatus.data as BasicPaymentProduct)
                }

                is Status.Failed -> {
                    paymentSharedViewModel.globalErrorMessage.value =
                        paymentProductStatus.throwable.message
                }

                is Status.Loading -> {
                    // No loadingState needed for this fragment
                }

                is Status.None -> {
                    // Init status; nothing to do here
                }
            }
        }
    }

    private fun observeEncryptedPaymentRequestStatus() {
        paymentGooglePayViewModel.encryptedPaymentRequestStatus.observe(this) { encryptedPaymentRequestStatus ->
            when (encryptedPaymentRequestStatus) {
                is Status.ApiError -> {
                    paymentSharedViewModel.globalErrorMessage.value =
                        encryptedPaymentRequestStatus.apiError.message
                }

                is Status.Success -> {
                    val encryptedFieldsData =
                        (encryptedPaymentRequestStatus.data as PreparedPaymentRequest).encryptedFields
                    paymentSharedViewModel.googlePayData.value = encryptedFieldsData
                }

                is Status.Failed -> {
                    paymentSharedViewModel.globalErrorMessage.value =
                        encryptedPaymentRequestStatus.throwable.message
                }

                is Status.Loading -> {
                    // No loadingState needed for this fragment; Google pay has its own loading indicator
                }

                is Status.None -> {
                    // Init status; nothing to do here
                }
            }
        }
    }

    /**
     * Configure and show Google Pay sheet.
     */
    private fun requestGooglePayPayment(basicPaymentProduct: BasicPaymentProduct) {
        val specificData = basicPaymentProduct.paymentProduct320SpecificData
        val amountOfMoney = paymentSharedViewModel.paymentContext.amountOfMoney
        val countryCode = paymentSharedViewModel.paymentContext.countryCode

        if (specificData == null || amountOfMoney?.amount == null || amountOfMoney.currencyCode.isNullOrBlank() || countryCode.isNullOrBlank()) {
            paymentSharedViewModel.globalErrorMessage.value = "Missing required payment data"
            return
        }

        val googlePayUtil = GooglePayPaymentUtil(
            this,
            paymentSharedViewModel.googlePayConfiguration.merchantId,
            paymentSharedViewModel.googlePayConfiguration.merchantName,
            specificData
        )

        val paymentRequest =
            googlePayUtil.getPaymentDataRequest(amountOfMoney, countryCode) ?: run {
                paymentSharedViewModel.globalErrorMessage.value =
                    "Google Pay can't fetch payment data request"
                return
            }

        val isReadyToPayRequest =
            googlePayUtil.getIsReadyToPayRequest(amountOfMoney, countryCode) ?: run {
                paymentSharedViewModel.globalErrorMessage.value =
                    "Google Pay can't fetch payment data request"
                return
            }

        val isReadyToPayTask = googlePayUtil.paymentsClient.isReadyToPay(isReadyToPayRequest)
        isReadyToPayTask.addOnCompleteListener { completedTask ->
            try {
                val isAvailable = completedTask.getResult(ApiException::class.java)
                if (isAvailable) {
                    // Since loadPaymentData may show the UI asking the user to select a payment
                    // method, we use AutoResolveHelper to wait for the user interacting with it.
                    // Once completed, onActivityResult will be called with the result.
                    @Suppress("DEPRECATION")
                    AutoResolveHelper.resolveTask(
                        googlePayUtil.paymentsClient.loadPaymentData(paymentRequest),
                        this,
                        GOOGLE_PAY_REQUEST_CODE
                    )
                }
            } catch (_: ApiException) {
                paymentSharedViewModel.globalErrorMessage.value = "Google pay is not available"
            }
        }
    }

    /**
     * Listener for when Google Pay sheet is finished
     */
    @Deprecated("@see parent")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            GOOGLE_PAY_REQUEST_CODE -> {
                when (resultCode) {
                    RESULT_OK ->
                        data?.let { intent ->
                            PaymentData.getFromIntent(intent)?.let(::handleGooglePaySuccess)
                        }

                    RESULT_CANCELED -> {
                        // No need to do anything
                    }

                    AutoResolveHelper.RESULT_ERROR -> {
                        AutoResolveHelper.getStatusFromIntent(data)?.let { status ->
                            paymentSharedViewModel.globalErrorMessage.value =
                                "Google pay loadPaymentData failed with error code: ${status.statusCode}"
                        }
                    }
                }
            }
        }
    }

    /**
     * After the user has successfully completed the Google Pay steps fetch token data and prepare a payment
     */
    private fun handleGooglePaySuccess(paymentData: PaymentData) {
        val paymentDataJSONString = paymentData.toJson()

        try {
            // Token will be null if PaymentDataRequest was not constructed using fromJson(String).
            val unformattedGooglePayToken = JSONObject(paymentDataJSONString)
                .getJSONObject("paymentMethodData")
                .getJSONObject("tokenizationData").getString("token")

            // Token needs to be formatted when using it to create a payment
            // with mobilePaymentMethodSpecificInput.encryptedPaymentData
            val formattedGooglePayToken = JSONObject.quote(unformattedGooglePayToken)

            paymentGooglePayViewModel.paymentRequest.setValue(
                GOOGLE_PAY_TOKEN_FIELD_ID,
                formattedGooglePayToken
            )
            paymentGooglePayViewModel.encryptGooglePayPayment()
        } catch (exception: JSONException) {
            paymentSharedViewModel.globalErrorMessage.value =
                "Google pay token error ${exception.message}"
        }
    }

    private fun launchGooglePay() {
        paymentGooglePayViewModel.setSession(paymentSharedViewModel.session)
        paymentGooglePayViewModel.getGooglePayPaymentProductDetails(
            paymentSharedViewModel.paymentContext
        )
    }

    companion object {
        private const val GOOGLE_PAY_TOKEN_FIELD_ID = "encryptedPaymentData"
        private const val GOOGLE_PAY_REQUEST_CODE = 991
    }
}
