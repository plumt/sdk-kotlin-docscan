package net.plumt.docscan

import android.app.Activity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Play 서비스 문제([DocScanFailureReason.PLAY_SERVICES_MISSING]·[DocScanFailureReason.PLAY_SERVICES_UPDATE_REQUIRED])를
 * 사용자가 고칠 수 있으면 Play 서비스의 안내 대화상자(설치·업데이트·사용 설정)를 띄운다.
 * [DocScanFailureReason.PLAY_SERVICES_API_UNAVAILABLE] 은 상태 코드(17)로는 고칠 방법이 없어 업데이트 안내 대화상자를 띄운다.
 *
 * @return 대화상자를 띄웠으면 true. 다른 분류이거나 사용자가 고칠 수 없는 상태면 false — 앱이 직접 안내한다
 */
public fun DocScanException.showPlayServicesResolution(activity: Activity, requestCode: Int = 0): Boolean =
    showPlayServicesResolution(this, activity, requestCode, PlayServicesDialogs.Default)

/** 시험에서 GoogleApiAvailability 를 가리는 틈. */
internal interface PlayServicesDialogs {
    fun isUserResolvable(code: Int): Boolean
    fun show(activity: Activity, code: Int, requestCode: Int): Boolean

    object Default : PlayServicesDialogs {
        override fun isUserResolvable(code: Int): Boolean = GoogleApiAvailability.getInstance().isUserResolvableError(code)
        override fun show(activity: Activity, code: Int, requestCode: Int): Boolean =
            GoogleApiAvailability.getInstance().showErrorDialogFragment(activity, code, requestCode)
    }
}

internal fun showPlayServicesResolution(
    error: DocScanException,
    activity: Activity,
    requestCode: Int,
    dialogs: PlayServicesDialogs,
): Boolean {
    val code = when (error.reason) {
        DocScanFailureReason.PLAY_SERVICES_MISSING, DocScanFailureReason.PLAY_SERVICES_UPDATE_REQUIRED -> error.statusCode ?: return false
        DocScanFailureReason.PLAY_SERVICES_API_UNAVAILABLE -> ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED
        else -> return false
    }
    if (!dialogs.isUserResolvable(code)) return false
    return dialogs.show(activity, code, requestCode)
}
