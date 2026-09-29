package com.cabeye.rider.driver

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cabeye.rider.CabEyeApp
import com.cabeye.rider.auth.AccountInfo
import com.cabeye.rider.auth.SpokenNumbers
import com.cabeye.rider.net.ApiResult
import com.cabeye.rider.net.AppRole
import com.cabeye.rider.net.isUnauthorized
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class DriverAccountStep { PHONE, CODE, PROFILE }

/**
 * Everything on the driver's sign-in and profile screen.
 *
 * The profile fields are exactly what a blind rider is told when the driver accepts — name,
 * colour and model — plus the plate, which the rider's app keeps for a sighted helper and for
 * SOS. Nothing here is decoration: an empty vehicle model is a rider standing at a kerb
 * listening for "your driver is here" with no idea which car to walk to.
 */
data class DriverAccountUi(
    val step: DriverAccountStep = DriverAccountStep.PHONE,
    val phone: String = "",
    val code: String = "",
    val name: String = "",
    val vehicleType: String = "AUTO",
    val vehicleModel: String = "",
    val vehicleColour: String = "",
    val vehiclePlate: String = "",
    val licenceNumber: String = "",
    val languages: String = "",
    val busy: Boolean = false,
    val message: String = "",
    /** True when opened from the driver screen to edit, rather than as part of first sign-in. */
    val editing: Boolean = false,
    val stats: String = ""
) {
    val canSave: Boolean
        get() = name.isNotBlank() && vehicleModel.isNotBlank() && vehiclePlate.isNotBlank()
}

enum class DriverField { NAME, VEHICLE_TYPE, VEHICLE_MODEL, VEHICLE_COLOUR, VEHICLE_PLATE, LICENCE, LANGUAGES }

/**
 * Driver sign-in (phone + OTP) and the driver profile.
 *
 * The driver is sighted and about to drive, so this is an ordinary form rather than a voice
 * dialogue — but it feeds straight into what the rider hears, so the required fields are the
 * ones the rider needs to find the car.
 */
class DriverAccountViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CabEyeApp
    private val api get() = app.api
    private val store get() = app.auth

    var ui by mutableStateOf(DriverAccountUi())
        private set

    private companion object {
        const val TAG = "CabEye.DriverAccount"
    }

    /**
     * Called when the account screen appears. A stored session is restored at once, then
     * checked with the server; only a definite 401 sends the driver back to sign in.
     */
    fun start() {
        if (app.driverAccount.value != null) return
        val cached = store.account(AppRole.DRIVER)
        if (store.hasSession(AppRole.DRIVER) && cached != null) {
            restore(cached)
            viewModelScope.launch {
                when (val result = api.me()) {
                    is ApiResult.Ok -> {
                        app.onAccountUpdated(AppRole.DRIVER, result.value)
                        if (!result.value.profileComplete) restore(result.value)
                    }
                    is ApiResult.Failed -> if (result.isUnauthorized) {
                        app.signOut(AppRole.DRIVER)
                        ui = DriverAccountUi(message = "Your sign-in expired. Please sign in again.")
                    }
                }
            }
        }
    }

    private fun restore(account: AccountInfo) {
        if (account.profileComplete) {
            app.onSignedIn(AppRole.DRIVER, account)
        } else {
            // Signed in but the vehicle is missing: straight to the profile.
            app.onSignedIn(AppRole.DRIVER, account)
            ui = formFor(account, editing = false, message = "Add your vehicle so riders can find you.")
        }
    }

    // =================================================================================
    //  Sign-in
    // =================================================================================

    fun onPhoneChange(value: String) {
        ui = ui.copy(phone = value.filter { it.isDigit() || it == '+' || it == ' ' }.take(16), message = "")
    }

    fun onCodeChange(value: String) {
        ui = ui.copy(code = value.filter { it.isDigit() }.take(6), message = "")
    }

    fun sendCode() {
        val phone = SpokenNumbers.mobileNumber(ui.phone)
        if (phone == null) {
            ui = ui.copy(message = "Enter a ten digit mobile number.")
            return
        }
        ui = ui.copy(busy = true, message = "Sending code…")
        viewModelScope.launch {
            ui = when (val result = api.sendOtp(phone, AppRole.DRIVER)) {
                is ApiResult.Ok -> {
                    val auto = result.value.devCode
                    ui.copy(
                        step = DriverAccountStep.CODE,
                        phone = phone,
                        code = auto ?: "",
                        busy = false,
                        message = if (auto != null) "Test mode: the code was filled in for you."
                        else "Enter the six digit code sent to your phone."
                    )
                }
                is ApiResult.Failed -> if (result.detail.startsWith("HTTP 429")) {
                    ui.copy(step = DriverAccountStep.CODE, phone = phone, busy = false, message = result.spoken)
                } else {
                    ui.copy(busy = false, message = result.spoken)
                }
            }
        }
    }

    fun verify() {
        val phone = ui.phone
        val code = ui.code
        if (code.length != 6) {
            ui = ui.copy(message = "The code has six digits.")
            return
        }
        ui = ui.copy(busy = true, message = "Checking…")
        viewModelScope.launch {
            when (val result = api.verifyOtp(phone, AppRole.DRIVER, code)) {
                is ApiResult.Ok -> {
                    val signIn = result.value
                    if (!store.save(AppRole.DRIVER, signIn.token, signIn.account)) {
                        Log.w(TAG, "token could not be stored; this session only")
                    }
                    if (signIn.account.profileComplete) {
                        ui = DriverAccountUi()
                        app.onSignedIn(AppRole.DRIVER, signIn.account)
                    } else {
                        app.onSignedIn(AppRole.DRIVER, signIn.account)
                        ui = formFor(
                            signIn.account, editing = false,
                            message = if (signIn.isNew) "Welcome! Set up your profile — this is what riders hear."
                            else "Add your vehicle so riders can find you."
                        )
                    }
                }
                is ApiResult.Failed -> ui = ui.copy(busy = false, message = result.spoken)
            }
        }
    }

    fun changeNumber() {
        ui = DriverAccountUi(phone = ui.phone)
    }

    // =================================================================================
    //  Profile
    // =================================================================================

    /** Opened from the driver screen's Profile button. */
    fun editProfile() {
        val account = app.driverAccount.value ?: return
        ui = formFor(account, editing = true, message = "")
    }

    fun cancelEdit() {
        ui = DriverAccountUi()
    }

    fun onField(field: DriverField, value: String) {
        val v = value.take(60)
        ui = when (field) {
            DriverField.NAME -> ui.copy(name = v)
            DriverField.VEHICLE_TYPE -> ui.copy(vehicleType = if (v == "CAB") "CAB" else "AUTO")
            DriverField.VEHICLE_MODEL -> ui.copy(vehicleModel = v)
            DriverField.VEHICLE_COLOUR -> ui.copy(vehicleColour = v)
            DriverField.VEHICLE_PLATE -> ui.copy(vehiclePlate = v.uppercase())
            DriverField.LICENCE -> ui.copy(licenceNumber = v.uppercase())
            DriverField.LANGUAGES -> ui.copy(languages = v)
        }.copy(message = "")
    }

    fun saveProfile() {
        if (!ui.canSave) {
            ui = ui.copy(message = "Name, vehicle model and number plate are required.")
            return
        }
        val body = JSONObject()
            .put("name", ui.name.trim())
            .put("vehicleType", ui.vehicleType)
            .put("vehicleModel", ui.vehicleModel.trim())
            .put("vehicleColour", ui.vehicleColour.trim())
            .put("vehiclePlate", ui.vehiclePlate.trim())
            .put("licenceNumber", ui.licenceNumber.trim())
            .put("languages", ui.languages.trim())
        ui = ui.copy(busy = true, message = "Saving…")
        viewModelScope.launch {
            when (val result = api.updateProfile(body)) {
                is ApiResult.Ok -> {
                    app.onAccountUpdated(AppRole.DRIVER, result.value)
                    app.onSignedIn(AppRole.DRIVER, result.value)
                    ui = DriverAccountUi()
                }
                is ApiResult.Failed -> ui = ui.copy(busy = false, message = result.spoken)
            }
        }
    }

    fun signOut() {
        app.signOut(AppRole.DRIVER)
        ui = DriverAccountUi()
    }

    private fun formFor(account: AccountInfo, editing: Boolean, message: String): DriverAccountUi {
        val d = account.driver
        val stats = if (d != null && d.completedTrips > 0) {
            val rating = if (d.ratingCount > 0) " · ★ ${d.ratingAverage} (${d.ratingCount})" else ""
            "${d.completedTrips} trips$rating"
        } else ""
        return DriverAccountUi(
            step = DriverAccountStep.PROFILE,
            phone = account.phone,
            name = account.name,
            vehicleType = d?.vehicleType ?: "AUTO",
            vehicleModel = d?.vehicleModel.orEmpty(),
            vehicleColour = d?.vehicleColour.orEmpty(),
            vehiclePlate = d?.vehiclePlate.orEmpty(),
            licenceNumber = d?.licenceNumber.orEmpty(),
            languages = d?.languages.orEmpty(),
            editing = editing,
            message = message,
            stats = stats
        )
    }
}
