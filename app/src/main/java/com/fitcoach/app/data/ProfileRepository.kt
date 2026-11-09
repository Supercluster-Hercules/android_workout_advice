package com.fitcoach.app.data

import android.content.Context
import com.fitcoach.engine.Sex
import com.fitcoach.engine.UserProfile

/** 用 SharedPreferences 把用户资料保存在本机。 */
class ProfileRepository(context: Context) {

    private val prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)

    fun load(): UserProfile = UserProfile(
        age = prefs.getInt(KEY_AGE, 0).takeIf { it > 0 },
        sex = prefs.getString(KEY_SEX, null)?.let { runCatching { Sex.valueOf(it) }.getOrNull() },
        maxHeartRate = prefs.getInt(KEY_MAX_HR, 0).takeIf { it > 0 },
    )

    fun save(profile: UserProfile) {
        prefs.edit()
            .putInt(KEY_AGE, profile.age ?: 0)
            .putString(KEY_SEX, profile.sex?.name)
            .putInt(KEY_MAX_HR, profile.maxHeartRate ?: 0)
            .apply()
    }

    private companion object {
        const val KEY_AGE = "age"
        const val KEY_SEX = "sex"
        const val KEY_MAX_HR = "max_hr"
    }
}
