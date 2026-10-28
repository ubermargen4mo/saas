package com.hrips.browser

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

object DefaultBrowser {
    private fun role(context: Context): RoleManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)?.takeIf { it.isRoleAvailable(RoleManager.ROLE_BROWSER) }
        } else null

    fun isDefault(context: Context): Boolean {
        role(context)?.let { return it.isRoleHeld(RoleManager.ROLE_BROWSER) }
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        val info = context.packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
        return info?.activityInfo?.packageName == context.packageName
    }

    fun requestIntent(context: Context): Intent =
        role(context)?.takeIf { !it.isRoleHeld(RoleManager.ROLE_BROWSER) }?.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
            ?: settingsIntent()

    fun settingsIntent() = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
}
